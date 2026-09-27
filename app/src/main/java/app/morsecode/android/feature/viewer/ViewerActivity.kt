package app.morsecode.android.feature.viewer

import android.content.Intent
import android.graphics.Bitmap
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.lifecycle.lifecycleScope
import app.morsecode.android.R
import app.morsecode.android.core.media.ViewerSession
import app.morsecode.android.core.model.MediaItem
import app.morsecode.android.core.model.SessionState
import app.morsecode.android.core.model.TransferFile
import app.morsecode.android.core.ui.DeckPhysics
import app.morsecode.android.core.ui.PhotoDeckView
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.Ui
import app.morsecode.android.core.util.Fmt
import app.morsecode.android.core.util.ThemeColors
import app.morsecode.android.di.AppServices
import kotlinx.coroutines.launch

/**
 * §6.10 VIEWER — full-screen image, immersive (§5.2 [CHANGED]).
 *
 * THE IMAGE FILLS THE VIEWER: the deck occupies every pixel between the top
 * bar and the action row. NAVIGATION IS A SWIPE, not buttons — there is no
 * ‹ › chrome on the phone, though a hardware keyboard keeps ← →.
 *
 * THE SEND FAB ACTUALLY SENDS: it queues this photo on the active session and
 * opens the transfer screen, exactly like [Send] in the picker (A33). A FAB
 * that only raised a toast would be a defect.
 */
class ViewerActivity : ImmersiveActivity() {

    private lateinit var deck: PhotoDeckView
    private lateinit var meta: AppCompatTextView
    private lateinit var dots: LinearLayout
    private val bitmaps = HashMap<Int, Bitmap>()

    override fun titleText(): CharSequence =
        ViewerSession.itemAt(ViewerSession.startIndex)?.name ?: getString(R.string.title_viewer)

