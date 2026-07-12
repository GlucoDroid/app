package tk.glucodata.drivers.sibionics

internal data class SibionicsRebuiltReading(
    val sampleMs: Long,
    val glucoseMgdl: Float,
    val rawMgdl: Float,
    val temperatureC: Float,
    val impedance: Float,
    val index: Int,
)

internal data class SibionicsReplayResult(
    val context: SibionicsAlgorithmContext,
    val readings: List<SibionicsRebuiltReading>,
    val sourceSamples: List<SibionicsSourceSample>,
)

internal object SibionicsAlgorithmRebuilder {
    fun isContiguousFromSensorStart(samples: List<SibionicsSourceSample>): Boolean {
        if (samples.isEmpty() || samples.first().index !in 0..1) return false
        return samples.zipWithNext().all { (before, after) -> after.index == before.index + 1 }
    }

    fun rebuild(
        sensorId: String,
        sourceSamples: List<SibionicsSourceSample>,
        selection: SibionicsAlgorithmSelection,
        variant: SibionicsConstants.Variant,
        shortCode: String,
        sensitivity: Float,
        unitIsMmol: Boolean,
        calibrateDisplaySeries: (FloatArray, LongArray) -> FloatArray,
    ): SibionicsReplayResult {
        val stockContext = SibionicsAlgorithmContext(sensorId).also {
            it.configure(shortCode, sensitivity, variant, SibionicsAlgorithmSelection.STOCK)
        }
        val validSources = ArrayList<SibionicsSourceSample>(sourceSamples.size)
        val stockMmol = ArrayList<Float>(sourceSamples.size)
        sourceSamples.forEach { sample ->
            val stock = stockContext.processStock(
                rawMmol = sample.rawMmol,
                temperatureC = sample.temperatureC,
                index = sample.index,
                mode = SibionicsAlgorithmMode.REPLAY,
            )
            val stockMgdl = stock * SibionicsConstants.MGDL_PER_MMOLL
            if (stock.isFinite() && stock > 0f && SibionicsConstants.isValidAlgorithmGlucoseMgdl(stockMgdl)) {
                validSources += sample
                stockMmol += stock
            }
        }
        if (validSources.isEmpty()) {
            return SibionicsReplayResult(stockContext, emptyList(), sourceSamples)
        }

        val displayStock = FloatArray(stockMmol.size) { index ->
            if (unitIsMmol) stockMmol[index] else stockMmol[index] * SibionicsConstants.MGDL_PER_MMOLL
        }
        val calibratedDisplay = if (selection.calibrationEnabled) {
            calibrateDisplaySeries(
                displayStock,
                LongArray(validSources.size) { validSources[it].timestampMs },
            ).takeIf { it.size == displayStock.size } ?: displayStock
        } else {
            displayStock
        }

        val rebuiltContext = SibionicsAlgorithmContext(sensorId).also {
            it.configure(shortCode, sensitivity, variant, selection)
            check(it.restore(stockContext.snapshot())) { "could not transfer exact algorithm state" }
        }
        val readings = ArrayList<SibionicsRebuiltReading>(validSources.size)
        validSources.forEachIndexed { index, sample ->
            val calibrated = calibratedDisplay[index]
            val preparedMmol = if (calibrated.isFinite() && calibrated > 0f) {
                if (unitIsMmol) calibrated else calibrated / SibionicsConstants.MGDL_PER_MMOLL
            } else {
                stockMmol[index]
            }
            val displayMmol = rebuiltContext.processPreparedMeasurement(
                stockMmol = stockMmol[index],
                measurementMmol = preparedMmol,
                rawMmol = sample.rawMmol,
                temperatureC = sample.temperatureC,
                index = sample.index,
                impedance = sample.impedance,
                eventTimeMs = sample.timestampMs,
            )
            val displayMgdl = displayMmol * SibionicsConstants.MGDL_PER_MMOLL
            if (SibionicsConstants.isValidAlgorithmGlucoseMgdl(displayMgdl)) {
                readings += SibionicsRebuiltReading(
                    sampleMs = sample.timestampMs,
                    glucoseMgdl = displayMgdl,
                    rawMgdl = sample.rawMmol * SibionicsConstants.MGDL_PER_MMOLL,
                    temperatureC = sample.temperatureC,
                    impedance = sample.impedance,
                    index = sample.index,
                )
            }
        }
        return SibionicsReplayResult(rebuiltContext, readings, sourceSamples)
    }
}
