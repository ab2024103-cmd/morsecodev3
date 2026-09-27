package app.morsecode.android.feature.help

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import app.morsecode.android.R
import app.morsecode.android.core.ui.Buttons
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.util.ThemeColors
import app.morsecode.android.feature.settings.ConnectionDoctorFragment
import app.morsecode.android.feature.settings.SettingsFragment

/**
 * §6.17 HELP & FAQ — an accordion with the first item expanded, then the
 * troubleshooting articles, "each ending in a real action button".
 *
 * Every article's button goes somewhere that can actually fix the problem;
 * none of them is a dead end, which is the whole point of §6.17's last line.
 */
class HelpFragment : Screen() {

    private data class Entry(val questionRes: Int, val answerRes: Int)

    private data class Article(
        val titleRes: Int,
        val bodyRes: Int,
        val actionRes: Int,
        val action: (HelpFragment) -> Unit,
    )

    private val faq = listOf(
        Entry(R.string.help_q1, R.string.help_a1),
        Entry(R.string.help_q2, R.string.help_a2),
        Entry(R.string.help_q3, R.string.help_a3),
        Entry(R.string.help_q4, R.string.help_a4),
        Entry(R.string.help_q5, R.string.help_a5),
    )

    private val articles = listOf(
        Article(R.string.help_article_not_found, R.string.help_a1, R.string.help_open_doctor) {
            it.nav().push(ConnectionDoctorFragment())
        },
        Article(R.string.help_article_stalls, R.string.help_a3, R.string.help_open_battery) {
            it.nav().push(SettingsFragment())
        },
        Article(R.string.help_article_browser, R.string.help_a4, R.string.help_open_doctor) {
            it.nav().push(ConnectionDoctorFragment())
        },
        Article(R.string.help_article_apk, R.string.help_a5, R.string.help_open_storage) {
            it.nav().push(SettingsFragment())
        },
        Article(R.string.help_article_hotspot, R.string.help_a4, R.string.help_open_doctor) {
            it.nav().push(ConnectionDoctorFragment())
        },
        Article(R.string.help_article_slow, R.string.help_a1, R.string.help_open_doctor) {
            it.nav().push(ConnectionDoctorFragment())
        },
        Article(R.string.help_article_missing, R.string.help_a5, R.string.help_open_storage) {
            it.nav().push(SettingsFragment())
        },
        Article(R.string.help_article_broadcast, R.string.help_a3, R.string.help_open_doctor) {
            it.nav().push(ConnectionDoctorFragment())
        },
    )

    override fun onBuildScreen(column: LinearLayout) {
        val context = requireContext()
        toolbar.bind(getString(R.string.title_help)) { nav().pop() }

        faq.forEachIndexed { index, entry ->
            // §6.17: the first item is expanded.
            column.addView(accordion(entry, expanded = index == 0), params(4))
        }

        for (article in articles) {
            column.addView(articleCard(article), params(8))
        }
    }

    private fun accordion(entry: Entry, expanded: Boolean): View {
        val context = requireContext()
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.background = Shapes.card(context, 12f)
        val pad = Shapes.dpInt(context, 14f)
        card.setPadding(pad, pad, pad, pad)

        val question = AppCompatTextView(context)
        question.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemTitle)
        question.setText(entry.questionRes)
        question.minHeight = Shapes.dpInt(context, 48f)
        card.addView(question)

        val answer = AppCompatTextView(context)
        answer.setTextAppearance(context, R.style.TextAppearance_Morsecode_Body)
        answer.setText(entry.answerRes)
        answer.visibility = if (expanded) View.VISIBLE else View.GONE
        card.addView(answer)

        card.isClickable = true
        card.setOnClickListener {
            answer.visibility = if (answer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        card.contentDescription = getString(entry.questionRes)
        return card
    }

    private fun articleCard(article: Article): View {
        val context = requireContext()
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.background = Shapes.raised(context, 12f)
        val pad = Shapes.dpInt(context, 14f)
        card.setPadding(pad, pad, pad, pad)

        val title = AppCompatTextView(context)
        title.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemTitle)
        title.setText(article.titleRes)
        card.addView(title)

        val body = AppCompatTextView(context)
        body.setTextAppearance(context, R.style.TextAppearance_Morsecode_Body)
        body.setText(article.bodyRes)
        card.addView(body)

        // §6.17: every article ends in a REAL action button.
        card.addView(
            Buttons.outlined(context, getString(article.actionRes)) { article.action(this) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return card
    }

    private fun params(topMarginDp: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = Shapes.dpInt(requireContext(), topMarginDp.toFloat())
        return params
    }
}