    override fun onBuildImmersive() {
        val items = ViewerSession.list()
        if (items.isEmpty()) {
            finish()
            return
        }

        deck = PhotoDeckView(this, AppServices.deviceTier)
        deck.count = items.size
        deck.bitmapProvider = { position -> bitmaps[position] }
        deck.onIndexChanged = { index ->
            renderMeta(index)
            loadAround(index)
        }
        stage.addView(
            deck,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        meta = AppCompatTextView(this)
        meta.setTextAppearance(this, R.style.TextAppearance_Morsecode_ItemMeta)
        meta.setTextColor(ThemeColors.resolve(this, R.attr.colorViewerChrome))
        meta.gravity = Gravity.CENTER
        val metaParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        metaParams.gravity = Gravity.TOP
        stage.addView(meta, metaParams)

        dots = LinearLayout(this)
        dots.orientation = LinearLayout.HORIZONTAL
        dots.gravity = Gravity.CENTER
        val dotParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        dotParams.gravity = Gravity.BOTTOM
        dotParams.bottomMargin = Shapes.dpInt(this, 12f)
        stage.addView(dots, dotParams)

        buildActions()
        deck.show(ViewerSession.startIndex)
        loadAround(ViewerSession.startIndex)
    }

    /** §6.10's bottom row: four neutral circles and one accent SEND FAB. */
    private fun buildActions() {
        actionRow.addView(circleAction(R.drawable.ic_send, R.string.viewer_action_share) { share() })
        // "Edit" launches ACTION_EDIT and is HIDDEN when no handler exists.
        if (hasEditHandler()) {
            actionRow.addView(circleAction(R.drawable.ic_choose, R.string.viewer_action_edit) { edit() })
        }
        actionRow.addView(circleAction(R.drawable.ic_trash, R.string.viewer_action_delete) { delete() })
        actionRow.addView(circleAction(R.drawable.ic_info, R.string.viewer_action_info) { info() })
        actionRow.addView(
            circleAction(R.drawable.ic_send, R.string.viewer_action_send, accent = true) { sendCurrent() },
        )
    }

    private fun circleAction(
        iconRes: Int,
        labelRes: Int,
        accent: Boolean = false,
        onClick: () -> Unit,
    ): View {
        val button = AppCompatImageView(this)
        button.setImageResource(iconRes)
        button.contentDescription = getString(labelRes)
        val pad = Shapes.dpInt(this, 14f)
        button.setPadding(pad, pad, pad, pad)
        button.background = Shapes.circle(
            if (accent) {
                ThemeColors.accent(this)
            } else {
                ThemeColors.resolve(this, R.attr.colorViewerChromeScrim)
            },
        )
        androidx.core.widget.ImageViewCompat.setImageTintList(
            button,
            android.content.res.ColorStateList.valueOf(
                if (accent) {
                    ThemeColors.resolve(this, R.attr.colorInkOnAccent)
                } else {
                    ThemeColors.resolve(this, R.attr.colorViewerChrome)
                },
            ),
        )
        button.setOnClickListener { onClick() }
        val size = Shapes.dpInt(this, 48f)
        val params = LinearLayout.LayoutParams(size, size)
        params.marginEnd = Shapes.dpInt(this, 12f)
        button.layoutParams = params
        return button
    }

    private fun currentItem(): MediaItem? = ViewerSession.itemAt(deck.index)

    private fun renderMeta(index: Int) {
        val item = ViewerSession.itemAt(index) ?: return
        // "5 of 15 · 4032 × 3024 · 2.4 MB" — the count and size are real; the
        // pixel dimensions arrive with the decoded bitmap.
        val bitmap = bitmaps[index]
        val dimensions = bitmap?.let { "${it.width} × ${it.height}" }
        meta.text = listOfNotNull(
            DeckPhysics.positionLabel(index, ViewerSession.count),
            dimensions,
            Fmt.size(item.sizeBytes),
        ).joinToString(" · ")

        dots.removeAllViews()
        val shown = minOf(ViewerSession.count, MAX_DOTS)
        for (dot in 0 until shown) {
            val view = View(this)
            val active = dot == index % shown
            val height = Shapes.dpInt(this, 6f)
            val params = LinearLayout.LayoutParams(
                if (active) Shapes.dpInt(this, 18f) else height,
                height,
            )
            params.marginEnd = Shapes.dpInt(this, 6f)
            view.layoutParams = params
            view.background = Shapes.pill(
                this,
                if (active) {
                    ThemeColors.accent(this)
                } else {
                    ThemeColors.resolve(this, R.attr.colorViewerChromeScrim)
                },
            )
            dots.addView(view)
        }
    }

    /** Three slides live at a time, so three bitmaps are what is loaded. */
    private fun loadAround(index: Int) {
        val count = ViewerSession.count
        for (position in listOf(
            index,
            DeckPhysics.previousIndex(index, count),
            DeckPhysics.nextIndex(index, count),
        )) {
            if (bitmaps.containsKey(position)) continue
            val item = ViewerSession.itemAt(position) ?: continue
            lifecycleScope.launch {
                val bitmap = AppServices.thumbnails.load(item) ?: return@launch
                bitmaps[position] = bitmap
                deck.refresh()
                if (position == deck.index) renderMeta(position)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // §6.10: a desktop has no thumb, so ← → stay.
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                deck.goNext()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                deck.goPrevious()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ----- Actions ----------------------------------------------------------

    private fun sendCurrent() {
        val item = currentItem() ?: return
        val file = TransferFile(item.name, item.uri, item.mimeType, item.sizeBytes)
        val peerName = (AppServices.transferEngine.session.value as? SessionState.Connected)?.peerName
        if (peerName == null) {
            // With no session it opens Discovery first, exactly like the picker.
            AppServices.transferEngine.holdShare(listOf(file))
            startActivity(Intent(this, app.morsecode.android.MainActivity::class.java))
            finish()
            return
        }
        AppServices.transferEngine.enqueue(listOf(file))
        Ui.snackbar(
            this,
            getString(R.string.viewer_queued, item.name, Fmt.size(item.sizeBytes), peerName),
        )
        finish()
    }

    private fun share() {
        val item = currentItem() ?: return
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = item.mimeType ?: "image/*"
        intent.putExtra(Intent.EXTRA_STREAM, item.uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.viewer_action_share)))
    }

    private fun edit() {
        val item = currentItem() ?: return
        val intent = Intent(Intent.ACTION_EDIT)
        intent.setDataAndType(item.uri, item.mimeType ?: "image/*")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching { startActivity(intent) }
    }

    private fun hasEditHandler(): Boolean {
        val item = ViewerSession.itemAt(ViewerSession.startIndex) ?: return false
        val intent = Intent(Intent.ACTION_EDIT)
        intent.setDataAndType(item.uri, item.mimeType ?: "image/*")
        return intent.resolveActivity(packageManager) != null
    }

    private fun delete() {
        val item = currentItem() ?: return
        // Deleting goes through the same resolver the library reads, so the
        // grid reflects it without a private cache to invalidate.
        runCatching { contentResolver.delete(item.uri, null, null) }
        finish()
    }

    private fun info() {
        val item = currentItem() ?: return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage(
                listOfNotNull(
                    Fmt.size(item.sizeBytes),
                    item.mimeType,
                    item.path,
                ).joinToString("\n"),
            )
            .setPositiveButton(R.string.action_done, null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        bitmaps.clear()
    }

    private companion object {
        const val MAX_DOTS = 12
    }
}
