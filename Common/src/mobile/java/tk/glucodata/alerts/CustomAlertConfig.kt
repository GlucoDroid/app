package tk.glucodata.alerts

import org.json.JSONObject
import java.util.UUID

enum class CustomAlertType {
    HIGH, LOW
}

data class CustomAlertConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val type: CustomAlertType = CustomAlertType.HIGH,
    val threshold: Float = 0f,
    val enabled: Boolean = true,
    
    val timeRangeEnabled: Boolean = true,
    
    // Time range in minutes from midnight (0-1439). 
    // e.g. 360 = 06:00.
    val startTimeMinutes: Int = 0,
    val endTimeMinutes: Int = 1440,
    
    // Standard Alert Features
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    val flash: Boolean = false,
    val style: String = "notification", // notification, alarm, both
    val intensity: String = "medium", // low, medium, high, ascending
    val overrideDnd: Boolean = false,
    val retryInterval: Int = 0,
    val retryCount: Int = 0,
    
    val snoozedUntil: Long = 0L,
    val soundUri: String? = null
) {
    fun isActiveTime(currentMinutes: Int): Boolean {
        if (!timeRangeEnabled) return true // Always active if time range disabled
        
        return if (startTimeMinutes <= endTimeMinutes) {
            currentMinutes in startTimeMinutes until endTimeMinutes
        } else {
            // Crosses midnight (e.g. 22:00 to 07:00)
            currentMinutes >= startTimeMinutes || currentMinutes < endTimeMinutes
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("type", type.name)
            put("threshold", threshold)
            put("enabled", enabled)
            
            put("timeRangeEnabled", timeRangeEnabled)
            put("startTimeMinutes", startTimeMinutes)
            put("endTimeMinutes", endTimeMinutes)
            
            put("sound", sound)
            put("vibrate", vibrate)
            put("flash", flash)
            put("style", style)
            put("intensity", intensity)
            put("overrideDnd", overrideDnd)
            put("retryInterval", retryInterval)
            put("retryCount", retryCount)
            
            put("snoozedUntil", snoozedUntil)
            put("soundUri", soundUri)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): CustomAlertConfig {
            return CustomAlertConfig(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", ""),
                type = CustomAlertType.valueOf(json.optString("type", "HIGH")),
                threshold = json.optDouble("threshold", 0.0).toFloat(),
                enabled = json.optBoolean("enabled", true),
                
                timeRangeEnabled = json.optBoolean("timeRangeEnabled", true),
                startTimeMinutes = json.optInt("startTimeMinutes", 0),
                endTimeMinutes = json.optInt("endTimeMinutes", 1440),
                
                sound = json.optBoolean("sound", true),
                vibrate = json.optBoolean("vibrate", true),
                flash = json.optBoolean("flash", false),
                style = json.optString("style", "notification"),
                intensity = json.optString("intensity", "medium"),
                overrideDnd = json.optBoolean("overrideDnd", false),
                retryInterval = json.optInt("retryInterval", 0),
                retryCount = json.optInt("retryCount", 0),
                
                snoozedUntil = json.optLong("snoozedUntil", 0L),
                soundUri = if (json.isNull("soundUri")) null else json.getString("soundUri")
            )
        }
    }
}
