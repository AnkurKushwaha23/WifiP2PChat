package com.ankurkushwaha.p2pchat

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.Channel
import android.os.Build
import androidx.core.app.ActivityCompat

class WifiDirectBroadcastReceiver(
    val manager: WifiP2pManager,
    val channel: Channel,
    val activity: MainActivity
) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action
        when (action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    println("Wi-Fi P2P is enabled.")
                } else {
                    println("Wi-Fi P2P is not enabled.")
                }
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                println("Peers changed action received.")
                if (ActivityCompat.checkSelfPermission(
                        activity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ActivityCompat.checkSelfPermission(
                                activity,
                                Manifest.permission.NEARBY_WIFI_DEVICES
                            ) != PackageManager.PERMISSION_GRANTED)
                ) {
                    println("Permissions not granted for peer discovery.")
                    return
                }
                manager.requestPeers(channel, activity.peerListListener)
            }
//fix the below code
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                println("Connection changed action received.")
                val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)

                if (networkInfo != null && networkInfo.isConnected) {
                    manager.requestConnectionInfo(channel, activity.connectionInfoListener)
                } else {
                    activity.binding.connectionStatus.text = "Not Connected"
                }
            }
        }
    }
}