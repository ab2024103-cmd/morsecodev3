package app.morsecode.android.feature.gallery

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import app.morsecode.android.R
import app.morsecode.android.core.data.Prefs
import app.morsecode.android.core.ui.ActionBarView
import app.morsecode.android.core.ui.BottomNavView
import app.morsecode.android.core.ui.ChipState
import app.morsecode.android.core.ui.ConsentDialog
import app.morsecode.android.core.ui.EmptyStateView
import app.morsecode.android.core.ui.FileKind
import app.morsecode.android.core.ui.PeerCardView
import app.morsecode.android.core.ui.RadarView
import app.morsecode.android.core.ui.SectionHeaderView
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.StateChipView
import app.morsecode.android.core.ui.SummaryCardView
import app.morsecode.android.core.ui.ThinProgressBar
import app.morsecode.android.core.ui.Themes
import app.morsecode.android.core.ui.TipView
import app.morsecode.android.core.ui.TransferRowView
import app.morsecode.android.core.ui.TypeTileView
import app.morsecode.android.core.ui.Ui
import app.morsecode.android.core.util.Accent
import app.morsecode.android.core.util.Fmt
import app.morsecode.android.core.util.ThemeColors
import app.morsecode.android.di.AppServices

/**
 * DEBUG-ONLY component gallery (build plan, Stage 2).
 *
 * Renders every §4.12 component in dark and light, in all five accents, with
 * the content column pinned to 320 dp, 360 dp or 600 dp so the §4.14 responsive
 * rules can be eyeballed on one device. It ships in the debug variant only and
 * is not part of the product's information architecture (§5.1).
 *
 * The demo device ids below are calibrated so the four documented mock avatars
 * reproduce exactly through §4.6's formula: MYA-L10 amber, Ravi's Redmi violet,
 * Pixel 7X sky, Samsung A14 green.
 */
class ComponentGalleryActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var column: LinearLayout
    private var widthDp = 360

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = AppServices.prefs
        Themes.applyAccent(this, prefs)
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(ThemeColors.resolve(this, R.attr.colorBgBase))

        root.addView(controls())

        val scroll = ScrollView(this)
        val centering = FrameLayout(this)
        column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        val pad = Shapes.dpInt(this, 16f)
        column.setPadding(pad, pad, pad, pad)
        val columnParams = FrameLayout.LayoutParams(
            Shapes.dpInt(this, widthDp.toFloat()),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        columnParams.gravity = Gravity.CENTER_HORIZONTAL
        centering.addView(column, columnParams)
        scroll.addView(centering)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        val nav = BottomNavView(this)
        nav.setOnTabSelected { tab -> Ui.snackbar(this, tab.name) }
        root.addView(
            nav,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        setContentView(root)
        fillColumn()
    }

    private fun controls(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        val pad = Shapes.dpInt(this, 8f)
        bar.setPadding(pad, pad, pad, pad)

        bar.addView(button(getString(R.string.gallery_theme)) {
            val next = when (prefs.themeMode.value) {
                Prefs.ThemeMode.DARK -> Prefs.ThemeMode.LIGHT
                Prefs.ThemeMode.LIGHT -> Prefs.ThemeMode.SYSTEM
                Prefs.ThemeMode.SYSTEM -> Prefs.ThemeMode.DARK
            }
            prefs.setThemeMode(next)
            Themes.applyNightMode(prefs)
            recreate()
        })

        for (accent in Accent.values()) {
            val dot = View(this)
            dot.background = Shapes.circle(ContextCompat.getColor(this, accent.colorRes))
            dot.contentDescription = accent.key
            dot.setOnClickListener {
                prefs.setAccent(accent)
                recreate()
            }
            val size = Shapes.dpInt(this, 40f)   // §6.13: five 40 dp circles
            val params = LinearLayout.LayoutParams(size, size)
            params.leftMargin = Shapes.dpInt(this, 6f)
            bar.addView(dot, params)
        }

        for (width in intArrayOf(320, 360, 600)) {
            bar.addView(button(width.toString()) {
                widthDp = width
                val params = column.layoutParams as FrameLayout.LayoutParams
                params.width = Shapes.dpInt(this, width.toFloat())
                column.layoutParams = params
            })
        }
        val scroller = HorizontalScrollView(this)
        scroller.addView(bar)
        return scroller
    }

    private fun button(label: CharSequence, onClick: () -> Unit): View {
        val view = AppCompatTextView(this)
        view.setTextAppearance(this, R.style.TextAppearance_Morsecode_Button)
        view.text = label
        view.contentDescription = label
        view.gravity = Gravity.CENTER
        view.background = Shapes.outlinedButton(this)
        view.minHeight = Shapes.dpInt(this, 48f)
        view.minWidth = Shapes.dpInt(this, 48f)
        val padH = Shapes.dpInt(this, 12f)
        view.setPadding(padH, 0, padH, 0)
        view.setOnClickListener { onClick() }
        return view
    }

    private fun fillColumn() {
        val context: Context = this

        add(header(getString(R.string.gallery_section_peers)))
        val peerCard = PeerCardView(context)
        peerCard.bind("Ravi's Redmi:2", "Ravi\u2019s Redmi", "Connected · Phone · LAN",
            app.morsecode.android.core.ui.TransportBadge.LAN)
        add(peerCard)
        val peerCard2 = PeerCardView(context)
        peerCard2.bind("MYA-L10:1", "MYA-L10", "Broadcast · Phone · LAN",
            app.morsecode.android.core.ui.TransportBadge.FROM)
        add(peerCard2)
        val peerCard3 = PeerCardView(context)
        peerCard3.bind("Pixel 7X:3", "Pixel 7X", "Bluetooth 5.2 · Phone · Nearby",
            app.morsecode.android.core.ui.TransportBadge.NEARBY)
        add(peerCard3)
        val peerCard4 = PeerCardView(context)
        peerCard4.bind("Samsung A14:3", "Samsung A14", "192.168.1.88 · WebShare",
            app.morsecode.android.core.ui.TransportBadge.WEB)
        add(peerCard4)

        add(header(getString(R.string.gallery_section_rows)))
        add(row("holiday_2019.mp4", Fmt.progress(51_275_366L, 150_994_944L) + " · " +
            Fmt.speed(6_501_171L), FileKind.VIDEO, ChipState.SENDING, 0.34f))
        add(row("IMG_2043.jpg", Fmt.size(4_299_161L) + " · waiting", FileKind.IMAGE,
            ChipState.QUEUED, 0f))
        add(row("live_set_final.flac", Fmt.progress(41_628_467L, 67_108_864L) + " · resume " +
            Fmt.size(41_628_467L), FileKind.AUDIO, ChipState.PAUSED, 0.62f))
        add(row("notes_backup.zip", Fmt.progress(13_736_837L, 19_084_083L) + " · " +
            Fmt.speed(8_808_038L), FileKind.ARCHIVE, ChipState.RECEIVING, 0.72f))
        add(row("a_very_long_file_name_that_must_middle_ellipsise_but_keep.pdf",
            Fmt.size(2_516_582L) + " · CRC verified", FileKind.DOC, ChipState.DONE, 1f))
        add(row("broken_upload.apk", Fmt.size(9_437_184L) + " · connection lost",
            FileKind.APK, ChipState.FAILED, 0.4f))

        val sub = TransferRowView(context)
        sub.bindVariant(TransferRowView.Variant.PEER_SUB, "Pixel 7X:3", "Pixel 7X")
        sub.bind("Pixel 7X", Fmt.progress(24_117_248L, 150_994_944L), FileKind.VIDEO,
            ChipState.SENDING, 0.16f)
        add(sub)

        add(header(getString(R.string.gallery_section_summary)))
        val summary = SummaryCardView(context)
        summary.bind(
            getString(R.string.summary_batch_in_progress),
            listOf("1 sending · 1 queued · 1 paused — avg " + Fmt.speed(6_501_171L)),
        )
        add(summary)
        val broadcastSummary = SummaryCardView(context)
        broadcastSummary.bind(
            "⇶ Broadcasting to 3 phones",
            listOf("1 broadcasting · 2 queued — combined throughput " + Fmt.speed(14_889_779L)),
            listOf("3" to "PEERS", "212" to "BATCH MB", "636" to "TO SEND MB"),
        )
        add(broadcastSummary)

        add(header(getString(R.string.gallery_section_chips)))
        val chipRow = LinearLayout(context)
        chipRow.orientation = LinearLayout.HORIZONTAL
        val chipScroll = HorizontalScrollView(context)
        for (state in ChipState.values()) {
            val chip = StateChipView(context)
            chip.bind(state)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.rightMargin = Shapes.dpInt(context, 6f)
            chipRow.addView(chip, params)
        }
        chipScroll.addView(chipRow)
        add(chipScroll)

        val tileRow = LinearLayout(context)
        tileRow.orientation = LinearLayout.HORIZONTAL
        for (kind in FileKind.values()) {
            val tile = TypeTileView(context)
            tile.bind(kind)
            val size = Shapes.dpInt(context, 40f)
            val params = LinearLayout.LayoutParams(size, size)
            params.rightMargin = Shapes.dpInt(context, 6f)
            tileRow.addView(tile, params)
        }
        add(tileRow)

        add(header(getString(R.string.gallery_section_progress)))
        for (mode in ThinProgressBar.Mode.values()) {
            val bar = ThinProgressBar(context)
            bar.setMode(mode)
            bar.setProgress(if (mode == ThinProgressBar.Mode.COMPLETE) 1f else 0.62f, animate = false)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Shapes.dpInt(context, 4f),
            )
            params.bottomMargin = Shapes.dpInt(context, 12f)
            add(bar, params)
        }

        add(header(getString(R.string.gallery_section_radar)))
        val radar = RadarView(context)
        val radarSize = Shapes.dpInt(context, 160f)
        add(radar, LinearLayout.LayoutParams(radarSize, radarSize))

        add(header(getString(R.string.gallery_section_actions)))
        val actionBar = ActionBarView(context)
        actionBar.setOnAction(ActionBarView.Action.CHOOSE) { Ui.snackbar(this, getString(R.string.action_choose)) }
        actionBar.setOnAction(ActionBarView.Action.END) { Ui.snackbar(this, getString(R.string.action_end)) }
        add(actionBar)

        add(header(getString(R.string.gallery_section_states)))
        val empty = EmptyStateView(context)
        empty.bind(R.drawable.ic_empty_box, getString(R.string.gallery_empty_line),
            getString(R.string.gallery_empty_action)) { Ui.snackbar(this, getString(R.string.gallery_empty_action)) }
        add(empty)

        val tip = TipView(context)
        tip.bind(prefs, "gallery.tip", getString(R.string.gallery_tip))
        add(tip)

        add(button(getString(R.string.gallery_consent_peer)) {
            ConsentDialog.forPeer(this, "Ravi's Redmi:2", "Ravi\u2019s Redmi",
                "Phone · Wi-Fi LAN · 192.168.1.42") { accepted ->
                Ui.snackbar(this, accepted.toString())
            }.show()
        })
        add(button(getString(R.string.gallery_consent_browser)) {
            ConsentDialog.forBrowser(this, "Chrome · 192.168.1.88") { accepted ->
                Ui.snackbar(this, accepted.toString())
            }.show()
        })
    }

    private fun row(
        name: String,
        meta: String,
        kind: FileKind,
        state: ChipState,
        progress: Float,
    ): View {
        val view = TransferRowView(this)
        view.bindVariant(TransferRowView.Variant.SENDER)
        view.bind(name, meta, kind, state, progress) { Ui.snackbar(this, name) }
        return view
    }

    private fun header(title: CharSequence): View {
        val view = SectionHeaderView(this)
        view.bind(title, getString(R.string.gallery_action)) { Ui.snackbar(this, title) }
        return view
    }

    private fun add(view: View, params: LinearLayout.LayoutParams? = null) {
        column.addView(
            view,
            params ?: LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val spacer = View(this)
        spacer.setBackgroundColor(Color.TRANSPARENT)
        column.addView(
            spacer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Shapes.dpInt(this, 8f),
            ),
        )
    }
}
