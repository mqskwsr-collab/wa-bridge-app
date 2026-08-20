package com.wabridge.app

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Finds the actual media FILE WhatsApp wrote to disk for a just-arrived
 * notification. The notification itself never carries the real file
 * (at most a small compressed preview embedded in some cases), so this
 * reads directly from WhatsApp's own media folder.
 *
 * FIX (19.8.2026): on-device diagnostics showed the "new" post-2021
 * scoped-storage location (Android/media/com.whatsapp/WhatsApp/Media/)
 * exists but is completely EMPTY on this device/WhatsApp install - no
 * "WhatsApp" subfolder at all, meaning this install never migrated to
 * it and is still using the pre-2021 legacy root-level location. Both
 * are now tried, in order, since which one applies can genuinely differ
 * per device/WhatsApp version/install history:
 *
 *   1) Android/media/com.whatsapp/WhatsApp/Media/   (new, most current installs)
 *   2) WhatsApp/Media/                              (legacy, storage root)
 *
 *   each containing:
 *     ├── WhatsApp Images/
 *     ├── WhatsApp Video/
 *     └── WhatsApp Voice Notes/
 *
 * Requires MANAGE_EXTERNAL_STORAGE ("All files access", granted manually
 * via Settings - see MainActivity) rather than the narrower
 * READ_MEDIA_IMAGES/VIDEO/AUDIO permissions: WhatsApp is known to drop a
 * .nomedia marker in some of these subfolders on some devices, which
 * silently hides the files from MediaStore queries (what the narrower
 * permissions gate access through) while leaving them perfectly readable
 * via direct File access, which is what this class uses.
 *
 * MATCHING STRATEGY: there is no reliable per-chat identifier available
 * from the filesystem side (WhatsApp organizes media by TYPE, not by
 * chat/sender), so this simply returns the newest file in the relevant
 * subfolder whose lastModified() falls within a short window of the
 * notification's arrival. KNOWN LIMITATION: if two media messages of the
 * same type arrive from different chats within that same short window,
 * this can pick the wrong one. Acceptable for a low-volume personal/family
 * bridge; not something to over-engineer for now.
 */
object WaMediaLocator {

    private const val TAG = "WaBridgeMedia"

    private val CANDIDATE_BASE_PATHS = listOf(
        "Android/media/com.whatsapp/WhatsApp/Media", // new (post-2021 scoped storage)
        "WhatsApp/Media"                              // legacy (storage root)
    )
    private const val SUBFOLDER_IMAGES = "WhatsApp Images"
    private const val SUBFOLDER_VIDEO = "WhatsApp Video"
    private const val SUBFOLDER_VOICE_NOTES = "WhatsApp Voice Notes"

    // How far back (and slightly forward, to absorb clock/IO ordering
    // slack between "file written" and "notification posted") from the
    // notification's arrival time to consider a file a match.
    private const val MATCH_WINDOW_MS = 15_000L

    data class FoundMedia(val file: File, val mimeType: String)

    fun isAvailable(): Boolean {
        // Environment.isExternalStorageManager() only exists from API 30
        // (Android 11) onward - minSdk for this app is 26, so this must
        // be version-guarded or it crashes on older devices with a
        // NoSuchMethodError. Below API 30, MANAGE_EXTERNAL_STORAGE isn't
        // a thing yet and legacy external storage access rules apply
        // instead; treating it as "available" there is fine since this
        // whole media feature is a best-effort add-on that already fails
        // safe (see attachMediaIfAny's try/catch) if direct file access
        // turns out not to work on such a device.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return Environment.isExternalStorageManager()
    }

