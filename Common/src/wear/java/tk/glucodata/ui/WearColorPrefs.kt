package tk.glucodata.ui

import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.GlucoseRangeColors

/**
 * The phone colours a reading only when it leaves range and leaves in-range
 * values in the neutral text colour. The watch used the in-range hue for
 * everything, so a whole screen of green carried no information. Same default
 * here, with the old behaviour available for anyone who wants it.
 */
object WearColorPrefs {
    private const val PREFS = "tk.glucodata_preferences"
    private const val KEY_COLOR_IN_RANGE = "wear_color_in_range"
    /** Neutral on-surface tone; matches the phone's in-range readout. */
    private const val NEUTRAL = 0xFFE3E2DE.toInt()

    private fun prefs(): android.content.SharedPreferences? =
        Applic.app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @JvmStatic
    fun colorInRange(): Boolean = prefs()?.getBoolean(KEY_COLOR_IN_RANGE, false) ?: false

    @JvmStatic
    fun setColorInRange(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_COLOR_IN_RANGE, enabled)?.apply()
    }

    /** The colour to use for a reading that is within range. */
    @JvmStatic
    fun inRangeColor(): Int =
        if (colorInRange()) GlucoseRangeColors.inRange(true) else NEUTRAL
}
