package tk.glucodata.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import android.widget.TextView
import tk.glucodata.Applic
import tk.glucodata.GlucosePoint
import tk.glucodata.Natives
import tk.glucodata.NotificationChartDrawer
import tk.glucodata.Notify
import tk.glucodata.R
import tk.glucodata.SensorBluetooth
import tk.glucodata.SuperGattCallback
import tk.glucodata.strGlucose
import java.util.Locale
import kotlin.random.Random

class AODOverlayService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())

    // State for AOD behavior
    private var isScreenOn = true
    private var isLocked = false
    
    // Burn-in protection state
    private var xOffset = 0
    private var yOffset = 0

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    updateVisibility()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    updateVisibility()
                }
                Intent.ACTION_USER_PRESENT -> {
                    isLocked = false
                    updateVisibility()
                }
            }
        }
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (overlayView?.visibility == View.VISIBLE) {
                updateOverlayContent()
                applyBurnInProtection()
            }
            handler.postDelayed(this, 60000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlay()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, filter)
        
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        isLocked = keyguardManager.isKeyguardLocked
        updateVisibility()
    }

    private fun createOverlay() {
        if (overlayView != null) return

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.aod_overlay, null)
        
        // Transparent background to show system AOD/Wallpaper
        overlayView?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT, // Only wrap content to allow positioning
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        )

        // Default to TOP based on typical xDrip usage, but user requested positioning.
        // Ideally we read this from SharedPreferences. For now, default to CENTER for visibility.
        // User asked to "copy placement settings" from xDrip (Top/Center/Bottom).
        // Let's implement a rudimentary check if we had settings.
        // Since we are refactoring, let's stick to gravity control.
        params?.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params?.y = 100 // Initial top margin

        windowManager?.addView(overlayView, params)
        handler.post(updateRunnable)
    }

    private fun updateVisibility() {
        val shouldShow = isLocked
        
        if (shouldShow) {
            if (overlayView?.visibility != View.VISIBLE) {
                overlayView?.visibility = View.VISIBLE
                updateOverlayContent()
                applyBurnInProtection()
            }
        } else {
            overlayView?.visibility = View.GONE
        }
    }
    
    private fun applyBurnInProtection() {
        val view = overlayView ?: return
        val p = params ?: return
        
        // Random usage to shift +/- 20 pixels
        val maxShift = 50
        val newX = Random.nextInt(-maxShift, maxShift)
        val newY = Random.nextInt(0, maxShift*2) // Only shift down from top
        
        val prefs = getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
        val opacity = prefs.getFloat("aod_opacity", 1.0f)
        val textScale = prefs.getFloat("aod_text_scale", 1.0f)
        val chartScale = prefs.getFloat("aod_chart_scale", 1.5f)
        
        // Read "Positions" Set (Multi-select)
        // Default to TOP if empty or missing
        val positions = prefs.getStringSet("aod_positions", setOf("TOP")) ?: setOf("TOP")
        val activePositions = if (positions.isNotEmpty()) positions.toList() else listOf("TOP")
        
        // Randomly pick one valid position from the Set
        val randomPosIndex = Random.nextInt(activePositions.size)
        val currentPosition = activePositions[randomPosIndex]

        // Alignment logic
        val alignment = prefs.getString("aod_alignment", "CENTER") ?: "CENTER"
        
        // Apply Opacity
        view.alpha = opacity
        
        // Apply Scaling
        val glucoseImg = view.findViewById<ImageView>(R.id.notification_glucose)
        val arrowImg = view.findViewById<ImageView>(R.id.notification_arrow)
        val chartImg = view.findViewById<ImageView>(R.id.notification_chart)
        val statusText = view.findViewById<TextView>(R.id.notification_status)

        // Text/Arrow scaling is now handled during bitmap generation/textSize setting
        // to ensure high resolution. We do not scale X/Y here.
        
        chartImg?.scaleX = chartScale
        chartImg?.scaleY = chartScale
        
        // Apply Alignment to Root Layout (LinearLayout in aod_overlay.xml)
        val rootLayout = view.findViewById<android.widget.LinearLayout>(R.id.aod_root)
        if (rootLayout != null) {
             var alignGravity = Gravity.CENTER_HORIZONTAL
             when(alignment) {
                 "LEFT" -> alignGravity = Gravity.START
                 "CENTER" -> alignGravity = Gravity.CENTER_HORIZONTAL
                 "RIGHT" -> alignGravity = Gravity.END
             }
             rootLayout.gravity = alignGravity
        }

        // Apply Vertical Position
        var baseGravity = Gravity.CENTER_HORIZONTAL // This is WindowManager gravity
        var baseY = 0
        
        when(currentPosition) {
            "TOP" -> {
                baseGravity = baseGravity or Gravity.TOP
                baseY = 100 // Status bar clearance
            }
            "CENTER" -> {
                baseGravity = baseGravity or Gravity.CENTER_VERTICAL
                baseY = 0
            }
            "BOTTOM" -> {
                baseGravity = baseGravity or Gravity.BOTTOM
                baseY = 100 // Nav bar clearance
            }
        }
        
        p.gravity = baseGravity
        p.x = newX
        p.y = baseY + newY
        
        try {
            windowManager?.updateViewLayout(view, p)
        } catch (e: Exception) {}
    }

    private fun updateOverlayContent() {
        val view = overlayView ?: return

        // 1. Fetch Data
        val endT = System.currentTimeMillis()
        val startT = endT - 3 * 60 * 60 * 1000L
        val isMmol = Applic.unit == 1

        var chartPoints: List<GlucosePoint>
        try {
            chartPoints = tk.glucodata.data.HistoryRepository.getHistoryForNotification(startT, isMmol)
        } catch (e: Exception) {
            chartPoints = ArrayList()
        }

        // ViewMode & Status
        var viewMode = 0
        val activeSensorSerial = Natives.lastsensorname()
        var statusText = ""

        if (activeSensorSerial != null && SensorBluetooth.blueone != null) {
            synchronized(SensorBluetooth.gattcallbacks) {
                for (cb in SensorBluetooth.gattcallbacks) {
                    if (cb.SerialNumber != null && cb.SerialNumber == activeSensorSerial) {
                        statusText = cb.constatstatusstr
                        viewMode = Natives.getViewMode(cb.dataptr)
                        break
                    }
                }
            }
        }

        // Current Value
        val last: strGlucose? = Natives.lastglucose()
        var glvalue = 0f
        var valStr = "---"
        var rate = 0f
        var time = 0L

        if (last != null && last.value != null) {
            try {
                glvalue = last.value.toFloat()
            } catch (e: Exception) {}
            rate = last.rate
            time = last.time

            val formatted = Notify.formatGlucoseText(last.value, glvalue, chartPoints, viewMode, time)
            valStr = formatted.toString()
        } else if (chartPoints.isNotEmpty()) {
            val p = chartPoints[chartPoints.size - 1]
            glvalue = p.value
            try {
                valStr = String.format(Locale.US, "%.1f", glvalue)
            } catch (e: Exception) {}
        }

        val glucoseColor = NotificationChartDrawer.getGlucoseColor(this, glvalue, isMmol)

        // Draw Components
        // Fetch Font Preferences
        // Fetch Font Preferences
        val prefs = getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
        val fontSource = prefs.getString("aod_font_source", "APP") ?: "APP"
        val fontWeight = prefs.getInt("aod_font_weight", 400)
        val textScale = prefs.getFloat("aod_text_scale", 1.5f)
        val useSystemFont = fontSource == "SYSTEM"
        
        // Arrow Settings
        val showArrow = prefs.getBoolean("aod_show_arrow", true)
        val arrowScale = prefs.getFloat("aod_arrow_scale", 1.0f)

        // Use textScale here for high-res bitmap generation
        val textBitmap = NotificationChartDrawer.drawGlucoseText(this, valStr, glucoseColor, textScale, fontWeight, useSystemFont)
        val textImg = view.findViewById<ImageView>(R.id.notification_glucose)
        textImg?.setImageBitmap(textBitmap)

        // Pass combined scale to arrow
        val arrowImg = view.findViewById<ImageView>(R.id.notification_arrow)
        if (showArrow) {
            val arrowBitmap = NotificationChartDrawer.drawArrow(this, rate, isMmol, glucoseColor, textScale * arrowScale)
            arrowImg?.setImageBitmap(arrowBitmap)
            arrowImg?.visibility = View.VISIBLE
        } else {
            arrowImg?.visibility = View.GONE
        }

// CHART VISIBILITY CHECK
        // prefs already defined above
        val showChart = prefs.getBoolean("aod_show_chart", true)
        val chartImg = view.findViewById<ImageView>(R.id.notification_chart)

        if (showChart) {
            val dm = resources.displayMetrics
            val w = dm.widthPixels
            // Base height 200dp
            val h = (200 * dm.density).toInt()

            val chartBitmap = NotificationChartDrawer.drawChart(this, chartPoints, w, h, isMmol, viewMode)
            if (chartImg != null) {
                chartImg.setImageBitmap(chartBitmap)
                chartImg.visibility = View.VISIBLE
            }
        } else {
             chartImg?.visibility = View.GONE
        }

        val statusView = view.findViewById<TextView>(R.id.notification_status)
        if (statusView != null) {
            // Apply Scaling
            statusView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f * textScale)
            
            if (statusText != null && statusText.isNotEmpty()) {
                statusView.visibility = View.VISIBLE
                statusView.text = statusText
            } else {
                statusView.visibility = View.GONE
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
             val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
             val wasLocked = isLocked
             isLocked = keyguardManager.isKeyguardLocked
             if (wasLocked != isLocked) {
                 updateVisibility()
             }
        }
    }

    override fun onInterrupt() {
        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {}
            overlayView = null
        }
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {}
        handler.removeCallbacks(updateRunnable)
    }
}
