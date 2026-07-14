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
            val base = render(snap)
            binding.insightsText.text = base
            // The alarm block reads system sources (binder/provider) — off-thread.
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            executor.execute {
                val alarm = renderAlarmSources()
                runOnUiThread {
                    if (!isDestroyed) binding.insightsText.text = base + alarm
                }
                executor.shutdown()
            }
        }
    }

    /**
     * Raw next-alarm data from both system sources, as the launcher sees them.
     * Diagnostic block: the status-line alarm has repeatedly shown a wrong hour on
     * the owner's device, and this is the only way to see which source lies.
     */
    private fun renderAlarmSources(): String {
        val sb = StringBuilder()
        sb.append("\n\n§ ").append(getString(R.string.insights_alarm_title)).append("\n")
        val tz = java.util.TimeZone.getDefault()
        sb.append(
            "  timezone: %s (UTC%+d min)\n".format(
                java.util.Locale.US, tz.id, tz.getOffset(System.currentTimeMillis()) / 60000
            )
        )
        val info = getSystemService(android.app.AlarmManager::class.java)?.nextAlarmClock
        if (info == null) {
            sb.append("  AlarmClockInfo: null\n")
        } else {
            val fmt = java.text.SimpleDateFormat(
                "EEE yyyy-MM-dd HH:mm:ss zzz", java.util.Locale.US
            )
            sb.append("  AlarmClockInfo.triggerTime: ").append(info.triggerTime).append("\n")
            sb.append("    local: ").append(fmt.format(java.util.Date(info.triggerTime))).append("\n")
            sb.append("    owner: ")
                .append(info.showIntent?.creatorPackage ?: "?").append("\n")
        }
        val formatted = try {
            @Suppress("DEPRECATION")
            android.provider.Settings.System.getString(
                contentResolver, android.provider.Settings.System.NEXT_ALARM_FORMATTED
            )
        } catch (e: Exception) {
            null
        }
        sb.append("  NEXT_ALARM_FORMATTED: ")
            .append(formatted?.let { "\"$it\"" } ?: "null").append("\n")
        val parsed = StatusLine.parseTimeOfDay(formatted)
        sb.append("    parsed: ").append(
            parsed?.let { "%02d:%02d".format(java.util.Locale.US, it / 60, it % 60) } ?: "null"
        ).append("\n")
        if (info != null) {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = info.triggerTime }
            val trigger =
                cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            val display = StatusLine.reconcileAlarmMinutes(parsed, trigger)
            sb.append("  status line shows: ").append(
                "%02d:%02d".format(java.util.Locale.US, display / 60, display % 60)
            ).append("\n")
        }
        return sb.toString()
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