    /**
     * Returns the best-guess file for this media type near the given
     * notification timestamp, or null if the permission isn't granted,
     * no candidate folder exists, or nothing recent enough was found.
     */
    fun findRecentMediaFile(context: Context, type: MediaClassifier.MediaType, notificationTimeMs: Long): FoundMedia? {
        if (type == MediaClassifier.MediaType.NONE) return null

        if (!isAvailable()) {
            Log.w(TAG, "MANAGE_EXTERNAL_STORAGE not granted - cannot locate media file")
            EventLog.log("Media: ⚠️ אין הרשאת \"כל הקבצים\" - לא ניתן לאתר את קובץ המדיה")
            return null
        }

        val subfolder = when (type) {
            MediaClassifier.MediaType.IMAGE -> SUBFOLDER_IMAGES
            MediaClassifier.MediaType.VIDEO -> SUBFOLDER_VIDEO
            MediaClassifier.MediaType.VOICE_NOTE -> SUBFOLDER_VOICE_NOTES
            MediaClassifier.MediaType.NONE -> return null
        }

        val root = Environment.getExternalStorageDirectory()
        var dir: File? = null
        for (basePath in CANDIDATE_BASE_PATHS) {
            val candidate = File(root, "$basePath/$subfolder")
            if (candidate.isDirectory) {
                dir = candidate
                break
            }
        }

        if (dir == null) {
            val tried = CANDIDATE_BASE_PATHS.joinToString(" , ") { File(root, "$it/$subfolder").absolutePath }
            Log.w(TAG, "Media folder not found in any candidate path: $tried")
            EventLog.log("Media: ⚠️ תיקיית מדיה לא נמצאה באף אחד מהנתיבים: $tried")
            logDiagnostics(root)
            return null
        }

        val candidates = dir.listFiles { f -> f.isFile } ?: emptyArray()
        val best = candidates
            .filter { kotlin.math.abs(it.lastModified() - notificationTimeMs) <= MATCH_WINDOW_MS }
            .maxByOrNull { it.lastModified() }

        if (best == null) {
            Log.w(TAG, "No recent file matched in ${dir.absolutePath} within ${MATCH_WINDOW_MS}ms of $notificationTimeMs")
            EventLog.log("Media: ⚠️ לא נמצא קובץ תואם בזמן ב-\"${dir.absolutePath}\"")
            return null
        }

        val mimeType = guessMimeType(best.name)
        Log.i(TAG, "Matched media file: ${best.absolutePath} (mime=$mimeType)")
        EventLog.log("Media: ✅ נמצא קובץ: ${best.name} (ב-${dir.absolutePath})")
        return FoundMedia(best, mimeType)
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "3gp" -> "video/3gpp"
            "opus" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            else -> "application/octet-stream"
        }
    }

    /**
     * DIAGNOSTIC (19.8.2026) - neither candidate base path panned out
     * (confirmed on-device: Android/media/com.whatsapp exists but is
     * completely empty, meaning that install never migrated to it - see
     * class doc comment). Rather than add a third guessed candidate path,
     * this does a broad, case-insensitive scan of the storage root for
     * ANY folder whose name contains "whatsapp", and logs what it finds -
     * covering unexpected casing/naming we haven't anticipated, so the
     * real answer can be read straight from EventLog.
     */
    private fun logDiagnostics(root: File) {
        try {
            val rootChildren = root.list()?.sorted() ?: emptyList()
            EventLog.log("Media: 🔎 אבחון - תוכן שורש האחסון (${root.absolutePath}): ${if (rootChildren.isEmpty()) "(ריקה/לא נגיש)" else rootChildren.joinToString(" | ")}")

            val whatsappLike = rootChildren.filter { it.contains("whatsapp", ignoreCase = true) }
            if (whatsappLike.isNotEmpty()) {
                EventLog.log("Media: 🔎 אבחון - תיקיות שמכילות \"whatsapp\" בשורש: ${whatsappLike.joinToString(" | ")}")
                whatsappLike.forEach { name ->
                    val sub = File(root, name)
                    val subChildren = sub.list()?.sorted() ?: emptyList()
                    EventLog.log("Media: 🔎 אבחון - תוכן \"$name\": ${if (subChildren.isEmpty()) "(ריקה)" else subChildren.joinToString(" | ")}")
                }
            }

            val androidMedia = File(root, "Android/media")
            val androidMediaChildren = androidMedia.list()?.sorted() ?: emptyList()
            val waPkgLike = androidMediaChildren.filter { it.contains("whatsapp", ignoreCase = true) }
            EventLog.log("Media: 🔎 אבחון - חבילות עם \"whatsapp\" תחת Android/media: ${if (waPkgLike.isEmpty()) "(אין)" else waPkgLike.joinToString(" | ")}")
        } catch (e: Exception) {
            EventLog.log("Media: 🔎 אבחון נכשל: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
