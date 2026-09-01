package tk.glucodata

import android.content.Context
import java.util.Calendar
import tk.glucodata.Log.doLog

object SpeakSchedule {
    private const val LOG_ID = "SpeakSchedule"
    private const val PREFS = "tk.glucodata_preferences"
    private const val KEY_ENABLED = "voice_schedule_enabled"
    private const val KEY_START = "voice_schedule_start_minutes"
    private const val KEY_END = "voice_schedule_end_minutes"

    private const val DEFAULT_START = 0        // 00:00
    private const val DEFAULT_END = 22 * 60    // 22:00

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getStartMinutes(context: Context): Int =
        prefs(context).getInt(KEY_START, DEFAULT_START)

    fun getEndMinutes(context: Context): Int =
        prefs(context).getInt(KEY_END, DEFAULT_END)

    fun setStartMinutes(context: Context, minutes: Int) {
        val clamped = minutes.coerceIn(0, 1439)
        if (doLog) {
            val previous = getStartMinutes(context)
            if (previous != clamped) {
                Log.stack(LOG_ID, "setStartMinutes CHANGED ${formatMinutes(previous)} -> ${formatMinutes(clamped)}"
                        + " (caller stack below)", Throwable())
            }
        }
        prefs(context).edit().putInt(KEY_START, clamped).apply()
    }

    fun setEndMinutes(context: Context, minutes: Int) {
        val clamped = minutes.coerceIn(0, 1440)
        if (doLog) {
            val previous = getEndMinutes(context)
            if (previous != clamped) {
                Log.stack(LOG_ID, "setEndMinutes CHANGED ${formatMinutes(previous)} -> ${formatMinutes(clamped)}"
                        + " (caller stack below)", Throwable())
            }
        }
        prefs(context).edit().putInt(KEY_END, clamped).apply()
    }

    fun isWithinSchedule(context: Context): Boolean {
        if (!isEnabled(context)) return true
        val cal = Calendar.getInstance()
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val start = getStartMinutes(context)
        val end = getEndMinutes(context)
        val result = if (start == end) true // equal = all day, no restriction
            else if (start < end) nowMinutes in start until end
            else nowMinutes >= start || nowMinutes < end // spans midnight
        if (doLog) {
            Log.i(LOG_ID, "isWithinSchedule now=${formatMinutes(nowMinutes)}"
                    + " storedStart=${formatMinutes(start)}(raw=$start)"
                    + " storedEnd=${formatMinutes(end)}(raw=$end)"
                    + " tz=${java.util.TimeZone.getDefault().id}"
                    + " result=$result")
        }
        return result
    }

    fun formatMinutes(minutes: Int): String {
        val h = (minutes / 60).coerceIn(0, 23)
        val m = (minutes % 60).coerceIn(0, 59)
        return String.format("%02d:%02d", h, m)
    }
}
