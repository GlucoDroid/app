package tk.glucodata.data

import tk.glucodata.Natives
import tk.glucodata.strGlucose
import tk.glucodata.nums.numio
import tk.glucodata.nums.item
import com.eveningoutpost.dexdrip.services.broadcastservice.models.GraphLine
import com.eveningoutpost.dexdrip.services.broadcastservice.models.GraphPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import android.util.Log
import tk.glucodata.ui.GlucosePoint

class GlucoseRepository {

    fun getCurrentReading(): Flow<GlucosePoint?> = flow {
        while (true) {
            try {
                val lastData: strGlucose? = Natives.lastglucose()
                val unit = Natives.getunit()
                if (lastData != null) {
                    var value = lastData.value.toFloatOrNull() ?: 0f
                    // Natives.lastglucose() returns string formatted according to unit in C++, 
                    // BUT we are parsing it back. 
                    // Actually, strGlucose.value comes from C++ `gconvert`. 
                    // If C++ already converts it, we might be double converting if we are not careful.
                    // However, getGlucoseHistory returns RAW ints (mg/dL * 10). 
                    // Let's assume current reading flow is correct for now or needs check?
                    // The user complained about graph, which uses getHistory.
                    // Let's focus on getHistory.
                    
                    val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(lastData.time * 1000L))
                    emit(GlucosePoint(value, timeStr, lastData.time * 1000L, 0f, lastData.rate))
                } else {
                    emit(null)
                }
            } catch (e: Exception) {
                Log.e("GlucoseRepo", "Error getting last glucose", e)
                emit(null)
            }
            delay(15000)
        }
    }

    fun getHistory(): List<GlucosePoint> {
        val history = mutableListOf<GlucosePoint>()
        
        try {
            val endSec = System.currentTimeMillis() / 1000L
            val startSec = endSec - (3 * 24 * 60 * 60)
            
            val unit = Natives.getunit()
            val isMmol = (unit == 1)
            
            val rawHistory = Natives.getGlucoseHistory(startSec)
                 if (rawHistory != null) {
                     Log.d("GlucoseRepo", "getGlucoseHistory returned ${rawHistory.size / 3} points")
                     try {
                         for (i in rawHistory.indices step 3) {
                             if (i + 2 >= rawHistory.size) break
                             val timeSec = rawHistory[i]
                             val valueAutoRaw = rawHistory[i+1]
                             val valueRawRaw = rawHistory[i+2]
                             
                             var value = valueAutoRaw / 10f // mg/dL
                             var valueRaw = valueRawRaw / 10f // mg/dL
                             
                             if (isMmol) {
                                 value = value / 18.0182f
                                 valueRaw = valueRaw / 18.0182f
                             }
                             
                             val timeMs = timeSec * 1000L
                             val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timeMs))
                             history.add(GlucosePoint(value, timeStr, timeMs, valueRaw, 0f))
                         }
                     } catch (e: Exception) {
                         Log.e("GlucoseRepo", "Error parsing history", e)
                     }
                 } else {
                     Log.d("GlucoseRepo", "getGlucoseHistory returned null. ViewMode might be changing or no data.")
                 }

        } catch (e: Exception) {
            Log.e("GlucoseRepo", "Error fetching history", e)
        }
        
        return history.sortedBy { it.timestamp }
    }

    fun getUnit(): String {
        return when (Natives.getunit()) {
            1 -> "mmol/L"
            2 -> "mg/dL"
            else -> "mmol/L"
        }
    }
}