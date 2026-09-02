package com.example.itantra.transport.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import com.example.itantra.util.Logger

class WifiDirectTransport(
    private val context: Context,
    private val onLocalDeviceAvailable: (WifiP2pDevice) -> Unit = {},
    private val onConnectionInfoAvailable: (WifiP2pInfo) -> Unit
) {

    private val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? = manager?.initialize(context, context.mainLooper, null)


    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Logger.d("Wi-Fi Direct State: ${if (isEnabled) "ENABLED" else "DISABLED"}")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Logger.d("Wi-Fi Direct Peers Changed")
                    // In Phase 1, we rely on BLE for discovery, but we can still list peers if needed
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true) {
                        Logger.d("Wi-Fi Direct Connected. Requesting connection info...")
                        manager?.requestConnectionInfo(channel) { info ->
                            Logger.d("Connection Info: GroupOwner=${info.isGroupOwner}, Address=${info.groupOwnerAddress?.hostAddress}")
                            onConnectionInfoAvailable(info)
                        }
                    } else {
                        Logger.d("Wi-Fi Direct Disconnected")
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    Logger.d("Wi-Fi Direct Local Device: ${device?.deviceName} [${device?.deviceAddress}]")
                    device?.let { onLocalDeviceAvailable(it) }
                }
            }
        }
    }

    fun register() {
        context.registerReceiver(receiver, intentFilter)
        requestDeviceInfo()
    }

    @SuppressLint("MissingPermission")
    private fun requestDeviceInfo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            channel?.let {
                manager?.requestDeviceInfo(it) { device ->
                    Logger.d("Manual Device Info: ${device?.deviceName} [${device?.deviceAddress}]")
                    device?.let { onLocalDeviceAvailable(it) }
                }
            }
        }
    }

    fun unregister() {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            // Already unregistered or not registered
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(deviceAddress: String) {
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
        }

        Logger.d("Attempting Wi-Fi Direct connection to $deviceAddress...")
        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Logger.d("Wi-Fi Direct Connection initiated")
            }

            override fun onFailure(reason: Int) {
                Logger.e("Wi-Fi Direct Connection failed: Reason $reason")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun createGroup() {
        Logger.d("Creating Wi-Fi Direct Group...")
        manager?.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Logger.d("Wi-Fi Direct Group creation initiated")
            }

            override fun onFailure(reason: Int) {
                Logger.e("Wi-Fi Direct Group creation failed: Reason $reason")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun removeGroup() {
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Logger.d("Wi-Fi Direct Group removed")
            }

            override fun onFailure(reason: Int) {
                Logger.e("Wi-Fi Direct Group removal failed: Reason $reason")
            }
        })
    }
}