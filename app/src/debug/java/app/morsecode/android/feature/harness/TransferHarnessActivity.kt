package app.morsecode.android.feature.harness

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.lifecycle.lifecycleScope
import app.morsecode.android.R
import app.morsecode.android.core.model.SendResult
import app.morsecode.android.core.model.TransferFile
import app.morsecode.android.core.model.TransferItem
import app.morsecode.android.core.model.TransferState
import app.morsecode.android.core.network.TransportKind
import app.morsecode.android.core.network.TransportSession
import app.morsecode.android.core.transfer.TransferEngine
import app.morsecode.android.core.ui.Buttons
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.Themes
import app.morsecode.android.core.util.Fmt
import app.morsecode.android.core.util.ThemeColors
import app.morsecode.android.di.AppServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug-only harness for the Stage 5 engine (headless stage: "drive it from
 * tests and a debug harness").
 *
 * It runs a loopback session that moves bytes at a fixed rate with no network,
 * so the §9 semantics — pause one file while the others continue (INV-2),
 * drop the session and watch everything become PAUSED then resume (INV-3),
 * cancel a file whose transport never answers (INV-1b) — can be exercised by
 * hand on a device. It is not part of §5.1's information architecture and does
 * not exist in the release build.
 */
class TransferHarnessActivity : AppCompatActivity() {

    private lateinit var output: AppCompatTextView
    private lateinit var engine: TransferEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.applyAccent(this, AppServices.prefs)
        super.onCreate(savedInstanceState)

        engine = TransferEngine(scope = lifecycleScope, logStore = AppServices.logStore)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(ThemeColors.resolve(this, R.attr.colorBgBase))
        val pad = Shapes.dpInt(this, 16f)
        root.setPadding(pad, pad, pad, pad)

        root.addView(button("Connect loopback session") {
            engine.onSessionConnected(LoopbackSession())
        })
        root.addView(button("Queue 3 files (1 MB, 8 MB, 2 MB)") {
            engine.enqueue(
                listOf(
                    file("holiday.jpg", 1_000_000),
                    file("clip.mp4", 8_000_000),
                    file("notes.pdf", 2_000_000),
                ),
            )
        })
        root.addView(button("Pause the running file") {
            engine.items.value.firstOrNull { it.state == TransferState.IN_PROGRESS }
                ?.let { engine.pause(it.id) }
        })
        root.addView(button("Resume everything paused") {
            engine.items.value.filter { it.state == TransferState.PAUSED }
                .forEach { engine.resume(it.id) }
        })
        root.addView(button("Cancel the running file") {
            engine.items.value.firstOrNull { it.state == TransferState.IN_PROGRESS }
                ?.let { engine.cancel(it.id) }
        })
        root.addView(button("Drop the session (INV-3)") {
            engine.onSessionLost("Connection lost")
        })

        output = AppCompatTextView(this)
        output.setTextAppearance(this, R.style.TextAppearance_Morsecode_LogLine)
        output.setTextColor(ThemeColors.resolve(this, R.attr.colorTextPrimary))
        val scroller = ScrollView(this)
        scroller.addView(output)
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        lifecycleScope.launch {
            engine.items.collect { items -> output.text = render(items) }
        }
    }

    private fun render(items: List<TransferItem>): CharSequence = buildString {
        appendLine("session: ${engine.session.value}")
        appendLine()
        for (item in items) {
            appendLine(item.file.displayName)
            appendLine(
                "  ${item.state}  ${Fmt.progress(item.bytesTransferred, item.totalBytes)}" +
                    "  ${Fmt.speed(item.speedBps)}",
            )
            if (item.lastError != null) appendLine("  ! ${item.lastError}")
        }
    }

    private fun button(label: String, onClick: () -> Unit) =
        Buttons.outlined(this, label, onClick).apply {
            gravity = Gravity.CENTER
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.bottomMargin = Shapes.dpInt(this@TransferHarnessActivity, 8f)
            layoutParams = params
        }

    private fun file(name: String, size: Long) = TransferFile(
        displayName = name,
        uri = Uri.parse("harness://$name"),
        mime = null,
        size = size,
    )

    /**
     * A transport that moves bytes without a network: 2 MB/s, reported in
     * 256 KB steps, exactly like the LAN chunk size. Pause and cancel are
     * deliberately NOT acknowledged, so the harness exercises INV-1(b)'s
     * 3-second terminal fallback rather than the happy path.
     */
    private class LoopbackSession : TransportSession {

        override val peerId = "loopback"
        override val peerName = "Loopback"
        override val transport = TransportKind.LAN
        override var isAlive = true

        override suspend fun sendFile(
            item: TransferItem,
            sha256: String?,
            onProgress: (Long, Long) -> Unit,
        ): SendResult {
            var sent = item.resumeOffset
            val step = 256L * 1024
            while (sent < item.totalBytes) {
                delay(128)
                sent = (sent + step).coerceAtMost(item.totalBytes)
                onProgress(sent, 2_000_000)
            }
            return SendResult.Completed
        }

        override suspend fun pauseOutgoing(fileId: String) = Unit

        override suspend fun cancelTransfer(fileId: String) = Unit

        override suspend fun close(reason: String) {
            isAlive = false
        }
    }
}
