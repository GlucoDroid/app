package tk.glucodata.util

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log

data class DiscoveredMirror(
    val name: String,
    val ip: String,
    val port: Int,
    val password: String = "",
    val label: String = ""
)

class MDnsManager(private val context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serviceType = "_jugglucong._tcp."
    
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    
    /**
     * Register this device as a discoverable Juggluco mirror on the local network.
     * Embeds the connection password and label in TXT records so the
     * discovering device can create a matching connection entry.
     */
    fun registerService(deviceName: String, port: Int = 8795, password: String = "", label: String = "") {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "JugglucoNG-$deviceName"
            this.serviceType = this@MDnsManager.serviceType
            this.port = port
            // Embed connection params in TXT records (local network only)
            if (password.isNotEmpty()) setAttribute("pw", password)
            if (label.isNotEmpty()) setAttribute("lbl", label)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                Log.d("MDNS", "Service registered: ${NsdServiceInfo.serviceName}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("MDNS", "Registration failed: $errorCode")
            }
            override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e("MDNS", "Register error", e)
        }
    }

    fun unregisterService() {
        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
            registrationListener = null
        } catch (e: Exception) {}
    }

    // Scanning (Follower side)
    fun discoverServices(onServiceFound: (DiscoveredMirror) -> Unit) {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d("MDNS", "Service discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType == serviceType || service.serviceType == "$serviceType.") {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val hostAddress = serviceInfo.host?.hostAddress ?: return
                            // Read TXT record attributes
                            val pw = serviceInfo.attributes["pw"]?.let { String(it) } ?: ""
                            val lbl = serviceInfo.attributes["lbl"]?.let { String(it) } ?: ""
                            val mirror = DiscoveredMirror(
                                name = serviceInfo.serviceName.removePrefix("JugglucoNG-"),
                                ip = hostAddress,
                                port = serviceInfo.port,
                                password = pw,
                                label = lbl
                            )
                            Handler(Looper.getMainLooper()).post {
                                onServiceFound(mirror)
                            }
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }
        }

        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e("MDNS", "Discover error", e)
        }
    }

    fun stopDiscovery() {
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
            discoveryListener = null
        } catch (e: Exception) {}
    }
}
