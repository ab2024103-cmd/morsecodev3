package app.morsecode.android.feature.viewer

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import app.morsecode.android.R
import app.morsecode.android.core.media.PlaybackService
import app.morsecode.android.core.model.MediaItem
import app.morsecode.android.core.ui.BottomSheet
import app.morsecode.android.core.ui.FileKind
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.TypeTileView
import app.morsecode.android.core.util.ThemeColors
import app.morsecode.android.di.AppServices

/**
 * §6.11's actual Now-Playing Queue sheet. It deliberately renders the same
 * process-scoped [PlaybackQueue] as the player: choosing a row starts that
 * track immediately and removing an item changes the service's next track,
 * rather than being a decorative copy of "UP NEXT".
 */
class NowPlayingQueueSheet : BottomSheet() {

    override fun onCreateSheetContent(context: Context): View {
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        val state = AppServices.playbackQueue.state.value

        val title = AppCompatTextView(context)
        title.setTextAppearance(context, R.style.TextAppearance_Morsecode_ScreenTitle)
        title.text = getString(R.string.player_queue_title, state.tracks.size)
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (state.tracks.isEmpty()) {
            val empty = AppCompatTextView(context)
            empty.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
            empty.text = getString(R.string.player_queue_empty)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.topMargin = Shapes.dpInt(context, 12f)
            root.addView(empty, params)
            return root
        }

        for ((index, track) in state.tracks.withIndex()) {
            root.addView(queueRow(context, track, index, index == state.index))
        }
        return root
    }

    private fun queueRow(context: Context, track: MediaItem, index: Int, current: Boolean): View {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = Shapes.dpInt(context, 56f)
        row.isClickable = true
        row.isFocusable = true
        row.setPadding(0, Shapes.dpInt(context, 4f), 0, Shapes.dpInt(context, 4f))
        row.background = Shapes.pressable(
            context,
            Shapes.rounded(android.graphics.Color.TRANSPARENT, Shapes.dp(context, 12f)),
            Shapes.rounded(ThemeColors.resolve(context, R.attr.colorSurfacePressed), Shapes.dp(context, 12f)),
        )
        row.setOnClickListener {
            AppServices.playbackQueue.moveTo(index)
            PlaybackService.start(context)
            dismiss()
        }

        val tile = TypeTileView(context)
        tile.bind(FileKind.AUDIO)
        val tileSize = Shapes.dpInt(context, 40f)
        row.addView(tile, LinearLayout.LayoutParams(tileSize, tileSize))

        val labels = LinearLayout(context)
        labels.orientation = LinearLayout.VERTICAL
        val name = AppCompatTextView(context)
        name.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemTitle)
        name.text = track.name
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        if (current) name.setTextColor(ThemeColors.accent(context))
        labels.addView(name)
        val meta = AppCompatTextView(context)
        meta.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
        meta.text = if (current) getString(R.string.player_queue_playing) else track.artist ?: getString(R.string.files_unknown_artist)
        labels.addView(meta)
        val textParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        textParams.marginStart = Shapes.dpInt(context, 12f)
        row.addView(labels, textParams)

        val remove = AppCompatImageView(context)
        remove.setImageResource(R.drawable.ic_close)
        remove.contentDescription = getString(R.string.player_queue_remove, track.name)
        val pad = Shapes.dpInt(context, 12f)
        remove.setPadding(pad, pad, pad, pad)
        remove.setOnClickListener {
            AppServices.playbackQueue.remove(index)
            // The source queue changed immediately; close rather than show a
            // stale snapshot, and the Queue button reopens its new contents.
            dismiss()
        }
        val buttonSize = Shapes.dpInt(context, 48f)
        row.addView(remove, LinearLayout.LayoutParams(buttonSize, buttonSize))
        row.contentDescription = track.name
        return row
    }

    private companion object {
        const val TAG = "now-playing-queue"
    }
}
