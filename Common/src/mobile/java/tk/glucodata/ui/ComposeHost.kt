package tk.glucodata.ui

import android.content.Context
import android.view.View
import androidx.activity.compose.setContent
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

// MainActivity.java looks this function up reflectively as
// `tk.glucodata.ui.ComposeHostKt#setComposeContent`. Renaming the file or
// moving this function to a different file would break that lookup and
// prevent the Compose UI from initializing on app launch.
@Keep
fun setComposeContent(activity: AppCompatActivity, legacyView: View?) {
    // Hide the native legacy view (histogram/nanovg) to prevent double-rendering,
    // GPU overdraw, and visual glitches (bleeding through navbar).
    legacyView?.visibility = View.GONE

    activity.setContent {
        val prefs = activity.getSharedPreferences(activity.packageName + "_preferences", Context.MODE_PRIVATE)
        val savedTheme = prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM"
        var themeMode by remember { mutableStateOf(ThemeMode.valueOf(savedTheme)) }

        JugglucoTheme(themeMode = themeMode) {
            MainApp(
                themeMode = themeMode,
                onThemeChanged = { newMode ->
                    themeMode = newMode
                    prefs.edit().putString("theme_mode", newMode.name).apply()
                }
            )
        }
    }
}
