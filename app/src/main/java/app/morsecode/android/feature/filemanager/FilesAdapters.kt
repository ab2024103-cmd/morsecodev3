package app.morsecode.android.feature.filemanager

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import app.morsecode.android.R
import app.morsecode.android.core.media.DayGroups
import app.morsecode.android.core.media.Selection
import app.morsecode.android.core.model.DirectoryEntry
import app.morsecode.android.core.model.FileType
import app.morsecode.android.core.model.MediaItem
import app.morsecode.android.core.ui.FileKind
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.TypeTileView
import app.morsecode.android.core.util.Fmt
import app.morsecode.android.core.util.ThemeColors
import app.morsecode.android.di.AppServices
import kotlinx.coroutines.launch

/**
 * The §6.9 listings: the day-grouped grid, the media/app rows and the
 * directory rows.
 *
 * §20.2 ASYNC INTO RECYCLED VIEWS: every thumbnail load carries a BIND TOKEN
 * checked before the bitmap is applied, so a fast scroll can never paint one
 * row's photo into another's tile.
 *
 * §6.9's selection rules are implemented here rather than per screen: every
 * item carries its own check target, a tap on the BODY opens while nothing is
 * selected and toggles once something is, and a long press always enters
 * selection mode.
 */
class MediaGridAdapter(
    private val scope: LifecycleCoroutineScope,
    private val selection: Selection,
    private val onOpen: (MediaItem, List<MediaItem>) -> Unit,
    private val onSelectionChanged: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Header(val group: DayGroups.Group) : Row()
        data class Tile(val item: MediaItem, val siblings: List<MediaItem>) : Row()
    }

    private var rows: List<Row> = emptyList()
    private var flat: List<MediaItem> = emptyList()

    fun submit(groups: List<DayGroups.Group>) {
        val built = ArrayList<Row>()
        val flattened = ArrayList<MediaItem>()
        for (group in groups) {
            built.add(Row.Header(group))
            for (item in group.items) flattened.add(item)
        }
        // The viewer pages through the same list the grid shows (§6.10).
        for (group in groups) {
            for (item in group.items) built.add(Row.Tile(item, flattened))
        }
        // Interleave properly: headers must precede their own group.
        val ordered = ArrayList<Row>()
        for (group in groups) {
            ordered.add(Row.Header(group))
            for (item in group.items) ordered.add(Row.Tile(item, flattened))
        }
        rows = ordered
        flat = flattened
        notifyDataSetChanged()
    }

    fun isHeader(position: Int): Boolean = rows.getOrNull(position) is Row.Header

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_TILE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_HEADER) {
            HeaderHolder(parent.context)
        } else {
            TileHolder(parent.context)
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).bind(row.group)
            is Row.Tile -> (holder as TileHolder).bind(row.item, row.siblings)
        }
    }

    /** "Today · 6 items" with the round select-all toggle on the right (§6.9). */
    private inner class HeaderHolder(context: Context) :
        RecyclerView.ViewHolder(LinearLayout(context)) {

        private val root = itemView as LinearLayout
        private val title = AppCompatTextView(context)
        private val toggle = AppCompatTextView(context)

        init {
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            val pad = Shapes.dpInt(context, 8f)
            root.setPadding(0, pad * 2, 0, pad)
            title.setTextAppearance(context, R.style.TextAppearance_Morsecode_SectionHeader)
            root.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            toggle.setTextAppearance(context, R.style.TextAppearance_Morsecode_Button)
            toggle.minHeight = Shapes.dpInt(context, 48f)
            toggle.gravity = Gravity.CENTER
            root.addView(toggle)
        }

        fun bind(group: DayGroups.Group) {
            val context = root.context
            title.text = group.header
            val entries = group.items.map { Selection.of(it) }
            val allSelected = selection.isGroupSelected(entries)
            toggle.text = context.getString(
                if (allSelected) R.string.files_group_selected else R.string.files_select_all,
            )
            toggle.setTextColor(
                if (allSelected) {
                    androidx.core.content.ContextCompat.getColor(context, R.color.state_success)
                } else {
                    ThemeColors.accent(context)
                },
            )
            toggle.setOnClickListener {
                if (allSelected) selection.deselectAll(entries) else selection.selectAll(entries)
                onSelectionChanged()
                notifyDataSetChanged()
            }
        }
    }

    private inner class TileHolder(context: Context) :
        RecyclerView.ViewHolder(FrameLayout(context)) {

        private val root = itemView as FrameLayout
        // §15.1: the thumbnail is decorative (the tile carries the name); the
        // check is a real control and is labelled in bind().
        private val image = AppCompatImageView(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val check = AppCompatImageView(context).apply {
            contentDescription = context.getString(R.string.cd_select_item, "")
        }
        private val duration = AppCompatTextView(context)

        /** §20.2: the token this holder is currently bound to. */
        private var bindToken: String? = null

        init {
            val size = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            root.addView(image, size)

            check.setImageResource(R.drawable.ic_check)
            val checkSize = Shapes.dpInt(context, 24f)
            val checkParams = FrameLayout.LayoutParams(checkSize, checkSize)
            // §6.9: top-LEFT on video tiles so it never covers the duration badge.
            checkParams.gravity = Gravity.TOP or Gravity.START
            val margin = Shapes.dpInt(context, 6f)
            checkParams.setMargins(margin, margin, margin, margin)
            root.addView(check, checkParams)

            duration.setTextAppearance(context, R.style.TextAppearance_Morsecode_StateChip)
            val durationParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            durationParams.gravity = Gravity.BOTTOM or Gravity.END
            durationParams.setMargins(margin, margin, margin, margin)
            root.addView(duration, durationParams)
        }

        fun bind(item: MediaItem, siblings: List<MediaItem>) {
            val context = root.context
            val entry = Selection.of(item)
            val token = "${item.id}:${item.dateMillis}"
            bindToken = token

            image.setImageDrawable(null)
            image.setBackgroundColor(ThemeColors.resolve(context, R.attr.colorSurfaceRaised))
            AppServices.thumbnails.cached(item)?.let { image.setImageBitmap(it) } ?: scope.launch {
                val bitmap: Bitmap? = AppServices.thumbnails.load(item)
                // §20.2: re-check the token before applying.
                if (bitmap != null && bindToken == token) image.setImageBitmap(bitmap)
            }

            val selected = selection.contains(entry.key)
            check.background = Shapes.circle(
                if (selected) {
                    androidx.core.content.ContextCompat.getColor(context, R.color.state_success)
                } else {
                    ThemeColors.withAlpha(ThemeColors.resolve(context, R.attr.colorBgBase), 0.5f)
                },
            )
            check.alpha = if (selected) 1f else 0.7f
            check.contentDescription = context.getString(R.string.cd_select_item, item.name)
            check.setOnClickListener {
                selection.toggle(entry)
                onSelectionChanged()
                notifyItemChanged(bindingAdapterPosition)
            }

            duration.visibility = if (item.type == FileType.VIDEO && item.durationMillis > 0) {
                duration.text = Fmt.duration(item.durationMillis)
                View.VISIBLE
            } else {
                View.GONE
            }

            root.contentDescription = item.name
            root.setOnClickListener {
                // §6.9: body tap opens while nothing is selected, and toggles
                // once something is — standard gallery behaviour.
                if (selection.isActive) {
                    selection.toggle(entry)
                    onSelectionChanged()
                    notifyItemChanged(bindingAdapterPosition)
                } else {
                    onOpen(item, siblings)
                }
            }
            root.setOnLongClickListener {
                selection.toggle(entry)
                onSelectionChanged()
                notifyItemChanged(bindingAdapterPosition)
                true
            }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_TILE = 1
    }
}

/** Music, Apps and category listings: a leading tile, a title and a mono meta. */
class MediaListAdapter(
    private val selection: Selection,
    private val onOpen: (MediaItem) -> Unit,
    private val onSelectionChanged: () -> Unit,
) : RecyclerView.Adapter<MediaListAdapter.RowHolder>() {

    private var items: List<MediaItem> = emptyList()

    fun submit(next: List<MediaItem>) {
        items = next
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = RowHolder(parent.context)

    override fun onBindViewHolder(holder: RowHolder, position: Int) = holder.bind(items[position])

    inner class RowHolder(context: Context) : RecyclerView.ViewHolder(LinearLayout(context)) {

        private val root = itemView as LinearLayout
        private val tile = TypeTileView(context)
        private val title = AppCompatTextView(context)
        private val meta = AppCompatTextView(context)
        private val check = AppCompatImageView(context).apply {
            contentDescription = context.getString(R.string.cd_select_item, "")
        }

        init {
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.minimumHeight = Shapes.dpInt(context, 56f)
            val pad = Shapes.dpInt(context, 8f)
            root.setPadding(0, pad, 0, pad)

            val checkSize = Shapes.dpInt(context, 48f)
            check.setImageResource(R.drawable.ic_check)
            val checkPad = Shapes.dpInt(context, 12f)
            check.setPadding(checkPad, checkPad, checkPad, checkPad)
            root.addView(check, LinearLayout.LayoutParams(checkSize, checkSize))

            val tileSize = Shapes.dpInt(context, 40f)
            root.addView(tile, LinearLayout.LayoutParams(tileSize, tileSize))

            val column = LinearLayout(context)
            column.orientation = LinearLayout.VERTICAL
            title.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemTitle)
            title.maxLines = 1
            title.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            column.addView(title)
            meta.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
            column.addView(meta)
            val columnParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            columnParams.marginStart = Shapes.dpInt(context, 12f)
            root.addView(column, columnParams)
        }

        fun bind(item: MediaItem) {
            val context = root.context
            val entry = Selection.of(item)
            title.text = item.name
            meta.text = metaFor(item, context)
            tile.bind(kindOf(item))

            val selected = selection.contains(entry.key)
            check.alpha = if (selected) 1f else 0.35f
            check.contentDescription = context.getString(R.string.cd_select_item, item.name)
            check.setOnClickListener {
                selection.toggle(entry)
                onSelectionChanged()
                notifyItemChanged(bindingAdapterPosition)
            }

            root.setOnClickListener {
                if (selection.isActive) {
                    selection.toggle(entry)
                    onSelectionChanged()
                    notifyItemChanged(bindingAdapterPosition)
                } else {
                    onOpen(item)
                }
            }
            root.setOnLongClickListener {
                selection.toggle(entry)
                onSelectionChanged()
                notifyItemChanged(bindingAdapterPosition)
                true
            }
            root.contentDescription = "${item.name} ${meta.text}"
        }

        private fun metaFor(item: MediaItem, context: Context): String = when (item.type) {
            // §6.9: a missing artist renders "Unknown artist", never <unknown>.
            FileType.AUDIO -> listOf(
                item.artist ?: context.getString(R.string.files_unknown_artist),
                Fmt.duration(item.durationMillis),
                Fmt.size(item.sizeBytes),
            ).joinToString(" · ")
            FileType.APK -> "${item.packageName.orEmpty()} · ${Fmt.size(item.sizeBytes)}"
            else -> Fmt.size(item.sizeBytes)
        }

        private fun kindOf(item: MediaItem): FileKind = when (item.type) {
            FileType.IMAGE -> FileKind.IMAGE
            FileType.VIDEO -> FileKind.VIDEO
            FileType.AUDIO -> FileKind.AUDIO
            FileType.DOCUMENT -> FileKind.DOC
            FileType.ARCHIVE -> FileKind.ARCHIVE
            FileType.APK -> FileKind.APK
            else -> FileKind.UNKNOWN
        }
    }
}

/**
 * §6.9's directory rows. FOLDERS ARE FIRST-CLASS: every row has the same check
 * target, a folder shows "size · items · modified" and a selected folder is
 * sendable as one item (A25).
 */
class DirectoryAdapter(
    private val selection: Selection,
    private val onOpenFolder: (DirectoryEntry) -> Unit,
    private val onOpenFile: (DirectoryEntry) -> Unit,
    private val onSelectionChanged: () -> Unit,
) : RecyclerView.Adapter<DirectoryAdapter.EntryHolder>() {

    private var entries: List<DirectoryEntry> = emptyList()

    fun submit(next: List<DirectoryEntry>) {
        entries = next
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = entries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = EntryHolder(parent.context)

    override fun onBindViewHolder(holder: EntryHolder, position: Int) = holder.bind(entries[position])

    inner class EntryHolder(context: Context) : RecyclerView.ViewHolder(LinearLayout(context)) {

        private val root = itemView as LinearLayout
        private val check = AppCompatImageView(context).apply {
            contentDescription = context.getString(R.string.cd_select_item, "")
        }
        // The type glyph repeats what the name already says (§15.1).
        private val icon = AppCompatImageView(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val title = AppCompatTextView(context)
        private val meta = AppCompatTextView(context)

        init {
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.minimumHeight = Shapes.dpInt(context, 56f)

            val touch = Shapes.dpInt(context, 48f)
            check.setImageResource(R.drawable.ic_check)
            val pad = Shapes.dpInt(context, 12f)
            check.setPadding(pad, pad, pad, pad)
            root.addView(check, LinearLayout.LayoutParams(touch, touch))

            val iconSize = Shapes.dpInt(context, 24f)
            root.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))

            val column = LinearLayout(context)
            column.orientation = LinearLayout.VERTICAL
            title.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemTitle)
            title.maxLines = 1
            title.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            column.addView(title)
            meta.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
            column.addView(meta)
            val columnParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            columnParams.marginStart = Shapes.dpInt(context, 12f)
            root.addView(column, columnParams)
        }

        fun bind(entry: DirectoryEntry) {
            val context = root.context
            val selectionEntry = Selection.of(entry)
            title.text = entry.name
            icon.setImageResource(if (entry.isDirectory) R.drawable.ic_folder else iconFor(entry.type))
            meta.text = if (entry.isDirectory) {
                // §6.9: never a dash in the size column.
                "${Fmt.size(entry.sizeBytes)} · ${entry.childCount} items"
            } else {
                Fmt.size(entry.sizeBytes)
            }

            val selected = selection.contains(selectionEntry.key)
            check.alpha = if (selected) 1f else 0.35f
            check.contentDescription = context.getString(R.string.cd_select_item, entry.name)
            check.setOnClickListener {
                selection.toggle(selectionEntry)
                onSelectionChanged()
                notifyItemChanged(bindingAdapterPosition)
            }

            root.setOnClickListener {
                if (selection.isActive) {
                    selection.toggle(selectionEntry)
                    onSelectionChanged()
                    notifyItemChanged(bindingAdapterPosition)
                } else if (entry.isDirectory) {
                    onOpenFolder(entry)
                } else {
                    onOpenFile(entry)
                }
            }
            root.setOnLongClickListener {
                selection.toggle(selectionEntry)
                onSelectionChanged()
                notifyItemChanged(bindingAdapterPosition)
                true
            }
            root.contentDescription = "${entry.name} ${meta.text}"
        }

        private fun iconFor(type: FileType): Int = when (type) {
            FileType.IMAGE -> R.drawable.ic_type_image
            FileType.VIDEO -> R.drawable.ic_type_video
            FileType.AUDIO -> R.drawable.ic_type_audio
            FileType.DOCUMENT -> R.drawable.ic_type_doc
            FileType.ARCHIVE -> R.drawable.ic_type_archive
            FileType.APK -> R.drawable.ic_type_apk
            else -> R.drawable.ic_type_unknown
        }
    }
}
