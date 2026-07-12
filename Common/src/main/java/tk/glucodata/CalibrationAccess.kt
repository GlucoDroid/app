package tk.glucodata

object CalibrationAccess {
    private const val CLASS_NAME = "tk.glucodata.data.calibration.CalibrationManager"

    private val holder by lazy { runCatching { Class.forName(CLASS_NAME) }.getOrNull() }
    private val instance by lazy { runCatching { holder?.getField("INSTANCE")?.get(null) }.getOrNull() }
    private val hasActiveCalibrationMethod by lazy {
        runCatching {
            holder?.getMethod("hasActiveCalibration", Boolean::class.javaPrimitiveType, String::class.java)
        }.getOrNull()
    }
    private val getCalibratedValueMethod by lazy {
        runCatching {
            holder?.getMethod(
                "getCalibratedValue",
                Float::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                String::class.java
            )
        }.getOrNull()
    }
    private val shouldHideInitialMethod by lazy {
        runCatching { holder?.getMethod("shouldHideInitialWhenCalibrated") }.getOrNull()
    }
    private val notifyExternalPipelineMethod by lazy {
        runCatching { holder?.getMethod("notifyExternalCalibrationPipelineChanged") }.getOrNull()
    }
    private val getActiveCalibrationAnchorsMethod by lazy {
        runCatching {
            holder?.getMethod(
                "getActiveCalibrationAnchors",
                String::class.java,
                Boolean::class.javaPrimitiveType,
            )
        }.getOrNull()
    }
    private val shouldOverwriteSensorValuesMethod by lazy {
        runCatching { holder?.getMethod("shouldOverwriteSensorValues") }.getOrNull()
    }
    private val getRevisionMethod by lazy {
        runCatching { holder?.getMethod("getRevision") }.getOrNull()
    }
    private val getIntegratedCalibratedSeriesMethod by lazy {
        runCatching {
            holder?.getMethod(
                "getIntegratedCalibratedSeries",
                FloatArray::class.java,
                LongArray::class.java,
                Boolean::class.javaPrimitiveType,
                String::class.java,
            )
        }.getOrNull()
    }
    private val getIntegratedCalibrationFingerprintMethod by lazy {
        runCatching {
            holder?.getMethod(
                "getIntegratedCalibrationFingerprint",
                String::class.java,
                Boolean::class.javaPrimitiveType,
            )
        }.getOrNull()
    }

    @JvmStatic
    fun hasActiveCalibration(isRawMode: Boolean, sensorId: String? = null): Boolean {
        return runCatching {
            hasActiveCalibrationMethod?.invoke(instance, isRawMode, sensorId) as? Boolean
        }.getOrNull() ?: false
    }

    @JvmStatic
    @JvmOverloads
    fun getCalibratedValue(
        value: Float,
        timestamp: Long,
        isRawMode: Boolean,
        emitDiagnostics: Boolean = false,
        sensorIdOverride: String? = null
    ): Float {
        return runCatching {
            getCalibratedValueMethod?.invoke(
                instance,
                value,
                timestamp,
                isRawMode,
                emitDiagnostics,
                sensorIdOverride
            ) as? Float
        }.getOrNull() ?: value
    }

    @JvmStatic
    fun shouldHideInitialWhenCalibrated(): Boolean {
        return runCatching {
            shouldHideInitialMethod?.invoke(instance) as? Boolean
        }.getOrNull() ?: false
    }

    @JvmStatic
    fun notifyExternalCalibrationPipelineChanged() {
        runCatching { notifyExternalPipelineMethod?.invoke(instance) }
    }

    @JvmStatic
    fun getActiveCalibrationAnchors(sensorId: String?, isRawMode: Boolean = false): DoubleArray {
        return runCatching {
            getActiveCalibrationAnchorsMethod?.invoke(instance, sensorId, isRawMode) as? DoubleArray
        }.getOrNull() ?: DoubleArray(0)
    }

    @JvmStatic
    fun shouldOverwriteSensorValues(): Boolean {
        return runCatching {
            shouldOverwriteSensorValuesMethod?.invoke(instance) as? Boolean
        }.getOrNull() ?: false
    }

    @JvmStatic
    fun getRevision(): Long {
        return runCatching {
            when (val value = getRevisionMethod?.invoke(instance)) {
                is Long -> value
                is Number -> value.toLong()
                else -> null
            }
        }.getOrNull() ?: 0L
    }

    @JvmStatic
    fun getIntegratedCalibratedSeries(
        values: FloatArray,
        timestamps: LongArray,
        isRawMode: Boolean,
        sensorIdOverride: String?,
    ): FloatArray {
        if (values.size != timestamps.size) return values.copyOf()
        return runCatching {
            getIntegratedCalibratedSeriesMethod?.invoke(
                instance,
                values,
                timestamps,
                isRawMode,
                sensorIdOverride,
            ) as? FloatArray
        }.getOrNull()?.takeIf { it.size == values.size } ?: values.copyOf()
    }

    @JvmStatic
    fun getIntegratedCalibrationFingerprint(sensorIdOverride: String?, isRawMode: Boolean): Long {
        return runCatching {
            when (val value = getIntegratedCalibrationFingerprintMethod?.invoke(
                instance,
                sensorIdOverride,
                isRawMode,
            )) {
                is Long -> value
                is Number -> value.toLong()
                else -> 0L
            }
        }.getOrDefault(0L)
    }
}
