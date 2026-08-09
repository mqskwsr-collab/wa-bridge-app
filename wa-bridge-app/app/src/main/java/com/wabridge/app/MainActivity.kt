package com.wabridge.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var etWebAppUrl: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        etWebAppUrl = findViewById(R.id.etWebAppUrl)
        val btnGrantAccess = findViewById<Button>(R.id.btnGrantAccess)
        val btnSaveUrl = findViewById<Button>(R.id.btnSaveUrl)

        Prefs.getWebAppUrl(this)?.let { etWebAppUrl.setText(it) }

        btnGrantAccess.setOnClickListener {
            // Notification access cannot be requested via a normal runtime
            // permission dialog - this opens the system settings screen
            // where the user must manually toggle it on for this app.
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
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
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
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
    }
}
