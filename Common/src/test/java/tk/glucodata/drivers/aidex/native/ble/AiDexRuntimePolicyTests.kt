package tk.glucodata.drivers.aidex.native.ble

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDexRuntimePolicyTests {

    @Test
    fun initialAssistDelay_waitsForInitialHistoryWindow() {
        val delayMs = AiDexRuntimePolicy.initialAssistDelayMs(
            nowMs = 16_000L,
            phaseStreaming = true,
            pendingInitialHistoryRequest = true,
            historyDownloading = false,
            streamingStartedAtMs = 1_000L,
            initialHistoryRequestDelayMs = 65_000L,
        )

        assertEquals(50_000L, delayMs)
    }

    @Test
    fun initialAssistDelay_disabledOnceHistoryWindowIsOver() {
        val delayMs = AiDexRuntimePolicy.initialAssistDelayMs(
            nowMs = 80_000L,
            phaseStreaming = true,
            pendingInitialHistoryRequest = true,
            historyDownloading = false,
            streamingStartedAtMs = 0L,
            initialHistoryRequestDelayMs = 65_000L,
        )

        assertNull(delayMs)
    }

    @Test
    fun shouldContinueAssistScanning_falseWhileInitialHistoryStillPending() {
        val shouldContinue = AiDexRuntimePolicy.shouldContinueAssistScanning(
            stop = false,
            broadcastOnlyMode = false,
            phaseStreaming = true,
            hasRecentLiveData = false,
            pendingInitialHistoryRequest = true,
            historyDownloading = false,
            anchorMs = 0L,
            nowMs = 30_000L,
            lastGlucoseTimeMs = 0L,
            firstValidReadingWaitMaxMs = 60L * 60_000L,
        )

        assertFalse(shouldContinue)
    }

    @Test
    fun shouldContinueAssistScanning_falseAfterValidReadingSeen() {
        val anchorMs = 1_000L
        val shouldContinue = AiDexRuntimePolicy.shouldContinueAssistScanning(
            stop = false,
            broadcastOnlyMode = false,
            phaseStreaming = true,
            hasRecentLiveData = false,
            pendingInitialHistoryRequest = false,
            historyDownloading = false,
            anchorMs = anchorMs,
            nowMs = anchorMs + 10L * 60_000L,
            lastGlucoseTimeMs = anchorMs + 5L * 60_000L,
            firstValidReadingWaitMaxMs = 60L * 60_000L,
        )

        assertFalse(shouldContinue)
    }

    @Test
    fun shouldContinueAssistScanning_trueDuringExtendedWaitWithoutReading() {
        val anchorMs = 1_000L
        val shouldContinue = AiDexRuntimePolicy.shouldContinueAssistScanning(
            stop = false,
            broadcastOnlyMode = false,
            phaseStreaming = true,
            hasRecentLiveData = false,
            pendingInitialHistoryRequest = false,
            historyDownloading = false,
            anchorMs = anchorMs,
            nowMs = anchorMs + 20L * 60_000L,
            lastGlucoseTimeMs = 0L,
            firstValidReadingWaitMaxMs = 60L * 60_000L,
        )

        assertTrue(shouldContinue)
    }

    @Test
    fun firstValidReadingWaitStatus_reportsWarmupExtendedAndNoValidData() {
        val anchorMs = 1_000L
        val warmupMs = 7L * 60_000L
        val maxWaitMs = 60L * 60_000L

        assertEquals(
            "age=2m warmup=5m",
            AiDexRuntimePolicy.firstValidReadingWaitStatus(
                anchorMs = anchorMs,
                nowMs = anchorMs + 2L * 60_000L,
                warmupDurationMs = warmupMs,
                firstValidReadingWaitMaxMs = maxWaitMs,
            )
        )
        assertEquals(
            "age=10m extended=50m",
            AiDexRuntimePolicy.firstValidReadingWaitStatus(
                anchorMs = anchorMs,
                nowMs = anchorMs + 10L * 60_000L,
                warmupDurationMs = warmupMs,
                firstValidReadingWaitMaxMs = maxWaitMs,
            )
        )
        assertEquals(
            "age=61m no-valid-data",
            AiDexRuntimePolicy.firstValidReadingWaitStatus(
                anchorMs = anchorMs,
                nowMs = anchorMs + 61L * 60_000L,
                warmupDurationMs = warmupMs,
                firstValidReadingWaitMaxMs = maxWaitMs,
            )
        )
    }

    @Test
    fun connectedWarmupStatus_hidesWarmupOnceValidReadingExists() {
        val anchorMs = 1_000L
        val warmupMs = 7L * 60_000L
        val status = AiDexRuntimePolicy.connectedWarmupStatus(
            connectionPart = "Connected",
            anchorMs = anchorMs,
            nowMs = anchorMs + 2L * 60_000L,
            lastGlucoseTimeMs = anchorMs + 90_000L,
            warmupDurationMs = warmupMs,
            firstValidReadingWaitMaxMs = 60L * 60_000L,
            firstValidReadingWaitActive = true,
        )

        assertNull(status)
    }

    @Test
    fun connectedWarmupStatus_reportsWarmupUntilFirstValidReading() {
        val anchorMs = 1_000L
        val warmupMs = 7L * 60_000L
        val status = AiDexRuntimePolicy.connectedWarmupStatus(
            connectionPart = "Connected",
            anchorMs = anchorMs,
            nowMs = anchorMs + 2L * 60_000L,
            lastGlucoseTimeMs = 0L,
            warmupDurationMs = warmupMs,
            firstValidReadingWaitMaxMs = 60L * 60_000L,
            firstValidReadingWaitActive = true,
        )

        assertEquals("Connected — Warmup 5m", status)
    }

    @Test
    fun shouldStartHistoryImmediately_onlyWhenPendingAndNotDownloading() {
        assertTrue(
            AiDexRuntimePolicy.shouldStartHistoryImmediately(
                pendingInitialHistoryRequest = true,
                historyDownloading = false,
            )
        )
        assertFalse(
            AiDexRuntimePolicy.shouldStartHistoryImmediately(
                pendingInitialHistoryRequest = false,
                historyDownloading = false,
            )
        )
        assertFalse(
            AiDexRuntimePolicy.shouldStartHistoryImmediately(
                pendingInitialHistoryRequest = true,
                historyDownloading = true,
            )
        )
    }

    @Test
    fun shouldAcceptBroadcastFallback_trueWhileWaitingForFirstDirectLive() {
        assertTrue(
            AiDexRuntimePolicy.shouldAcceptBroadcastFallback(
                broadcastOnlyMode = false,
                waitingForFirstDirectLive = true,
                hadRecentLiveDataBeforeBroadcast = true,
            )
        )
    }

    @Test
    fun shouldAcceptBroadcastFallback_falseWhenDirectLiveIsAlreadyHealthy() {
        assertFalse(
            AiDexRuntimePolicy.shouldAcceptBroadcastFallback(
                broadcastOnlyMode = false,
                waitingForFirstDirectLive = false,
                hadRecentLiveDataBeforeBroadcast = true,
            )
        )
    }

    @Test
    fun shouldContinueBroadcastScanning_trueForNoDirectLiveFallbackMode() {
        assertTrue(
            AiDexRuntimePolicy.shouldContinueBroadcastScanning(
                broadcastOnlyMode = false,
                noDirectLiveBroadcastFallbackMode = true,
            )
        )
        assertTrue(
            AiDexRuntimePolicy.shouldContinueBroadcastScanning(
                broadcastOnlyMode = true,
                noDirectLiveBroadcastFallbackMode = false,
            )
        )
        assertFalse(
            AiDexRuntimePolicy.shouldContinueBroadcastScanning(
                broadcastOnlyMode = false,
                noDirectLiveBroadcastFallbackMode = false,
            )
        )
    }

    @Test
    fun shouldRecoverFromSetupStall_forBondedCccdChainAfterTimeout() {
        assertTrue(
            AiDexRuntimePolicy.shouldRecoverFromSetupStall(
                phase = AiDexBleManager.Phase.CCCD_CHAIN,
                phaseAgeMs = 25_000L,
                bondState = BluetoothDevice.BOND_BONDED,
                keyExchangePendingBond = false,
                setupTimeoutMs = 25_000L,
                bondingTimeoutMs = 35_000L,
            )
        )
    }

    @Test
    fun shouldRecoverFromSetupStall_waitsLongerWhileBonding() {
        assertFalse(
            AiDexRuntimePolicy.shouldRecoverFromSetupStall(
                phase = AiDexBleManager.Phase.CCCD_CHAIN,
                phaseAgeMs = 25_000L,
                bondState = BluetoothDevice.BOND_BONDING,
                keyExchangePendingBond = true,
                setupTimeoutMs = 25_000L,
                bondingTimeoutMs = 35_000L,
            )
        )
    }

    @Test
    fun shouldRecoverFromPreAuthEncryptedTraffic_whenBondedTrafficPersistsBeforeAuth() {
        assertTrue(
            AiDexRuntimePolicy.shouldRecoverFromPreAuthEncryptedTraffic(
                phase = AiDexBleManager.Phase.CCCD_CHAIN,
                bondState = BluetoothDevice.BOND_BONDED,
                keyExchangePendingBond = false,
                encryptedFrameCount = 4,
                firstEncryptedFrameAtMs = 10_000L,
                nowMs = 15_500L,
                minFrames = 3,
                timeoutMs = 5_000L,
            )
        )
    }

    @Test
    fun shouldRecoverFromPreAuthEncryptedTraffic_ignoresBondingTraffic() {
        assertFalse(
            AiDexRuntimePolicy.shouldRecoverFromPreAuthEncryptedTraffic(
                phase = AiDexBleManager.Phase.CCCD_CHAIN,
                bondState = BluetoothDevice.BOND_BONDING,
                keyExchangePendingBond = true,
                encryptedFrameCount = 6,
                firstEncryptedFrameAtMs = 10_000L,
                nowMs = 20_000L,
                minFrames = 3,
                timeoutMs = 5_000L,
            )
        )
    }

    @Test
    fun decideInvalidSetupRecoveryAction_escalatesToBondResetAfterRepeatedBondedFailures() {
        assertEquals(
            AiDexRuntimePolicy.InvalidSetupRecoveryAction.RECONNECT,
            AiDexRuntimePolicy.decideInvalidSetupRecoveryAction(
                consecutiveRecoveries = 1,
                bondState = BluetoothDevice.BOND_BONDED,
                bondResetThreshold = 2,
            )
        )
        assertEquals(
            AiDexRuntimePolicy.InvalidSetupRecoveryAction.REMOVE_BOND_AND_RECONNECT,
            AiDexRuntimePolicy.decideInvalidSetupRecoveryAction(
                consecutiveRecoveries = 2,
                bondState = BluetoothDevice.BOND_BONDED,
                bondResetThreshold = 2,
            )
        )
    }
}
