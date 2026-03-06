package tk.glucodata

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.View
import com.google.android.gms.wearable.Node
import tk.glucodata.nums.AllData

object WatchInterop {
    data class WearNodeInfo(
        val id: String,
        val displayName: String,
        val isGalaxy: Boolean,
        val directSensorMode: Int,
        val watchNumsMode: Int
    )

    data class GarminSnapshot(
        val sdkReady: Boolean,
        val registered: Boolean,
        val sendEnabled: Boolean,
        val waitingQueue: Boolean,
        val sendTimeMs: Long,
        val receivedTimeMs: Long,
        val sendStatus: String
    )

    @JvmStatic
    fun isNotifyEnabled(): Boolean = Applic.isWatchNotifyEnabled()

    @JvmStatic
    fun setNotifyEnabled(enabled: Boolean) {
        Applic.setWatchNotifyEnabled(enabled)
    }

    @JvmStatic
    fun isGooglePlayServicesAvailable(): Boolean = GoogleServices.isPlayServicesAvailable(Applic.app)

    @JvmStatic
    fun isWearOsEnabled(): Boolean = Applic.useWearos()

    @JvmStatic
    fun setWearOsEnabled(enabled: Boolean): Boolean {
        val app = Applic.app ?: return false
        return try {
            val pm = app.packageManager
            val receiver = ComponentName(app, MessageReceiver::class.java)
            val targetState = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            pm.setComponentEnabledSetting(receiver, targetState, PackageManager.DONT_KILL_APP)
            if (enabled) {
                MessageSender.initwearos(app)
                MessageSender.getMessageSender()?.finddevices()
                Natives.networkpresent()
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun refreshWearNodes() {
        MessageSender.getMessageSender()?.finddevices()
    }

    @JvmStatic
    fun getWearNodes(): List<WearNodeInfo> {
        val sender = MessageSender.getMessageSender() ?: return emptyList()
        val nodes = sender.nodes?.toList() ?: emptyList()
        return nodes.map { node ->
            val id = node.id
            WearNodeInfo(
                id = id,
                displayName = node.displayName ?: id,
                isGalaxy = MessageSender.isGalaxy(node),
                directSensorMode = try { Natives.directsensorwatch(id) } catch (_: Throwable) { -1 },
                watchNumsMode = try { Natives.hasWatchNums(id) } catch (_: Throwable) { -1 }
            )
        }
    }

    @JvmStatic
    fun applyWearNodeRouting(nodeId: String, isGalaxy: Boolean, directOnWatch: Boolean, enterOnWatch: Boolean): Boolean {
        val netInfo = try {
            Natives.getmynetinfo(
                nodeId,
                true,
                if (directOnWatch) 1 else -1,
                isGalaxy,
                if (enterOnWatch) 1 else -1
            )
        } catch (_: Throwable) {
            null
        } ?: return false

        val sender = MessageSender.getMessageSender() ?: return false
        return try {
            sender.sendnetinfo(nodeId, netInfo)
            sender.sendbluetooth(nodeId, directOnWatch)
            val context = MainActivity.thisone ?: Applic.app ?: return false
            Applic.setbluetooth(context, !directOnWatch)
            true
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun applyWearDefaults(nodeId: String, isGalaxy: Boolean): Boolean {
        val sender = MessageSender.getMessageSender() ?: return false
        val node: Node = sender.nodes?.firstOrNull { it.id == nodeId } ?: return false
        return try {
            sender.toDefaults(node)
            Natives.setWearosdefaults(nodeId, isGalaxy)
            val context = MainActivity.thisone ?: Applic.app ?: return false
            Applic.setbluetooth(context, true)
            true
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun startWearApp(nodeId: String, isGalaxy: Boolean): Boolean {
        val sender = MessageSender.getMessageSender() ?: return false
        return try {
            Natives.resetbylabel(nodeId, isGalaxy)
            sender.startWearOSActivity(nodeId)
            true
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun getGarminSnapshot(): GarminSnapshot {
        val data: AllData? = Applic.app?.numdata
        if (data == null) {
            return GarminSnapshot(
                sdkReady = false,
                registered = false,
                sendEnabled = false,
                waitingQueue = false,
                sendTimeMs = 0L,
                receivedTimeMs = 0L,
                sendStatus = "N/A"
            )
        }
        return GarminSnapshot(
            sdkReady = try { data.sdkready() } catch (_: Throwable) { false },
            registered = try { data.usewatch } catch (_: Throwable) { false },
            sendEnabled = try { data.sendtowatch } catch (_: Throwable) { false },
            waitingQueue = try { data.waiting() } catch (_: Throwable) { false },
            sendTimeMs = try { data.sendtime } catch (_: Throwable) { 0L },
            receivedTimeMs = try { data.receivedmessage } catch (_: Throwable) { 0L },
            sendStatus = try { data.sendstatus?.name ?: "N/A" } catch (_: Throwable) { "N/A" }
        )
    }

    @JvmStatic
    fun reinitGarmin(): Boolean {
        val app = Applic.app ?: return false
        val data = app.numdata ?: return false
        return try {
            data.reinit(MainActivity.thisone ?: app)
            true
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun syncGarmin(): Boolean {
        val data = Applic.app?.numdata ?: return false
        return try {
            data.sync()
            true
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun sendGarminQueueNext(): Boolean {
        val data = Applic.app?.numdata ?: return false
        return try {
            data.nextmessage()
            true
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun isKerfstokEnabled(): Boolean = Natives.getusegarmin()

    @JvmStatic
    fun isKerfstokDarkMode(): Boolean = Natives.getkerfstokblack()

    @JvmStatic
    fun setKerfstokDarkMode(enabled: Boolean) {
        Natives.setkerfstokblack(enabled)
        try { Applic.app?.numdata?.setcolor(enabled) } catch (_: Throwable) {}
    }

    @JvmStatic
    fun openKerfstokStore(context: Context): Boolean {
        val url = "https://apps.garmin.com/en-US/apps/b6348ccc-86d8-4780-8013-d9e19fed5260"
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun importCertificateFile(context: Context, uri: Uri, outputFileName: String): String? {
        val app = Applic.app ?: context.applicationContext
        val outputFile = app.getFileStreamPath(outputFileName)
        return try {
            var total = 0
            app.contentResolver.openInputStream(uri)?.use { input ->
                app.openFileOutput(outputFileName, Context.MODE_PRIVATE).use { output ->
                    val buffer = ByteArray(2 * 4096)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            } ?: return "Unable to open selected file"
            if (total < 50) {
                outputFile.delete()
                "Selected file is too small"
            } else {
                null
            }
        } catch (t: Throwable) {
            outputFile.delete()
            t.message ?: "Failed to import certificate file"
        }
    }
}
