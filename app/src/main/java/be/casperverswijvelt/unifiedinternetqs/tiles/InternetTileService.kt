package be.casperverswijvelt.unifiedinternetqs.tiles

import android.content.*
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.preference.PreferenceManager
import be.casperverswijvelt.unifiedinternetqs.*
import be.casperverswijvelt.unifiedinternetqs.listeners.CellularChangeListener
import be.casperverswijvelt.unifiedinternetqs.listeners.NetworkChangeCallback
import be.casperverswijvelt.unifiedinternetqs.listeners.NetworkChangeType
import be.casperverswijvelt.unifiedinternetqs.listeners.WifiChangeListener
import be.casperverswijvelt.unifiedinternetqs.util.*

class InternetTileService : TileService() {

    private companion object {
        const val TAG = "InternetTile"
    }

    private var wifiConnected = false
    private var wifiSSID: String? = null
    private var sharedPreferences: SharedPreferences? = null

    private var isTurningOnData = false
    private var isTurningOnWifi = false

    private val runCycleInternet = Runnable {
        cycleInternet()
        syncTile()
    }
    private val cellularChangeCallback = object : NetworkChangeCallback {
        override fun handleChange(type: NetworkChangeType?) {
            syncTile()
        }
    }
    private val wifiChangeCallback = object : NetworkChangeCallback {
        override fun handleChange(type: NetworkChangeType?) {
            when (type) {
                NetworkChangeType.NETWORK_LOST -> {
                    wifiConnected = false
                    wifiSSID = null
                    setLastConnectedWifi(applicationContext, wifiSSID)
                }
                NetworkChangeType.NETWORK_AVAILABLE -> {
                    wifiConnected = true
                    getConnectedWifiSSID {
                        wifiSSID = it
                        setLastConnectedWifi(applicationContext, wifiSSID)
                        syncTile()
                    }
                }
            }
            syncTile()
        }
    }

    private var wifiChangeListener: WifiChangeListener? = null
    private var cellularChangeListener: CellularChangeListener? = null

    override fun onCreate() {
        super.onCreate()
        log("Internet tile service created")

        wifiChangeListener = WifiChangeListener(wifiChangeCallback)
        cellularChangeListener = CellularChangeListener(cellularChangeCallback)

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)

        if (getWifiEnabled(this)) {
            wifiSSID = sharedPreferences
                ?.getString(resources.getString(R.string.last_connected_wifi_key), null)
        }

    }

    override fun onStartListening() {
        super.onStartListening()

        setListeners()
        syncTile()
    }


    override fun onStopListening() {
        super.onStopListening()

        removeListeners()
    }

    override fun onTileAdded() {
        super.onTileAdded()

        setListeners()
        syncTile()
    }

    override fun onTileRemoved() {
        super.onTileRemoved()

        removeListeners()
    }

    override fun onClick() {
        super.onClick()

        if (!hasShellAccess()) {

            // Either root or Shizuku access is needed to enable/disable mobile data and Wi-Fi.
            //  There is currently no other way to do this, so this functionality will not work
            //  without root Shizuku access.
            showDialog(getShellAccessRequiredDialog(applicationContext))
            return
        }

        if (
            sharedPreferences?.getBoolean(
                resources.getString(R.string.require_unlock_key),
                true
            ) == true
        ) {

            unlockAndRun(runCycleInternet)

        } else {

            runCycleInternet.run()
        }
    }

    private fun cycleInternet() {

        // Cycle trough internet connection modes:
        //  If Wi-Fi is enabled -> disable Wi-Fi and enable mobile data
        //  If mobile data is enabled -> disable mobile data and enable Wi-Fi
        //  Else -> enable Wi-Fi

        val dataEnabled = getDataEnabled(applicationContext)
        val wifiEnabled = getWifiEnabled(applicationContext)

        isTurningOnData = false
        isTurningOnWifi = false

        when {
            wifiEnabled -> {
                executeShellCommandAsync("svc wifi disable", null)

                isTurningOnData = true
                executeShellCommandAsync("svc data enable") {
                    if (it?.isSuccess != true) {
                        isTurningOnData = false
                    }
                    syncTile()
                }
            }
            dataEnabled -> {
                executeShellCommandAsync("svc data disable", null)

                isTurningOnWifi = true
                executeShellCommandAsync("svc wifi enable") {
                    if (it?.isSuccess != true) {
                        isTurningOnWifi = false
                    }
                    syncTile()
                }
            }
            else -> {
                isTurningOnWifi = true
                executeShellCommandAsync("svc wifi enable") {
                    if (it?.isSuccess != true) {
                        isTurningOnWifi = false
                    }
                }
            }
        }
    }

    private fun syncTile() {

        val dataEnabled = getDataEnabled(applicationContext)
        val wifiEnabled = getWifiEnabled(applicationContext)

        when {
            (isTurningOnWifi || wifiEnabled) && !isTurningOnData -> {

                if (wifiEnabled) {
                    isTurningOnWifi = false
                }

                // Update tile properties

                qsTile.state = Tile.STATE_ACTIVE
                qsTile.icon = getWifiIcon(applicationContext)
                qsTile.subtitle = if (isTurningOnWifi)
                    resources.getString(R.string.turning_on)
                else
                    (if (wifiConnected) wifiSSID else null)
                        ?: resources.getString(R.string.wifi_on)
            }
            isTurningOnData || dataEnabled -> {

                if (dataEnabled) {
                    isTurningOnData = false
                }

                // Update tile properties

                qsTile.state = Tile.STATE_ACTIVE
                qsTile.icon = getCellularNetworkIcon(applicationContext)
                qsTile.subtitle = getCellularNetworkText(
                    applicationContext,
                    cellularChangeListener?.currentTelephonyDisplayInfo
                )
            }
            else -> {

                // Update tile properties

                qsTile.state = Tile.STATE_INACTIVE
                qsTile.icon = Icon.createWithResource(
                    applicationContext,
                    R.drawable.ic_baseline_public_off_24
                )
                qsTile.subtitle = null
            }
        }

        qsTile.updateTile()
    }

    private fun setListeners() {

        log("Setting listeners")

        wifiConnected = false
        isTurningOnWifi = false
        isTurningOnData = false

        wifiChangeListener?.startListening(applicationContext)
        cellularChangeListener?.startListening(applicationContext)
    }

    private fun removeListeners() {

        log("Removing listeners")

        wifiChangeListener?.stopListening(applicationContext)
        cellularChangeListener?.stopListening(applicationContext)
    }

    private fun log(text: String) {
        Log.d(TAG, text)
    }
}