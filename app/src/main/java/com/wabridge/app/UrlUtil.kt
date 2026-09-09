package com.wabridge.app

// FIX (multi-tenant router): the stored Web App URL now always includes
// "?token=..." (identifying which user this device belongs to, so a
// single shared Apps Script deployment can serve every user). Every
// call site that used to hardcode "$webAppUrl?action=..." would have
// produced an invalid URL with two "?" characters once the base URL
// itself already has one. This helper appends correctly either way -
// also tolerant of an old-style URL with no query string at all, in
// case a device still has one saved from before this change.
object UrlUtil {
    fun appendParam(baseUrl: String, param: String): String {
        return if (baseUrl.contains("?")) "$baseUrl&$param" else "$baseUrl?$param"
    }
}
