package com.example.tethr.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

object AdbDiscoveryManager {
    private const val TAG = "AdbDiscoveryManager"

    // The two services broadcasted by Android Wireless Debugging
    private const val SERVICE_TYPE_CONNECT = "_adb-tls-connect._tcp."
    private const val SERVICE_TYPE_PAIRING = "_adb-tls-pairing._tcp."

    var discoveredConnPort: Int? = null
        private set
    var discoveredPairPort: Int? = null
        private set

    private var nsdManager: NsdManager? = null
    private var connectListener: NsdManager.DiscoveryListener? = null
    private var pairingListener: NsdManager.DiscoveryListener? = null

    fun startDiscovery(context: Context) {
        if (nsdManager != null) return // Already running
        
        Log.d(TAG, "Starting NsdManager discovery for ADB services...")
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        connectListener = createListener(SERVICE_TYPE_CONNECT) { port ->
            Log.d(TAG, "Discovered Connection Port: $port")
            discoveredConnPort = port
        }

        pairingListener = createListener(SERVICE_TYPE_PAIRING) { port ->
            Log.d(TAG, "Discovered Pairing Port: $port")
            discoveredPairPort = port
        }

        try {
            nsdManager?.discoverServices(SERVICE_TYPE_CONNECT, NsdManager.PROTOCOL_DNS_SD, connectListener)
            nsdManager?.discoverServices(SERVICE_TYPE_PAIRING, NsdManager.PROTOCOL_DNS_SD, pairingListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
        }
    }

    fun stopDiscovery() {
        Log.d(TAG, "Stopping NsdManager discovery...")
        try {
            connectListener?.let { nsdManager?.stopServiceDiscovery(it) }
            pairingListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop discovery", e)
        }
        
        nsdManager = null
        connectListener = null
        pairingListener = null
    }

    private fun createListener(serviceType: String, onPortFound: (Int) -> Unit): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Service discovery started for $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${service.serviceName}")
                
                // We must resolve the service to get the actual port
                nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val port = serviceInfo.port
                        Log.d(TAG, "Resolved port for ${serviceInfo.serviceName}: $port")
                        onPortFound(port)
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.e(TAG, "Service lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed to start: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed to stop: $errorCode")
            }
        }
    }
}
