package tk.glucodata

import tk.glucodata.drivers.ManagedSensorRuntime

object NotificationMultiSensorSource {
    @JvmStatic
    fun selectedSensorIds(primarySensorId: String?): List<String> {
        val candidates = ArrayList<String?>()
        candidates.add(primarySensorId)
        runCatching { Natives.activeSensors()?.forEach { candidates.add(it) } }
        runCatching {
            SensorBluetooth.mygatts()?.forEach { callback ->
                candidates.add(callback.SerialNumber)
            }
        }
        return MultiSensorSelection.selectedAvailable(candidates, primarySensorId)
    }

    @JvmStatic
    fun peerSeries(
        startTimeMs: Long,
        isMmol: Boolean,
        primarySensorId: String?
    ): List<NotificationChartDrawer.PeerSeries> {
        val selected = selectedSensorIds(primarySensorId)
        if (selected.size <= 1) return emptyList()
        return selected.drop(1).mapNotNull { sensorId ->
            val current = CurrentDisplaySource.resolveCurrent(
                Notify.glucosetimeout,
                sensorId,
                DisplayTrendSource.TREND_WINDOW_MS
            )
            val history = runCatching {
                NotificationHistorySource.getDisplayHistory(startTimeMs, isMmol, sensorId)
            }.getOrDefault(emptyList())
                .let { DisplayTrendSource.augmentHistory(it, current, sensorId, startTimeMs) }
            if (history.size < 2) {
                null
            } else {
                NotificationChartDrawer.PeerSeries(
                    sensorId,
                    resolveViewMode(sensorId),
                    SensorVisuals.colorArgb(sensorId),
                    history
                )
            }
        }
    }

    @JvmStatic
    fun peerValueItems(maxAgeMillis: Long, primarySensorId: String?): List<NotificationChartDrawer.ValueItem> {
        val selected = selectedSensorIds(primarySensorId)
        if (selected.size <= 1) return emptyList()
        return selected.drop(1).mapNotNull { sensorId ->
            val snapshot = CurrentDisplaySource.resolveCurrent(
                maxAgeMillis,
                sensorId,
                DisplayTrendSource.TREND_WINDOW_MS
            ) ?: return@mapNotNull null
            NotificationChartDrawer.ValueItem(
                snapshot.fullFormatted,
                SensorVisuals.colorArgb(sensorId)
            )
        }
    }

    @JvmStatic
    fun resolveViewMode(sensorId: String?): Int {
        if (sensorId.isNullOrBlank()) return 0
        ManagedSensorRuntime.resolveUiSnapshot(sensorId, sensorId)?.viewMode?.let { return it }
        runCatching {
            SensorBluetooth.mygatts()?.forEach { callback ->
                if (SensorIdentity.matches(callback.SerialNumber, sensorId) && callback.dataptr != 0L) {
                    return Natives.getViewMode(callback.dataptr)
                }
            }
        }
        return 0
    }
}
