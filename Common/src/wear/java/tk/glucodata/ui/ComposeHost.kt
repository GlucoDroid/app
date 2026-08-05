package tk.glucodata.ui

import android.view.View
import androidx.activity.compose.setContent
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
import tk.glucodata.ui.theme.WearJugglucoTheme

// MainActivity.java looks this function up reflectively as
// `tk.glucodata.ui.ComposeHostKt#setComposeContent` — same contract as the
// mobile ComposeHost. Renaming the file or moving this function breaks the
// lookup and silently falls back to the legacy View UI.
@Keep
fun setComposeContent(activity: AppCompatActivity, legacyView: View?) {
    legacyView?.visibility = View.GONE

    activity.setContent {
        WearJugglucoTheme {
            WearApp()
        }
    }
}
