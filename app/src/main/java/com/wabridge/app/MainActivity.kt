package com.wabridge.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvLastEvent: TextView
    private lateinit var btnTogglePolling: Button
    private lateinit var etWebAppUrl: EditText
    private val uiHandler = Handler(Looper.getMainLooper())
    private val logRefreshRunnable = object : Runnable {
        override fun run() {
            tvLastEvent.text = EventLog.getAll().ifBlank { "(אין אירועים עדיין)" }
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvLastEvent = findViewById(R.id.tvLastEvent)
        btnTogglePolling = findViewById(R.id.btnTogglePolling)
        findViewById<TextView>(R.id.tvBuildTag).text = "גרסה מותקנת: ${BuildInfo.BUILD_TAG}"

        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val logText = EventLog.getAll()
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("WA Bridge log", logText))
            Toast.makeText(this, "היומן הועתק - אפשר להדביק בהודעה", Toast.LENGTH_SHORT).show()
        }
        etWebAppUrl = findViewById(R.id.etWebAppUrl)
        val btnGrantAccess = findViewById<Button>(R.id.btnGrantAccess)
        val btnSaveUrl = findViewById<Button>(R.id.btnSaveUrl)
        val btnGrantAccessibility = findViewById<Button>(R.id.btnGrantAccessibility)

        Prefs.getWebAppUrl(this)?.let { etWebAppUrl.setText(it) }

        btnGrantAccess.setOnClickListener {
            // Notification access cannot be requested via a normal runtime
            // permission dialog - this opens the system settings screen
            // where the user must manually toggle it on for this app.
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btnGrantAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnSaveUrl.setOnClickListener {
            val url = etWebAppUrl.text.toString().trim()
            if (url.isEmpty() || !url.startsWith("https://")) {
                Toast.makeText(this, "כתובת לא תקינה - חייבת להתחיל ב-https://", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Prefs.setWebAppUrl(this, url)
            Toast.makeText(this, "נשמר", Toast.LENGTH_SHORT).show()
        }

        btnTogglePolling.setOnClickListener {
            val intent = Intent(this, PollingService::class.java)
            if (PollingService.isRunning) {
                stopService(intent)
            } else {
                if (Prefs.getWebAppUrl(this).isNullOrBlank()) {
                    Toast.makeText(this, "קודם שמור את כתובת ה-Web App למעלה", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
            // Small delay so isRunning reflects the just-issued command.
            btnTogglePolling.postDelayed({ updateStatus() }, 300)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        uiHandler.post(logRefreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(logRefreshRunnable)
    }

    private fun updateStatus() {
        val enabledListeners = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ) ?: ""
        val granted = enabledListeners.contains(packageName)
        tvStatus.text = if (granted) {
            "✅ גישה להתראות מאושרת - השירות פעיל"
        } else {
            "❌ גישה להתראות לא מאושרת - לחץ למטה כדי לאשר"
        }

        val enabledAccessibility = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val accessibilityGranted = enabledAccessibility.contains(packageName)
        tvAccessibilityStatus.text = if (accessibilityGranted) {
            "✅ שירות הנגישות פעיל"
        } else {
            "❌ שירות הנגישות לא מאושר - לחץ למטה כדי לאשר (חפש \"WA Bridge\" ברשימה)"
        }

        btnTogglePolling.text = if (PollingService.isRunning) {
            "⏹ עצור שירות שליחה (פעיל כרגע)"
        } else {
            "▶ התחל שירות שליחה"
        }
    }
}
