package fr.arichard.lastlauncher.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import fr.arichard.lastlauncher.LauncherApp
import fr.arichard.lastlauncher.R
import fr.arichard.lastlauncher.databinding.ActivityInsightsBinding
import fr.arichard.lastlauncher.predict.PredictionEngine
import fr.arichard.lastlauncher.ui.StatusLine

/**
 * "How it works": a terminal-style live report of the prediction engine — what data
 * it keeps, which signals it weighs, and the current ranking with the reasons behind
 * it. Everything shown is computed on-device from the local database.
 */
class InsightsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityInsightsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.pref_insights)

        binding.insightsText.text = getString(R.string.insights_loading)
        PredictionEngine.snapshot(this) { snap ->
            binding.insightsText.text = render(snap)
        }
    }

    private fun render(s: PredictionEngine.Snapshot): String {
        val repo = (application as LauncherApp).repo
        fun label(pkg: String) = repo.byPackage(pkg)?.label ?: pkg
        val sb = StringBuilder()

        sb.append("§ ").append(getString(R.string.insights_how_title)).append("\n\n")
        sb.append(getString(R.string.insights_how_body)).append("\n\n")

        sb.append("§ ").append(getString(R.string.insights_weights_title)).append("\n")
        for ((key, value) in PredictionEngine.weights()) {
            val name = when (key) {
                "hour" -> getString(R.string.insights_w_hour)
                "daytype" -> getString(R.string.insights_w_daytype)
                "transition" -> getString(R.string.insights_w_transition)
                "trigger" -> getString(R.string.insights_w_trigger)
                "notification" -> getString(R.string.insights_w_notification)
                else -> getString(R.string.insights_w_boost)
            }
            sb.append("  %-11s %s\n".format(java.util.Locale.US, "×$value", name))
        }
        sb.append("\n")

        sb.append("§ ").append(getString(R.string.insights_data_title)).append("\n")
        sb.append("  ").append(getString(R.string.insights_rows, s.totalRows)).append("\n")
        sb.append("  ").append(getString(R.string.insights_days, s.daysCovered)).append("\n")
        sb.append("  ").append(
            getString(R.string.insights_db, StatusLine.formatBytes(s.dbBytes))
        ).append("\n")
        sb.append("  ").append(getString(R.string.insights_today, s.launchesToday)).append("\n\n")

        sb.append("§ ").append(getString(R.string.insights_context_title)).append("\n")
        sb.append("  ").append(
            getString(
                R.string.insights_context_now, s.hour,
                getString(if (s.weekend) R.string.insights_weekend else R.string.insights_weekday)
            )
        ).append("\n")
        s.prevApp?.let {
            sb.append("  ").append(getString(R.string.insights_prev_app, label(it))).append("\n")
        }
        s.activeTrigger?.let {
            sb.append("  ").append(getString(R.string.insights_trigger, it)).append("\n")
        }
        if (s.notifying.isNotEmpty()) {
            sb.append("  ").append(
                getString(R.string.insights_notifying, s.notifying.size)
            ).append("\n")
        }
        sb.append("\n")

        sb.append("§ ").append(getString(R.string.insights_ranking_title)).append("\n")
        if (s.topScores.isEmpty()) {
            sb.append("  ").append(getString(R.string.insights_no_data)).append("\n")
        }
        for ((i, entry) in s.topScores.withIndex()) {
            val (pkg, score) = entry
            val marks = buildString {
                if (i < 3) append(" ◈")
                if (pkg in s.boosted) append(" ↑boost")
                s.notifying[pkg]?.let { append(" ✉$it") }
            }
            sb.append(
                "  %2d. %-22s %6.2f%s\n"
                    .format(java.util.Locale.US, i + 1, label(pkg).take(22), score, marks)
            )
        }
        sb.append("\n").append(getString(R.string.insights_footer))
        return sb.toString()
    }
}
