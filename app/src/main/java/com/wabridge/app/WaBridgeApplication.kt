package com.wabridge.app

import android.app.Application
import android.content.Context

/**
 * Registers a global crash handler so that if ANYTHING in the app
 * crashes unexpectedly (anywhere, not just the notification listener),
 * we at least get a persisted trace of what happened - EventLog alone
 * is in-memory and is lost the moment the process dies, which is
 * exactly when we'd most want to see it. MainActivity displays the
 * last crash (if any) on startup.
 */
class WaBridgeApplication : Application() {

    companion object {
        private const val PREFS_NAME = "wa_bridge_crash"
        private const val KEY_LAST_CRASH = "last_crash"

        fun getLastCrash(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_LAST_CRASH, null)
        }

        fun clearLastCrash(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_LAST_CRASH).apply()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = throwable.stackTraceToString()
                val info = "${java.util.Date()}\nThread: ${thread.name}\n$trace"
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_LAST_CRASH, info).apply()
            } catch (e: Exception) {
                // Nothing more we can do - don't let the crash handler
                // itself throw and mask the original crash.
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
