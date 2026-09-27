package app.morsecode.android.feature.settings

import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.morsecode.android.BuildConfig
import app.morsecode.android.R
import app.morsecode.android.core.logging.LogStore
import app.morsecode.android.core.ui.Buttons
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.Ui
import app.morsecode.android.core.util.Ids
import app.morsecode.android.di.AppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * §6.14 LOG VIEWER.
 *
 * "[Export .txt] writes the file to cache and then fires the SYSTEM SHARE
 * SHEET (ACTION_SEND, text/plain, FileProvider URI, subject 'Morsecode 1.0.0
 * (1)') so the log can go to Gmail, Drive, WhatsApp, Telegram, Bluetooth,
 * Files or anything else the user has installed [CHANGED]. Writing the file
 * silently — or showing only a toast — is a defect: a log nobody can send is
 * useless." That is A26.
 *
 * Rows are monospace with the level in its own colour AND its own text
 * (§4.13), searchable, and the list follows the tail unless the user has
 * scrolled up.
 */
class LogViewerFragment : Screen() {

    override val scrollable = false

    private lateinit var list: RecyclerView
    private lateinit var search: AppCompatEditText
    private val adapter = LogAdapter()

    private var errorsOnly = false
    private var query = ""

    override fun onBuildScreen(column: LinearLayout) {
        val context = requireContext()

        toolbar.bind(getString(R.string.title_logs)) { nav().pop() }
        toolbar.addAction(R.drawable.ic_trash, R.string.cd_clear_all) { clearLog() }

        val chips = LinearLayout(context)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.addView(
            Buttons.accent(context, getString(R.string.logs_export)) { exportAndShare() },
            cell(context, first = true),
        )
        chips.addView(
            Buttons.outlined(context, getString(R.string.logs_errors_only)) {
                errorsOnly = !errorsOnly
                render()
            },
            cell(context, first = false),
        )
        chips.addView(
            Buttons.outlined(context, getString(R.string.logs_clear)) { clearLog() },
            cell(context, first = false),
        )
        column.addView(chips, wide(8))

        search = AppCompatEditText(context)
        search.hint = getString(R.string.logs_search_hint)
        search.setSingleLine()
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(editable: android.text.Editable?) {
                query = editable?.toString().orEmpty()
                render()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        column.addView(search, wide(8))

        list = RecyclerView(context)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        column.addView(
            list,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        render()
    }

    override fun onStart() {
        super.onStart()
        render()
    }

    private fun render() {
        val lines = AppServices.logStore.snapshot()
            .filter { !errorsOnly || it.level == LogStore.Level.ERROR }
            .filter { query.isEmpty() || it.message.contains(query, ignoreCase = true) }
        adapter.submit(lines)
        if (lines.isNotEmpty() && !hasScrolledUp()) {
            // Auto-scrolls to the tail unless the user has scrolled up (§6.14).
            list.scrollToPosition(lines.size - 1)
        }
    }

    private fun hasScrolledUp(): Boolean {
        val manager = list.layoutManager as? LinearLayoutManager ?: return false
        val last = manager.findLastVisibleItemPosition()
        return last != RecyclerView.NO_POSITION && last < adapter.itemCount - 2
    }

    private fun clearLog() {
        AppServices.logStore.clear()
        render()
    }

    /**
     * A26: write the .txt, then raise the SYSTEM chooser. The file's first line
     * is "Morsecode <version> (<versionCode>)", which is also A15's half of
     * the same criterion.
     */
    private fun exportAndShare() {
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val exports = File(context.cacheDir, "exports").apply { mkdirs() }
                val target = File(exports, "morsecode-log.txt")
                target.writeText(
                    AppServices.logStore.exportText(
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                    ),
                )
                target
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.putExtra(
                Intent.EXTRA_SUBJECT,
                Ids.logHeader(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            )
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // The real chooser, with the user's own apps — never a toast.
            startActivity(Intent.createChooser(intent, getString(R.string.logs_export)))
        }
    }

    private inner class LogAdapter : RecyclerView.Adapter<LogHolder>() {

        private var lines: List<LogStore.Line> = emptyList()

        fun submit(next: List<LogStore.Line>) {
            lines = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = lines.size

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
            LogHolder(parent.context)

        override fun onBindViewHolder(holder: LogHolder, position: Int) = holder.bind(lines[position])
    }

    private inner class LogHolder(context: android.content.Context) :
        RecyclerView.ViewHolder(LinearLayout(context)) {

        private val root = itemView as LinearLayout
        private val time = AppCompatTextView(context)
        private val level = AppCompatTextView(context)
        private val message = AppCompatTextView(context)

        init {
            root.orientation = LinearLayout.HORIZONTAL
            val pad = Shapes.dpInt(context, 4f)
            root.setPadding(0, pad, 0, pad)
            for (view in listOf(time, level, message)) {
                view.setTextAppearance(context, R.style.TextAppearance_Morsecode_LogLine)
            }
            root.addView(time)
            val levelParams = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            levelParams.marginStart = Shapes.dpInt(context, 8f)
            levelParams.marginEnd = levelParams.marginStart
            root.addView(level, levelParams)
            message.maxLines = MAX_LINES
            root.addView(
                message,
                LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }

        fun bind(line: LogStore.Line) {
            val context = root.context
            time.text = line.clockTime()
            // The level is text as well as colour (§4.13).
            level.text = line.level.name
            level.setTextColor(
                ContextCompat.getColor(
                    context,
                    when (line.level) {
                        LogStore.Level.INFO -> R.color.state_info
                        LogStore.Level.WARN -> R.color.state_warning
                        LogStore.Level.ERROR -> R.color.state_error
                    },
                ),
            )
            message.text = line.message
            root.contentDescription = line.render()
        }
    }

    private fun cell(context: android.content.Context, first: Boolean): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        if (!first) params.marginStart = Shapes.dpInt(context, 8f)
        return params
    }

    private fun wide(topMarginDp: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = Shapes.dpInt(requireContext(), topMarginDp.toFloat())
        return params
    }

    private companion object {
        const val MAX_LINES = 4
    }
}
