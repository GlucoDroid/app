package tk.glucodata.drivers.sibionics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.math.max
import tk.glucodata.drivers.sibionics.v116a.SibionicsExactV116ACore

enum class SibionicsAlgorithmMode {
    LIVE,
    REPLAY,
}

/**
 * Driver-facing Sibionics algorithm.
 *
 * Every valid one-minute packet produces a value. The exact core advances for
 * every packet and periodically refreshes the algorithm-to-raw correction;
 * packets between those refreshes use the latest correction, matching the
 * legacy driver cadence.
 */
class SibionicsAlgorithmContext(
    @Suppress("unused") private val sensorId: String,
) {
    private var family = AlgorithmFamily.V115G
    private val v115Core = SibionicsExactV115GCore()
    private val v116Core = SibionicsExactV116ACore()
    private var liveDeltaMmol = Float.NaN
    private var replayDeltaMmol = Float.NaN

    fun configure(
        @Suppress("unused") shortCode: String,
        sensitivity: Float,
        variant: SibionicsConstants.Variant = SibionicsConstants.Variant.CHINESE,
    ) {
        val configuredFamily = if (variant.usesV116AAlgorithm) {
            AlgorithmFamily.V116A
        } else {
            AlgorithmFamily.V115G
        }
        if (configuredFamily != family) {
            family = configuredFamily
            resetWrapperState()
        }
        when (family) {
            AlgorithmFamily.V115G -> v115Core.configure(sensitivity)
            AlgorithmFamily.V116A -> v116Core.configure(sensitivity)
        }
    }

    fun reset() {
        v115Core.reset()
        v116Core.reset()
        resetWrapperState()
    }

    fun process(
        rawMmol: Float,
        temperatureC: Float,
        index: Int,
        mode: SibionicsAlgorithmMode,
    ): Float {
        if (!rawMmol.isFinite() || rawMmol <= 0f) return Float.NaN

        val candidate = when (family) {
            AlgorithmFamily.V115G -> v115Core.process(rawMmol, temperatureC, index)
            AlgorithmFamily.V116A -> v116Core.process(rawMmol, temperatureC, index)
        }
        val display = when (mode) {
            SibionicsAlgorithmMode.LIVE -> liveValue(rawMmol, candidate)
            SibionicsAlgorithmMode.REPLAY -> replayValue(rawMmol, candidate)
        }

        if (!display.isFinite() || display > MAX_VALID_MMOL) {
            clearDelta(mode)
            return nativeRound(rawMmol)
        }

        return max(display, 0f)
    }

    fun snapshot(): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(SNAPSHOT_MAGIC)
            output.writeInt(SNAPSHOT_VERSION)
            output.writeInt(family.snapshotId)
            output.writeFloat(liveDeltaMmol)
            output.writeFloat(replayDeltaMmol)
            val coreState = when (family) {
                AlgorithmFamily.V115G -> v115Core.snapshot()
                AlgorithmFamily.V116A -> v116Core.snapshot()
            }
            output.writeInt(coreState.size)
            output.write(coreState)
        }
        bytes.toByteArray()
    }

    fun restore(snapshot: ByteArray?): Boolean {
        if (snapshot == null || snapshot.isEmpty()) return false

        val restored = runCatching {
            DataInputStream(ByteArrayInputStream(snapshot)).use { input ->
                val magic = input.readInt()
                if (magic != SNAPSHOT_MAGIC) return@use restoreLegacyCoreSnapshot(snapshot)
                when (input.readInt()) {
                    SNAPSHOT_VERSION -> if (input.readInt() != family.snapshotId) return@use false
                    LEGACY_WRAPPER_SNAPSHOT_VERSION -> if (family != AlgorithmFamily.V115G) return@use false
                    else -> return@use false
                }

                val savedLiveDelta = input.readFloat()
                val savedReplayDelta = input.readFloat()
                val coreSize = input.readInt()
                if (!isStoredDeltaValid(savedLiveDelta) || !isStoredDeltaValid(savedReplayDelta)) return@use false
                if (coreSize !in 1..MAX_CORE_SNAPSHOT_BYTES) return@use false

                val coreState = ByteArray(coreSize)
                input.readFully(coreState)
                if (input.available() != 0 || !restoreCore(coreState)) return@use false

                liveDeltaMmol = savedLiveDelta
                replayDeltaMmol = savedReplayDelta
                true
            }
        }.getOrDefault(false)

        if (!restored) reset()
        return restored
    }

    private fun liveValue(rawMmol: Float, candidate: Float?): Float {
        if (candidate != null && isUsableCandidate(candidate)) {
            liveDeltaMmol = candidate - rawMmol
            return candidate
        }

        val delta = when {
            isUsableDelta(liveDeltaMmol) -> liveDeltaMmol
            isUsableDelta(replayDeltaMmol) -> replayDeltaMmol
            else -> Float.NaN
        }
        if (!delta.isFinite()) return nativeRound(rawMmol)
        liveDeltaMmol = delta
        return nativeRound(rawMmol + delta)
    }

    private fun replayValue(rawMmol: Float, candidate: Float?): Float {
        if (candidate != null && isUsableCandidate(candidate)) {
            replayDeltaMmol = candidate - rawMmol
            return candidate
        }
        return if (isUsableDelta(replayDeltaMmol)) {
            nativeRound(rawMmol + replayDeltaMmol)
        } else {
            nativeRound(rawMmol)
        }
    }

    private fun restoreLegacyCoreSnapshot(snapshot: ByteArray): Boolean {
        if (!restoreCore(snapshot)) return false
        resetWrapperState()
        return true
    }

    private fun restoreCore(snapshot: ByteArray): Boolean = when (family) {
        AlgorithmFamily.V115G -> v115Core.restore(snapshot)
        AlgorithmFamily.V116A -> v116Core.restore(snapshot)
    }

    private fun clearDelta(mode: SibionicsAlgorithmMode) {
        when (mode) {
            SibionicsAlgorithmMode.LIVE -> liveDeltaMmol = Float.NaN
            SibionicsAlgorithmMode.REPLAY -> replayDeltaMmol = Float.NaN
        }
    }

    private fun resetWrapperState() {
        liveDeltaMmol = Float.NaN
        replayDeltaMmol = Float.NaN
    }

    private fun isUsableCandidate(candidate: Float): Boolean =
        candidate.isFinite() && candidate > MIN_ALGORITHM_MMOL && candidate <= MAX_VALID_MMOL

    private fun isUsableDelta(delta: Float): Boolean = delta.isFinite() && abs(delta) < MAX_DELTA_MMOL

    private fun isStoredDeltaValid(delta: Float): Boolean = delta.isNaN() || isUsableDelta(delta)

    private fun nativeRound(value: Float): Float {
        if (!value.isFinite()) return Float.NaN
        val scaled = value * 10f
        val rounded = if (value >= 0f) scaled + 0.5f else scaled
        return rounded.toInt() / 10f
    }

    private companion object {
        private const val SNAPSHOT_MAGIC = 0x5349_4234
        private const val SNAPSHOT_VERSION = 3
        private const val LEGACY_WRAPPER_SNAPSHOT_VERSION = 2
        private const val MAX_CORE_SNAPSHOT_BYTES = 64 * 1024
        private const val MIN_ALGORITHM_MMOL = 1f
        private const val MAX_VALID_MMOL = SibionicsConstants.MAX_ALGORITHM_GLUCOSE_MMOL
        private const val MAX_DELTA_MMOL = 40f
    }

    private enum class AlgorithmFamily(val snapshotId: Int) {
        V115G(115),
        V116A(116),
    }
}
