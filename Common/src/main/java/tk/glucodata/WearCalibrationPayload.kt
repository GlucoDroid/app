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
) {
    companion object {
        private const val VERSION = 1
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
                    (if (payload.hideInitialWhenCalibrated) FLAG_HIDE_INITIAL else 0)
            val buffer = ByteBuffer.allocate(
                1 + 1 + 1 + serialBytes.size + 8 + 1 + autoCount * BYTES_PER_ANCHOR +
                    1 + rawCount * BYTES_PER_ANCHOR,
            )
            buffer.put(VERSION.toByte())
            buffer.put(flags.toByte())
            buffer.put(serialBytes.size.toByte())
            buffer.put(serialBytes)
            buffer.putLong(payload.revision)
            putMode(buffer, payload.auto)
            putMode(buffer, payload.raw)
            return buffer.array()
        }

        fun decode(data: ByteArray?): WearCalibrationPayload? = runCatching {
            val buffer = ByteBuffer.wrap(data ?: return null)
            if (buffer.remaining() < 12 || buffer.get().toInt() != VERSION) return null
            val flags = buffer.get().toInt() and 0xFF
            val serialLength = buffer.get().toInt() and 0xFF
            if (serialLength == 0 || buffer.remaining() < serialLength + 10) return null
            val serialBytes = ByteArray(serialLength)
            buffer.get(serialBytes)
            val sensorId = String(serialBytes, Charsets.UTF_8)
            val revision = buffer.long
            val auto = getMode(buffer) ?: return null
            val raw = getMode(buffer) ?: return null
            if (buffer.hasRemaining()) return null
            WearCalibrationPayload(
                sensorId = sensorId,
                revision = revision,
                valuesPrecalibrated = flags and FLAG_VALUES_PRECALIBRATED != 0,
                hideInitialWhenCalibrated = flags and FLAG_HIDE_INITIAL != 0,
                auto = auto,
                raw = raw,
            )
        }.getOrNull()

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
