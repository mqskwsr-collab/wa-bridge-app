package com.wabridge.app

import android.content.Context

/**
 * Tiny wrapper around SharedPreferences so the Apps Script Web App URL
 * only needs to be entered once, in MainActivity, instead of being
 * hardcoded/recompiled (unlike Code.gs's constants, which do require a
 * redeploy - see status file section on Code.gs maintenance).
 */
object Prefs {
    private const val PREFS_NAME = "wa_bridge_prefs"
    private const val KEY_WEBAPP_URL = "webapp_url"

    fun getWebAppUrl(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_WEBAPP_URL, null)
    }

    fun setWebAppUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_WEBAPP_URL, url.trim()).apply()
    }
}
