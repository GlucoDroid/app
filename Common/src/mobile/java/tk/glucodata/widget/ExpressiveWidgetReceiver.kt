package tk.glucodata.widget

import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ExpressiveWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ExpressiveAppWidget()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "tk.glucodata.action.GLUCOSE_UPDATE") {
            val pendingResult = goAsync()
            scope.launch {
                try {
                    performUpdate(context.applicationContext)
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }
        super.onReceive(context, intent)
    }
    
    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        
        @JvmStatic
        fun updateAll(context: Context) {
            scope.launch {
                performUpdate(context.applicationContext)
            }
        }

        private suspend fun performUpdate(context: Context) {
            val manager = androidx.glance.appwidget.GlanceAppWidgetManager(context)
            val widget = ExpressiveAppWidget()
            val glanceIds = manager.getGlanceIds(ExpressiveAppWidget::class.java)
            glanceIds.forEach { glanceId ->
                widget.update(context, glanceId)
            }
        }
    }
}
