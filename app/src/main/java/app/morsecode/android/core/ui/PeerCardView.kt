package app.morsecode.android.core.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatTextView
import app.morsecode.android.R
import app.morsecode.android.core.util.ThemeColors

/** The transport badge shown on the right of a peer row (§4.12a). */
enum class TransportBadge(@StringRes val labelRes: Int) {
    LAN(R.string.badge_lan),
    NEARBY(R.string.badge_nearby),
    WEB(R.string.badge_web),
    FROM(R.string.badge_from),
}

/**
 * §4.12a PeerCard: avatar, name, "Connected · Phone · LAN" mono subtitle, right
 * transport badge. Used on the transfer, receive, broadcast and complete
 * screens — built once here, never re-drawn per screen.
 */
class PeerCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val avatar = AvatarView(context)
    private val nameView = AppCompatTextView(context)
    private val subtitleView = AppCompatTextView(context)
    private val badgeView = AppCompatTextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Shapes.card(context)
        val pad = Shapes.dpInt(context, 12f)
        setPadding(pad, pad, pad, pad)

        val avatarSize = Shapes.dpInt(context, 40f)
        val avatarParams = LayoutParams(avatarSize, avatarSize)
        avatarParams.rightMargin = Shapes.dpInt(context, 12f)
        addView(avatar, avatarParams)

        val textColumn = LinearLayout(context)
        textColumn.orientation = VERTICAL
        nameView.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemTitle)
        nameView.maxLines = 1
        nameView.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        subtitleView.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
        textColumn.addView(nameView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        textColumn.addView(subtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(textColumn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        badgeView.setTextAppearance(context, R.style.TextAppearance_Morsecode_StateChip)
        val badgePadH = Shapes.dpInt(context, 8f)
        val badgePadV = Shapes.dpInt(context, 4f)
        badgeView.setPadding(badgePadH, badgePadV, badgePadH, badgePadV)
        addView(badgeView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    /** `subtitle` is the mono line, e.g. "Connected · Phone · LAN" (§6.4). */
    fun bind(deviceId: String, name: CharSequence, subtitle: CharSequence, badge: TransportBadge) {
        avatar.bind(deviceId, name.toString())
        nameView.text = name
        subtitleView.text = subtitle

        val accent = ThemeColors.accent(context)
        badgeView.setText(badge.labelRes)
        badgeView.setTextColor(accent)
        badgeView.background = Shapes.chip(
            context,
            ThemeColors.withAlpha(accent, 0.12f),
            ThemeColors.withAlpha(accent, 0.45f),
        )
        badgeView.contentDescription = badgeView.text
        contentDescription = "$name $subtitle ${badgeView.text}"
    }
}
