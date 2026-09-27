package app.morsecode.android.core.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import app.morsecode.android.R
import app.morsecode.android.core.util.ThemeColors

/**
 * §6.16 CONSENT DIALOGS — a centred card over a dimmed screen, 20 dp radius,
 * 72 dp icon on top.
 *
 *  a) Peer:    avatar, "Connection request", "<name> wants to send you files."
 *              with the peer name in the accent, mono
 *              "Phone · Wi-Fi LAN · 192.168.1.42".
 *  b) Browser: blue globe, "Browser wants access", "A browser session wants to
 *              browse your phone.", mono "Chrome · 192.168.1.88".
 *
 * Buttons are [Reject] (neutral outline) and [Accept] (#22C55E filled).
 * NOTHING crosses the wire before Accept (§6.16, §17.2) — this dialog only
 * reports the decision; the transport does the tearing down.
 */
object ConsentDialog {

    fun forPeer(
        context: Context,
        deviceId: String,
        peerName: String,
        monoLine: CharSequence,
        onDecision: (accepted: Boolean) -> Unit,
    ): Dialog {
        val avatar = AvatarView(context)
        avatar.bind(deviceId, peerName)
        val body = SpannableString(context.getString(R.string.consent_peer_body, peerName))
        val start = body.indexOf(peerName)
        if (start >= 0) {
            body.setSpan(
                ForegroundColorSpan(ThemeColors.accent(context)),
                start, start + peerName.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return build(
            context,
            header = avatar,
            title = context.getString(R.string.consent_peer_title),
            body = body,
            monoLine = monoLine,
            onDecision = onDecision,
        )
    }

    fun forBrowser(
        context: Context,
        monoLine: CharSequence,
        onDecision: (accepted: Boolean) -> Unit,
    ): Dialog {
        val globe = AppCompatImageView(context)
        globe.setImageResource(R.drawable.ic_globe)
        globe.background = Shapes.rounded(
            ContextCompat.getColor(context, R.color.onboarding_globe),
            Shapes.dp(context, 72f * 0.22f),
        )
        val pad = Shapes.dpInt(context, 16f)
        globe.setPadding(pad, pad, pad, pad)
        ImageViewCompat.setImageTintList(globe, ColorStateList.valueOf(Color.WHITE))
        globe.contentDescription = context.getString(R.string.cd_consent_icon)
        return build(
            context,
            header = globe,
            title = context.getString(R.string.consent_browser_title),
            body = context.getString(R.string.consent_browser_body),
            monoLine = monoLine,
            onDecision = onDecision,
        )
    }

    private fun build(
        context: Context,
        header: android.view.View,
        title: CharSequence,
        body: CharSequence,
        monoLine: CharSequence,
        onDecision: (Boolean) -> Unit,
    ): Dialog {
        val dialog = AppCompatDialog(context, R.style.Theme_Morsecode_Dialog)
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.background = Shapes.rounded(
            ThemeColors.resolve(context, R.attr.colorSurfaceRaised),
            Shapes.dp(context, 20f),
            Shapes.dpInt(context, 1f),
            ThemeColors.resolve(context, R.attr.colorHairline),
        )
        val pad = Shapes.dpInt(context, 20f)
        root.setPadding(pad, pad, pad, pad)

        val iconSize = Shapes.dpInt(context, 72f)
        root.addView(header, LinearLayout.LayoutParams(iconSize, iconSize))

        val titleView = AppCompatTextView(context)
        titleView.setTextAppearance(context, R.style.TextAppearance_Morsecode_ScreenTitle)
        titleView.text = title
        titleView.gravity = Gravity.CENTER
        root.addView(titleView, marginParams(context, top = 16))

        val bodyView = AppCompatTextView(context)
        bodyView.setTextAppearance(context, R.style.TextAppearance_Morsecode_Body)
        bodyView.text = body
        bodyView.gravity = Gravity.CENTER
        root.addView(bodyView, marginParams(context, top = 8))

        val monoView = AppCompatTextView(context)
        monoView.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
        monoView.text = monoLine
        monoView.gravity = Gravity.CENTER
        root.addView(monoView, marginParams(context, top = 8))

        val buttons = LinearLayout(context)
        buttons.orientation = LinearLayout.HORIZONTAL

        val reject = AppCompatTextView(context)
        reject.setTextAppearance(context, R.style.TextAppearance_Morsecode_Button)
        reject.setText(R.string.consent_reject)
        reject.gravity = Gravity.CENTER
        reject.background = Shapes.outlinedButton(context)
        reject.minHeight = Shapes.dpInt(context, 48f)
        reject.contentDescription = reject.text
        reject.setOnClickListener {
            dialog.dismiss()
            onDecision(false)
        }
        val rejectParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        rejectParams.rightMargin = Shapes.dpInt(context, 8f)
        buttons.addView(reject, rejectParams)

        val accept = AppCompatTextView(context)
        accept.setTextAppearance(context, R.style.TextAppearance_Morsecode_Button)
        accept.setText(R.string.consent_accept)
        accept.gravity = Gravity.CENTER
        accept.setTextColor(ThemeColors.resolve(context, R.attr.colorInkOnAccent))
        accept.background = Shapes.rounded(
            ContextCompat.getColor(context, R.color.state_success),
            Shapes.dp(context, 14f),
        )
        accept.minHeight = Shapes.dpInt(context, 48f)
        accept.contentDescription = accept.text
        accept.setOnClickListener {
            dialog.dismiss()
            onDecision(true)
        }
        buttons.addView(accept, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(buttons, marginParams(context, top = 20))

        dialog.setContentView(root)
        dialog.setCancelable(false)   // a consent gate is answered, never dismissed by accident
        return dialog
    }

    private fun marginParams(context: Context, top: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = Shapes.dpInt(context, top.toFloat())
        return params
    }
}
