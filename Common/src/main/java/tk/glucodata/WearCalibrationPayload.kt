package tk.glucodata

import java.nio.ByteBuffer

data class WearCalibrationMode(
    val anchorsMgdl: DoubleArray,
)

data class WearCalibrationPayload(
    val sensorId: String,
    val revision: Long,
    val valuesPrecalibrated: Boolean,
    val hideInitialWhenCalibrated: Boolean,
    val auto: WearCalibrationMode,
    val raw: WearCalibrationMode,
    /** The phone overwrites sensor values itself; the watch must not correct twice. */
    val overwriteSensorValues: Boolean = false,
    /**
     * The settings behind the phone's fit. Sent so the watch reproduces the same
     * numbers when it corrects readings it took over its own connection.
     */
    val tuning: tk.glucodata.data.calibration.CalibrationTuning =
        tk.glucodata.data.calibration.CalibrationTuning.DEFAULT,
) {
    companion object {
        private const val VERSION = 2
        private const val VERSION_WITHOUT_TUNING = 1
        private const val FLAG_OVERWRITE_SENSOR_VALUES = 1 shl 2
        private const val FLAG_VALUES_PRECALIBRATED = 1
        private const val FLAG_HIDE_INITIAL = 1 shl 1
        private const val MAX_SERIAL_BYTES = 255
        private const val MAX_ANCHORS_PER_MODE = 32
        private const val BYTES_PER_ANCHOR = 24

        fun encode(payload: WearCalibrationPayload): ByteArray {
            val serialBytes = payload.sensorId.toByteArray(Charsets.UTF_8)
            require(serialBytes.size <= MAX_SERIAL_BYTES)
            require(payload.auto.anchorsMgdl.size % 3 == 0)
            require(payload.raw.anchorsMgdl.size % 3 == 0)
            val autoCount = payload.auto.anchorsMgdl.size / 3
            val rawCount = payload.raw.anchorsMgdl.size / 3
            require(autoCount <= MAX_ANCHORS_PER_MODE)
            require(rawCount <= MAX_ANCHORS_PER_MODE)
            val flags =
                (if (payload.valuesPrecalibrated) FLAG_VALUES_PRECALIBRATED else 0) or
                    (if (payload.hideInitialWhenCalibrated) FLAG_HIDE_INITIAL else 0) or
                    (if (payload.overwriteSensorValues) FLAG_OVERWRITE_SENSOR_VALUES else 0)
            val algorithmBytes = payload.tuning.algorithm.toByteArray(Charsets.UTF_8)
            val weightBytes = payload.tuning.weightMode.toByteArray(Charsets.UTF_8)
            require(algorithmBytes.size <= MAX_SERIAL_BYTES)
            require(weightBytes.size <= MAX_SERIAL_BYTES)
            val buffer = ByteBuffer.allocate(
                1 + 1 + 1 + serialBytes.size + 8 + 1 + autoCount * BYTES_PER_ANCHOR +
                    1 + rawCount * BYTES_PER_ANCHOR +
                    1 + algorithmBytes.size + 1 + weightBytes.size + 1,
            )
            buffer.put(VERSION.toByte())
            buffer.put(flags.toByte())
            buffer.put(serialBytes.size.toByte())
            buffer.put(serialBytes)
            buffer.putLong(payload.revision)
            putMode(buffer, payload.auto)
            putMode(buffer, payload.raw)
            buffer.put(algorithmBytes.size.toByte())
            buffer.put(algorithmBytes)
            buffer.put(weightBytes.size.toByte())
            buffer.put(weightBytes)
            buffer.put(
                (
                    (if (payload.tuning.applyToPast) 1 else 0) or
                        (if (payload.tuning.lockPastHistory) 2 else 0) or
                        (if (payload.tuning.keepDisabledHistory) 4 else 0)
                    ).toByte(),
            )
            return buffer.array()
        }

        fun decode(data: ByteArray?): WearCalibrationPayload? = runCatching {
            val buffer = ByteBuffer.wrap(data ?: return null)
            if (buffer.remaining() < 12) return null
            val version = buffer.get().toInt()
            // A payload from before the settings were carried still describes the
            // anchors correctly; it just uses the documented defaults.
            if (version != VERSION && version != VERSION_WITHOUT_TUNING) return null
            val flags = buffer.get().toInt() and 0xFF
            val serialLength = buffer.get().toInt() and 0xFF
            if (serialLength == 0 || buffer.remaining() < serialLength + 10) return null
            val serialBytes = ByteArray(serialLength)
            buffer.get(serialBytes)
            val sensorId = String(serialBytes, Charsets.UTF_8)
            val revision = buffer.long
            val auto = getMode(buffer) ?: return null
            val raw = getMode(buffer) ?: return null
            val tuning = if (version >= VERSION) {
                val algorithm = getString(buffer) ?: return null
                val weightMode = getString(buffer) ?: return null
                if (buffer.remaining() < 1) return null
                val policyFlags = buffer.get().toInt()
                tk.glucodata.data.calibration.CalibrationTuning(
                    algorithm = algorithm,
                    weightMode = weightMode,
                    applyToPast = policyFlags and 1 != 0,
                    lockPastHistory = policyFlags and 2 != 0,
                    keepDisabledHistory = policyFlags and 4 != 0,
                )
            } else {
                tk.glucodata.data.calibration.CalibrationTuning.DEFAULT
            }
            if (buffer.hasRemaining()) return null
            WearCalibrationPayload(
                sensorId = sensorId,
                revision = revision,
                valuesPrecalibrated = flags and FLAG_VALUES_PRECALIBRATED != 0,
                hideInitialWhenCalibrated = flags and FLAG_HIDE_INITIAL != 0,
                overwriteSensorValues = flags and FLAG_OVERWRITE_SENSOR_VALUES != 0,
                tuning = tuning,
                auto = auto,
                raw = raw,
            )
        }.getOrNull()

        private fun getString(buffer: ByteBuffer): String? {
            if (buffer.remaining() < 1) return null
            val length = buffer.get().toInt() and 0xFF
            if (buffer.remaining() < length) return null
            val bytes = ByteArray(length)
            buffer.get(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        private fun putMode(buffer: ByteBuffer, mode: WearCalibrationMode) {
            buffer.put((mode.anchorsMgdl.size / 3).toByte())
            mode.anchorsMgdl.forEach { buffer.putDouble(it) }
        }

        private fun getMode(buffer: ByteBuffer): WearCalibrationMode? {
            if (!buffer.hasRemaining()) return null
            val count = buffer.get().toInt() and 0xFF
            if (count > MAX_ANCHORS_PER_MODE || buffer.remaining() < count * BYTES_PER_ANCHOR) {
                return null
            }
            return WearCalibrationMode(DoubleArray(count * 3) { buffer.double })
        }
    }
}
