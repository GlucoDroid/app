package tk.glucodata

import android.app.Notification
import android.content.Context

object WearOngoingActivity {
    @JvmStatic
    fun attach(context: Context, notification: Notification, notificationId: Int): Notification = notification

    @JvmStatic
    fun updateStatus(context: Context?, notificationId: Int) {
    }
}
