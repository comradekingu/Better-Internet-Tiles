package be.casperverswijvelt.unifiedinternetqs.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.nfc.NfcManager
import android.os.Build
import android.service.quicksettings.TileService
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import androidx.preference.PreferenceManager
import be.casperverswijvelt.unifiedinternetqs.BuildConfig
import be.casperverswijvelt.unifiedinternetqs.R
import be.casperverswijvelt.unifiedinternetqs.ShizukuDetectService
import be.casperverswijvelt.unifiedinternetqs.ui.MainActivity
import com.topjohnwu.superuser.Shell
import java.lang.reflect.Method

const val TAG = "Util"

fun getDataEnabled(context: Context): Boolean {

    val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

    var mobileDataEnabled = false

    // Get mobile data enabled state
    try {
        val cmClass = Class.forName(connectivityManager.javaClass.name)
        val method: Method = cmClass.getDeclaredMethod("getMobileDataEnabled")
        method.isAccessible = true // Make the method callable
        // get the setting for "mobile data"
        mobileDataEnabled = method.invoke(connectivityManager) as Boolean
    } catch (e: Exception) {
        // Empty
    }

    return mobileDataEnabled
}

fun getWifiEnabled(context: Context): Boolean {

    return (context.getSystemService(TileService.WIFI_SERVICE) as WifiManager).isWifiEnabled
}

fun getNFCEnabled(context: Context): Boolean {

    return (context.getSystemService(TileService.NFC_SERVICE) as NfcManager)
        .defaultAdapter?.isEnabled ?: false
}

fun getConnectedWifiSSID(
    context: Context? = null,
    callback: ((String?) -> Unit)
) {

    if (hasShellAccess(context)) {
        executeShellCommandAsync(
            "dumpsys netstats | grep -E 'iface=wlan.*(networkId|wifiNetworkKey)'"
        ) {
            val pattern = "(?<=(networkId|wifiNetworkKey)=\").*(?=\")".toRegex()
            it?.out?.forEach { wifiString ->
                pattern.find(wifiString)?.let { matchResult ->
                    callback(matchResult.value)
                    return@executeShellCommandAsync
                }
            }
            callback(null)
        }
    } else {
        callback(null)
    }
}

fun getWifiIcon(context: Context): Icon {

    val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val rssi: Int? = try {
        wm.connectionInfo.rssi
    } catch (e: Exception) {
        log("Could not get Wi-Fi RSSI: ${e.message}")
        null
    }
    val signalStrength = rssi?.let {
        // We use 5 levels for our icon visualisation, so we use this deprecated
        //  calculation with 'numLevels' parameter. We don't want to use the system's
        //  level system since it might differ.
        WifiManager.calculateSignalLevel(it, 5) // 0-4
    } ?: 0

    return Icon.createWithResource(
        context,
        when (signalStrength) {
            4 -> R.drawable.ic_baseline_signal_wifi_4_bar_24
            3 -> R.drawable.ic_baseline_signal_wifi_3_bar_24
            2 -> R.drawable.ic_baseline_signal_wifi_2_bar_24
            1 -> R.drawable.ic_baseline_signal_wifi_1_bar_24
            else -> R.drawable.ic_baseline_signal_wifi_0_bar_24
        }
    )
}

fun getCellularNetworkIcon(context: Context): Icon {

    val tm =
        context.getSystemService(TileService.TELEPHONY_SERVICE) as TelephonyManager
    val signalStrength = tm.signalStrength?.level ?: 0

    // TODO: We should try to get the signal strength of the data sim here.
    //  Only solution I found to do this requires fine location access, which I don't really want
    //  to add.

    return Icon.createWithResource(
        context,
        when (signalStrength) {
            4 -> R.drawable.ic_baseline_signal_cellular_4_bar_24
            3 -> R.drawable.ic_baseline_signal_cellular_3_bar_24
            2 -> R.drawable.ic_baseline_signal_cellular_2_bar_24
            1 -> R.drawable.ic_baseline_signal_cellular_1_bar_24
            else -> R.drawable.ic_baseline_signal_cellular_0_bar
        }
    )
}

