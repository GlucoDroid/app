package tk.glucodata

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the connect-mode lever and the measurement that would justify ever pulling it.
 *
 * SuperGattCallback.autoconnect is a single application-wide static that the SensorBluetooth
 * constructor overwrites from Natives.getAndroid13(); while the connectGatt call sites read it
 * directly no driver could choose its own mode, and the 2026-08-01 jamming storm accordingly
 * logged 103 connect attempts (52 + 51, two Ottai sensors) at autoConnect=true and none at false.
 * useAutoConnect() makes a per-driver choice expressible — deliberately with no override anywhere
 * yet, because the modelled effect of a direct connect on these sensors ranges from 1071s saved to
 * 280s lost and nothing in the dataset decides between them. These are source scans because the
 * classes involved need the Android runtime and the native library to be constructed at all.
 */
class ConnectModeLeverTests {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/java/tk/glucodata/SuperGattCallback.java").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Common/src not found from ${System.getProperty("user.dir")}")
    }

    private fun superGattCallback(): String =
        File(repoRoot(), "Common/src/main/java/tk/glucodata/SuperGattCallback.java").readText()

    private fun sources(vararg roots: String): List<File> =
        roots.flatMap { root ->
            File(repoRoot(), root).walkTopDown()
                .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
                .toList()
        }

    @Test
    fun everyConnectGattCallSiteAsksTheDriver() {
        val text = superGattCallback().replace(Regex("\\s+"), " ")
        val callSites = Regex("connectGatt\\(Applic\\.app, ([A-Za-z.()]+),").findAll(text)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "SuperGattCallback must keep exactly the three connectGatt call sites",
            3,
            callSites.size,
        )
        assertTrue(
            "every connectGatt call site must read the connect mode through useAutoConnect(), not " +
                "the application-wide static: $callSites",
            callSites.all { it == "cb.useAutoConnect()" },
        )
    }

    @Test
    fun noDriverOverridesTheDefault() {
        // R2(a) is a lever, not a change of behaviour: an override appearing here means some
        // driver now connects in a mode the 2026-08-01 dataset never measured.
        val overriders = sources("Common/src")
            .filter { it.name != "SuperGattCallback.java" && it.name != "ConnectModeLeverTests.kt" }
            .filter { it.readText().contains("useAutoConnect") }
            .map { it.name }
        assertTrue(
            "no driver may override useAutoConnect() without field measurements of the other " +
                "mode; found $overriders",
            overriders.isEmpty(),
        )
    }

    @Test
    fun everyGattDriverRecordsItsFirstCallbackLatency() {
        // The base class cannot funnel this: onConnectionStateChange is in practice the first
        // callback of an attempt and no driver except AiDex chains to super, so a driver that
        // declares it and forgets the call contributes nothing to the comparison.
        // Every source set, not src/main alone: Libre3 and Si are the app's primary sensors and
        // both live in flavour source sets, so a src/main scan stayed green while the measurement
        // was absent for most users. GlucoseMeterGatt is a plain BluetoothGattCallback and has no
        // connectGatt of ours to time, hence the subclass filter.
        val missing = sources("Common/src")
            .filterNot { it.path.replace('\\', '/').contains("/Common/src/test/") }
            .filter { it.name != "SuperGattCallback.java" }
            .filter { file ->
                val text = file.readText()
                (text.contains("extends SuperGattCallback") || text.contains(": SuperGattCallback("))
                    && (text.contains("override fun onConnectionStateChange(")
                        || text.contains("public void onConnectionStateChange("))
                    && !text.contains("noteFirstGattCallback(")
            }
            .map { it.name }
        assertTrue(
            "every SuperGattCallback subclass must report how long connectGatt took to produce " +
                "its first callback; missing in $missing",
            missing.isEmpty(),
        )
    }
}
