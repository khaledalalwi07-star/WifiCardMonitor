package com.example.wificardmonitor

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var allowance: EditText
    private lateinit var hours: EditText
    private lateinit var days: EditText
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private val prefs by lazy { getSharedPreferences("card", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private val refreshTask = object : Runnable {
        override fun run() {
            showSummary()
            handler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        fun label(textValue: String) {
            layout.addView(TextView(this).apply {
                text = textValue
                textSize = 17f
                setPadding(0, 14, 0, 4)
            })
        }

        label("مراقب كروت الإنترنت")
        label("حجم الباقة بالميجابايت (MB)")
        allowance = EditText(this).apply {
            hint = "مثال: 2000"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        layout.addView(allowance)

        label("ساعات الاتصال المسموحة")
        hours = EditText(this).apply {
            hint = "مثال: 24"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        layout.addView(hours)

        label("مدة الصلاحية بالأيام")
        days = EditText(this).apply {
            hint = "مثال: 7"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(days)

        val start = Button(this).apply { text = "بدء بطاقة جديدة" }
        layout.addView(start)

        val permission = Button(this).apply { text = "فتح صلاحية الوصول إلى سجل الاستخدام" }
        layout.addView(permission)

        val refresh = Button(this).apply { text = "تحديث الرصيد الآن" }
        layout.addView(refresh)

        summary = TextView(this).apply {
            textSize = 17f
            setPadding(0, 20, 0, 0)
        }
        layout.addView(summary)

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 12, 0, 0)
        }
        layout.addView(status)

        setContentView(ScrollView(this).apply { addView(layout) })

        permission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        start.setOnClickListener {
            val mb = allowance.text.toString().toDoubleOrNull()
            val allowedHours = hours.text.toString().toDoubleOrNull()
            val validDays = days.text.toString().toLongOrNull()

            if (mb == null || allowedHours == null || validDays == null ||
                !mb.isFinite() || !allowedHours.isFinite() ||
                mb <= 0.0 || allowedHours <= 0.0 || validDays <= 0L
            ) {
                status.text = "أدخل أرقامًا صحيحة أكبر من صفر في الحقول الثلاثة."
                return@setOnClickListener
            }

            if (mb > Long.MAX_VALUE / 1_048_576.0 ||
                allowedHours > Long.MAX_VALUE / 3_600_000.0 ||
                validDays > Long.MAX_VALUE / 86_400_000L
            ) {
                status.text = "القيمة كبيرة جدًا. أدخل قيمة أصغر."
                return@setOnClickListener
            }

            if (!hasUsageAccess()) {
                status.text = "فعّل صلاحية الوصول إلى سجل الاستخدام لهذا التطبيق، ثم ارجع واضغط بدء البطاقة."
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                return@setOnClickListener
            }

            val now = System.currentTimeMillis()
            prefs.edit().clear()
                .putLong("start", now)
                .putLong("allowance_bytes", (mb * 1_048_576.0).toLong())
                .putLong("allowed_ms", (allowedHours * 3_600_000.0).toLong())
                .putLong("valid_ms", validDays * 86_400_000L)
                .putLong("connected_ms", 0L)
                .putLong("consumed_bytes", 0L)
                .putLong("baseline_bytes", -1L)
                .putLong("last_total_bytes", -1L)
                .putBoolean("running", true)
                .apply()

            val serviceIntent = Intent(this, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(start.windowToken, 0)

            status.text = "بدأت البطاقة. سيُحدَّث الرصيد تلقائيًا كل عدة ثوانٍ."
            showSummary()
        }

        refresh.setOnClickListener { showSummary() }
        showSummary()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshTask)
        handler.post(refreshTask)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun showSummary() {
        val startTime = prefs.getLong("start", 0L)
        if (startTime == 0L) {
            summary.text = "لم تبدأ بطاقة بعد."
            return
        }

        val allowanceBytes = prefs.getLong("allowance_bytes", 0L)
        val consumedBytes = prefs.getLong("consumed_bytes", 0L)
        val remainingBytes = (allowanceBytes - consumedBytes).coerceAtLeast(0L)
        val connectedMs = prefs.getLong("connected_ms", 0L)
        val allowedMs = prefs.getLong("allowed_ms", 0L)
        val validMs = prefs.getLong("valid_ms", 0L)
        val validityLeft = (validMs - (System.currentTimeMillis() - startTime))
            .coerceAtLeast(0L)
        val timeLeft = (allowedMs - connectedMs).coerceAtLeast(0L)
        val active = prefs.getBoolean("running", false)

        summary.text =
            "الحالة: ${if (active) "البطاقة تعمل" else "البطاقة متوقفة أو انتهت"}\n" +
            "المستهلك: ${formatMB(consumedBytes)} MB\n" +
            "المتبقي: ${formatMB(remainingBytes)} MB\n" +
            "وقت Wi-Fi المحسوب: ${formatTime(connectedMs)}\n" +
            "وقت الاتصال المتبقي: ${formatTime(timeLeft)}\n" +
            "الصلاحية المتبقية: ${TimeUnit.MILLISECONDS.toDays(validityLeft)} يوم"
    }

    private fun formatMB(bytes: Long): String = String.format("%.1f", bytes / 1_048_576.0)

    private fun formatTime(ms: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
        return "${minutes / 60} ساعة و${minutes % 60} دقيقة"
    }
}