@SuppressLint("MissingPermission")
fun getCellularNetworkText(
    context: Context,
    telephonyDisplayInfo: TelephonyDisplayInfo?
): String {

    val info = ArrayList<String>()
    val tm =
        context.getSystemService(TileService.TELEPHONY_SERVICE) as TelephonyManager

    val subscriptionInfo = getDataSubscriptionInfo(context)
    // No data sim set or no read phone state permission
        ?: return context.getString(R.string.network_not_available)

    info.add(subscriptionInfo.displayName.toString())

    // TODO: Use signal strength of data SIM
    if (tm.signalStrength?.level == 0) {

        // No service
        return context.getString(R.string.no_service)
    }

    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        telephonyDisplayInfo != null
    ) {

        when (telephonyDisplayInfo.overrideNetworkType) {
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> {
                info.add("4G+")
            }
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> {
                info.add("5Ge")
            }
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> {
                info.add("5G")
            }
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> {
                info.add("5G+")
            }
            else -> {
                getNetworkClassString(tm.dataNetworkType)?.let {
                    info.add(it)
                }
            }
        }

    } else {
        getNetworkClassString(tm.dataNetworkType)?.let {
            info.add(it)
        }
    }

    return info.joinToString(separator = ", ")
}

fun getDataSubscriptionInfo(context: Context): SubscriptionInfo? {

    if (
        context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
        == PackageManager.PERMISSION_GRANTED
    ) {
        val sm =
            context.getSystemService(TileService.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val subId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SubscriptionManager.getActiveDataSubscriptionId()
        } else {
            SubscriptionManager.getDefaultSubscriptionId()
        }

        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {

            return sm.getActiveSubscriptionInfo(subId)
        }
    }
    // No data sim set or no read phone state permission
    return null
}

fun getShellAccessRequiredDialog(context: Context): Dialog {

    val intent = Intent(context, MainActivity::class.java)
    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

    return AlertDialog.Builder(context)
        .setTitle(R.string.shell_access_required)
        .setMessage(R.string.shell_access_not_set_up)
        .setPositiveButton(android.R.string.ok) { _, _ ->
            if (ShizukuUtil.shizukuAvailable) {
                ShizukuUtil.requestShizukuPermission { }
            } else {
                context.startActivity(intent)
            }
        }
        .setCancelable(true)
        .create()
}

fun executeShellCommand(command: String): Shell.Result? {
    if (Shell.isAppGrantedRoot() == true) {
        return Shell.cmd(command).exec()
    } else if (ShizukuUtil.hasShizukuPermission()) {
        val process = ShizukuUtil.executeCommand(command)
        return object : Shell.Result() {
            override fun getOut(): MutableList<String> {
                return process
                    .inputStream.bufferedReader()
                    .use { it.readText() }
                    .split("\n".toRegex())
                    .toMutableList()
            }

            override fun getErr(): MutableList<String> {
                return process
                    .errorStream.bufferedReader()
                    .use { it.readText() }
                    .split("\n".toRegex())
                    .toMutableList()
            }

            override fun getCode(): Int {
                return process.exitValue()
            }
        }
    }
    return null
}

fun executeShellCommandAsync(
    command: String,
    callback: ((Shell.Result?) -> Unit)? = {}
) {
    ExecutorServiceSingleton.getInstance().execute {
        val result = executeShellCommand(command)
        callback?.let { it(result) }
    }
}

/**
 * Check if app has shell access (either root or shizuku).
 * If shell access is detected, the shizuku detection service is automatically stopped
 */
fun hasShellAccess(context: Context? = null): Boolean {

    val hasShellAccess =
        Shell.isAppGrantedRoot() == true || ShizukuUtil.hasShizukuPermission()

    if (hasShellAccess) {
        context?.stopService(Intent(context, ShizukuDetectService::class.java))
    }

    return hasShellAccess
}

fun grantReadPhoneState(callback: ((Shell.Result?) -> Unit)? = {}) {
    return executeShellCommandAsync(
        "pm grant ${BuildConfig.APPLICATION_ID} ${Manifest.permission.READ_PHONE_STATE}",
        callback
    )
}

fun setLastConnectedWifi(context: Context, ssid: String?) {
    val pm = PreferenceManager.getDefaultSharedPreferences(context)
    val editor = pm.edit()
    editor.putString(
        context.resources.getString(R.string.last_connected_wifi_key),
        ssid
    )
    editor.apply()
}

private fun getNetworkClassString(networkType: Int): String? {

    return when (networkType) {
        TelephonyManager.NETWORK_TYPE_GSM,
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT -> "2G"
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
        TelephonyManager.NETWORK_TYPE_LTE,
        TelephonyManager.NETWORK_TYPE_IWLAN -> "4G"
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        else -> null
    }
}

private fun log(text: String) {
    Log.d(TAG, text)
}