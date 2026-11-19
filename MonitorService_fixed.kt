package com.example.wificardmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MonitorService : Service() {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var wifiConnected = false
    private var lastElapsed = 0L
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var connectivity: ConnectivityManager
    private var callbackRegistered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateWifiState()
        }

        override fun onLost(network: Network) {
            updateWifiState()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            updateWifiState()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("card", MODE_PRIVATE)
        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        createNotificationChannel()
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setContentTitle("مراقب كروت الإنترنت")
            .setContentText("مراقبة استهلاك Wi-Fi ووقت الاتصال")
            .setSmallIcon(android.R.drawable.stat_sys_wifi)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivity.registerNetworkCallback(request, callback)
            callbackRegistered = true
        } catch (_: Exception) {
            // تستمر قراءة الإحصائيات، لكن متابعة تغير الاتصال قد تكون أقل دقة.
        }

        wifiConnected = isWifiActive()
        lastElapsed = SystemClock.elapsedRealtime()
        executor.scheduleAtFixedRate({
            try {
                sample()
            } catch (_: Exception) {
                // لا نسمح لخطأ عابر بإيقاف مهمة المراقبة.
            }
        }, 1, 15, TimeUnit.SECONDS)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "مراقبة البطاقة",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun isWifiActive(): Boolean {
        val active = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(active) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun updateWifiState() {
        setConnected(isWifiActive())
    }

    @Synchronized
    private fun setConnected(value: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (lastElapsed > 0L && wifiConnected) {
            val delta = (now - lastElapsed).coerceAtLeast(0L)
            prefs.edit()
                .putLong("connected_ms", prefs.getLong("connected_ms", 0L) + delta)
                .apply()
        }
        wifiConnected = value
        lastElapsed = now
    }

    @Synchronized
    private fun updateConnectedTime() {
        val now = SystemClock.elapsedRealtime()
        if (wifiConnected && lastElapsed > 0L) {
            val delta = (now - lastElapsed).coerceAtLeast(0L)
            prefs.edit()
                .putLong("connected_ms", prefs.getLong("connected_ms", 0L) + delta)
                .apply()
        }
        lastElapsed = now
    }

    private fun sample() {
        if (!prefs.getBoolean("running", false)) {
            stopSelf()
            return
        }

        updateConnectedTime()

        val startedAt = prefs.getLong("start", 0L)
        val validMs = prefs.getLong("valid_ms", 0L)
        val allowanceBytes = prefs.getLong("allowance_bytes", 0L)
        val allowedMs = prefs.getLong("allowed_ms", 0L)
        val consumedBefore = prefs.getLong("consumed_bytes", 0L)
        val connectedBefore = prefs.getLong("connected_ms", 0L)

        if (startedAt <= 0L ||
            System.currentTimeMillis() - startedAt >= validMs ||
            consumedBefore >= allowanceBytes ||
            connectedBefore >= allowedMs
        ) {
            finishCard()
            return
        }

        val total = readWifiBytes() ?: return
        val baseline = prefs.getLong("baseline_bytes", -1L)

        if (baseline < 0L) {
            prefs.edit()
                .putLong("baseline_bytes", total)
                .putLong("last_total_bytes", total)
                .apply()
            return
        }

        val previous = prefs.getLong("last_total_bytes", baseline)
        if (total >= previous) {
            val delta = total - previous
            if (delta > 0L) {
                val newConsumed = prefs.getLong("consumed_bytes", 0L) + delta
                prefs.edit().putLong("consumed_bytes", newConsumed).apply()
            }
        }
        // إذا أعاد أندرويد ضبط عداد الإحصائيات، نعيد خط الأساس دون إضافة قيمة سالبة.
        prefs.edit().putLong("last_total_bytes", total).apply()

        val consumedNow = prefs.getLong("consumed_bytes", 0L)
        val connectedNow = prefs.getLong("connected_ms", 0L)
        val expired = System.currentTimeMillis() - startedAt >= validMs
        if (expired || consumedNow >= allowanceBytes || connectedNow >= allowedMs) {
            finishCard()
        }
    }

    private fun finishCard() {
        prefs.edit().putBoolean("running", false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun readWifiBytes(): Long? {
        return try {
            val manager = getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            val stats: NetworkStats = manager.querySummary(
                ConnectivityManager.TYPE_WIFI,
                null,
                0L,
                System.currentTimeMillis()
            )
            val bucket = NetworkStats.Bucket()
            var total = 0L
            while (stats.hasNextBucket()) {
                if (stats.getNextBucket(bucket)) {
                    total += bucket.rxBytes + bucket.txBytes
                }
            }
            stats.close()
            total
        } catch (_: Exception) {
            null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::prefs.isInitialized) updateConnectedTime()
        if (callbackRegistered) {
            try {
                connectivity.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "wifi_card_monitor"
        private const val NOTIFICATION_ID = 7
    }
}
