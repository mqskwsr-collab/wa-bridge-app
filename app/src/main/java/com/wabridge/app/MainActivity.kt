package com.wabridge.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_READ_STORAGE = 1001
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvAllFilesStatus: TextView
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
        tvAllFilesStatus = findViewById(R.id.tvAllFilesStatus)
        tvLastEvent = findViewById(R.id.tvLastEvent)
        btnTogglePolling = findViewById(R.id.btnTogglePolling)
        findViewById<TextView>(R.id.tvBuildTag).text = "גרסה מותקנת: ${BuildInfo.BUILD_TAG}"

        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            // FIX37: a real crash (TransactionTooLargeException, ~1MB
            // clipboard parcel) confirmed the accumulated log text can
            // exceed Android's Binder transaction size limit (~1MB,
            // shared across the whole process, not just this call).
            // Truncate defensively before copying so this can never
            // crash the whole app again, regardless of how large the
            // in-memory log ever grows.
            val logText = truncatedLogText()
            // FIX (21.8.2026): this used to always show a "copied!"
            // success toast unconditionally, even though the user
            // reported copy silently not working (confirmed - some
            // emulator/OEM clipboard implementations accept the call
            // without throwing but never actually populate the system
            // clipboard, e.g. some NoxPlayer builds). Now verifies by
            // reading the clip straight back before claiming success,
            // and wraps the whole thing in try/catch in case the
            // ClipboardManager call itself throws (also seen on some
            // devices). If verification fails, tells the user honestly
            // and points them at the two fallbacks added the same date:
            // long-press-to-select on the log text itself (now
            // textIsSelectable), or the new "שתף" share button.
            try {
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("WA Bridge log", logText))
                val readBack = clipboard.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.text?.toString()
                if (readBack == logText) {
                    Toast.makeText(this, "היומן הועתק - אפשר להדביק בהודעה", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this,
                        "ההעתקה לא הצליחה במכשיר הזה - נסה את כפתור \"שתף\" 📤 או לחיצה ארוכה על הטקסט למטה",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "ההעתקה נכשלה (${e.javaClass.simpleName}) - נסה את כפתור \"שתף\" 📤 או לחיצה ארוכה על הטקסט למטה",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        findViewById<Button>(R.id.btnShareLog).setOnClickListener {
            // FIX (21.8.2026): clipboard-independent fallback - routes
            // the log text through Android's normal share sheet instead
            // (email, WhatsApp itself, Notes, Drive, etc.), so a broken
            // clipboard on this specific device/emulator doesn't block
            // getting logs out at all.
            val logText = truncatedLogText()
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, logText)
                putExtra(Intent.EXTRA_SUBJECT, "WA Bridge log")
            }
            try {
                startActivity(Intent.createChooser(sendIntent, "שתף את היומן"))
            } catch (e: Exception) {
                Toast.makeText(this, "השיתוף נכשל: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
        }

        val lastCrash = WaBridgeApplication.getLastCrash(this)
        val tvLastCrash = findViewById<TextView>(R.id.tvLastCrash)
        if (lastCrash != null) {
            tvLastCrash.visibility = android.view.View.VISIBLE
            tvLastCrash.text = "⚠️ קריסה אחרונה שנתפסה:\n$lastCrash"
            tvLastCrash.setOnClickListener {
                val clipboard = getSystemService(ClipboardManager::class.java)
                val crashText = if (lastCrash.length > 200_000) lastCrash.takeLast(200_000) else lastCrash
                clipboard.setPrimaryClip(ClipData.newPlainText("WA Bridge crash", crashText))
                Toast.makeText(this, "פרטי הקריסה הועתקו", Toast.LENGTH_SHORT).show()
                WaBridgeApplication.clearLastCrash(this)
                tvLastCrash.visibility = android.view.View.GONE
            }
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

        findViewById<Button>(R.id.btnGrantAllFiles).setOnClickListener {
            // MANAGE_EXTERNAL_STORAGE ("All files access") can't be
            // requested via a normal runtime permission dialog either -
            // same manual-grant pattern as notification access and
            // accessibility above.
            // FIX (21.8.2026): below API 30 this used to just show a
            // "not needed" toast and do nothing else, on the (wrong)
            // assumption that no permission was needed at all pre-
            // Android-11. In reality READ_EXTERNAL_STORAGE is still a
            // normal runtime-requestable permission there, and it was
            // never being requested - confirmed on-device as the actual
            // root cause of every media download silently failing on a
            // pre-R test device. Now requests it properly there instead.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    // Some OEM ROMs don't implement the per-app variant of
                    // this settings screen - fall back to the general one.
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            } else {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_CODE_READ_STORAGE
                )
            }
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
            btnTogglePolling.isEnabled = false // prevent double-tap starting it twice
            val intent = Intent(this, PollingService::class.java)
            if (PollingService.isRunning) {
                stopService(intent)
            } else {
                if (Prefs.getWebAppUrl(this).isNullOrBlank()) {
                    Toast.makeText(this, "קודם שמור את כתובת ה-Web App למעלה", Toast.LENGTH_LONG).show()
                    btnTogglePolling.isEnabled = true
                    return@setOnClickListener
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
            // Small delay so isRunning reflects the just-issued command.
            btnTogglePolling.postDelayed({
                updateStatus()
                btnTogglePolling.isEnabled = true
            }, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        uiHandler.post(logRefreshRunnable)
    }

    private fun truncatedLogText(): String {
        val fullLogText = EventLog.getAll()
        val maxChars = 200_000
        return if (fullLogText.length > maxChars) {
            "... (היומן קוצר - מוצגים ${maxChars} התווים האחרונים מתוך ${fullLogText.length}) ...\n" +
                fullLogText.takeLast(maxChars)
        } else {
            fullLogText
        }
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(logRefreshRunnable)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_READ_STORAGE) {
            // Refresh the on-screen status immediately with the result,
            // same as every other manual-grant flow already does via
            // onResume when returning from a Settings screen.
            updateStatus()
        }
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

        val allFilesGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // FIX (21.8.2026): was hardcoded `true` here - see the long
            // comment on WaMediaLocator.isAvailable() (same date) for
            // why that was wrong and what it broke on-device.
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        tvAllFilesStatus.text = if (allFilesGranted) {
            "✅ גישה לכל הקבצים מאושרת - תמונות/וידאו/הקלטות ייתפסו אוטומטית"
        } else {
            "❌ גישה לכל הקבצים לא מאושרת - תמונות/וידאו/הקלטות יישלחו כטקסט בלבד עד שתאשר"
        }
    }
}
