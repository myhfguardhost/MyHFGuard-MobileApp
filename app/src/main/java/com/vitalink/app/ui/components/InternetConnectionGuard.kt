package com.vitalink.app.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vitalink.app.util.AppLanguage
import kotlinx.coroutines.delay

/** One app-wide prompt, only while foregrounded and without validated internet. */
@Composable
fun InternetConnectionGuard(lifecycle: Lifecycle, content: @Composable (Boolean) -> Unit) {
    val context = LocalContext.current
    var online by remember { mutableStateOf<Boolean?>(null) }
    var foreground by remember { mutableStateOf(false) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var settingsUnavailable by remember { mutableStateOf(false) }

    DisposableEffect(lifecycle, context) {
        val monitor = InternetMonitor(context.applicationContext) { connected -> online = connected }
        fun start() {
            foreground = true
            monitor.start()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> {
                    foreground = false
                    monitor.stop()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose {
            lifecycle.removeObserver(observer)
            monitor.stop()
        }
    }

    LaunchedEffect(online, foreground, dismissed) {
        showDialog = false
        if (online == true) {
            dismissed = false
            settingsUnavailable = false
        } else if (online == false && foreground && !dismissed) {
            // Avoid flashing a dialog during a brief Wi-Fi/mobile-data handover.
            delay(1200)
            showDialog = true
        }
    }

    val offlinePromptPending = foreground && online == false && !dismissed
    // Also defer other startup reminders during the first connectivity snapshot.
    content((online == null || offlinePromptPending) && !dismissed)

    if (showDialog && offlinePromptPending) {
        AlertDialog(
            onDismissRequest = { dismissed = true },
            title = { Text(AppLanguage.text("Internet connection needed", "Sambungan internet diperlukan", "需要互联网连接", "இணைய இணைப்பு தேவை")) },
            text = {
                Text(if (settingsUnavailable) AppLanguage.text(
                    "Open your phone's Settings and turn on Wi-Fi or mobile data, then return to MyHFGuard.",
                    "Buka Tetapan telefon dan hidupkan Wi-Fi atau data mudah alih, kemudian kembali ke MyHFGuard.",
                    "请打开手机设置并开启 Wi-Fi 或移动数据，然后返回 MyHFGuard。",
                    "தொலைபேசி அமைப்புகளில் Wi-Fi அல்லது மொபைல் தரவை இயக்கி, MyHFGuard-க்குத் திரும்பவும்."
                ) else AppLanguage.text(
                    "Please connect to Wi-Fi or turn on mobile data to load and save your health information. If Wi-Fi is already on, check that it has internet access. This message closes when your connection is restored.",
                    "Sila sambung ke Wi-Fi atau hidupkan data mudah alih untuk memuatkan dan menyimpan maklumat kesihatan. Jika Wi-Fi sudah aktif, pastikan ia mempunyai akses internet. Mesej ini ditutup apabila sambungan pulih.",
                    "请连接 Wi-Fi 或开启移动数据，以加载和保存健康信息。如果 Wi-Fi 已开启，请确认它可以访问互联网。连接恢复后，此提示会自动关闭。",
                    "உடல்நலத் தகவலை ஏற்றவும் சேமிக்கவும் Wi-Fi-இல் இணையவும் அல்லது மொபைல் தரவை இயக்கவும். Wi-Fi ஏற்கனவே இயக்கப்பட்டிருந்தால் இணைய அணுகல் உள்ளதா எனச் சரிபார்க்கவும். இணைப்பு மீண்டதும் இந்தச் செய்தி மூடப்படும்."
                ))
            },
            confirmButton = {
                TextButton(onClick = { settingsUnavailable = !openInternetSettings(context) }) {
                    Text(AppLanguage.text("Open settings", "Buka tetapan", "打开设置", "அமைப்புகளைத் திறக்கவும்"))
                }
            },
            dismissButton = {
                TextButton(onClick = { dismissed = true }) {
                    Text(AppLanguage.text("Later", "Kemudian", "稍后", "பின்னர்"))
                }
            }
        )
    }
}

private class InternetMonitor(context: Context, private val onChanged: (Boolean) -> Unit) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null || manager == null) return
        var currentNetwork: Network? = manager.activeNetwork
        onChanged(currentNetwork?.let(manager::getNetworkCapabilities).isOnline())
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (callback !== this) return
                currentNetwork = network
                // onCapabilitiesChanged follows onAvailable; use its supplied snapshot.
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (callback === this && network == currentNetwork) onChanged(capabilities.isOnline())
            }

            override fun onLost(network: Network) {
                if (callback === this && network == currentNetwork) {
                    currentNetwork = null
                    onChanged(false)
                }
            }
        }
        callback = listener
        try {
            manager.registerDefaultNetworkCallback(listener, handler)
        } catch (_: RuntimeException) {
            // Retry monitoring next foreground session; never crash while showing a reminder.
            callback = null
        }
    }

    fun stop() {
        val listener = callback ?: return
        callback = null
        try { manager?.unregisterNetworkCallback(listener) } catch (_: RuntimeException) { }
    }

    private fun NetworkCapabilities?.isOnline(): Boolean = this != null &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private fun openInternetSettings(context: Context): Boolean {
    val actions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        listOf(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)
    } else {
        listOf(Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)
    }
    for (action in actions) {
        try {
            context.startActivity(Intent(action))
            return true
        } catch (_: ActivityNotFoundException) {
            // Some manufacturers omit the Internet panel. Try their full Settings app.
        } catch (_: SecurityException) { }
    }
    return false
}
