package com.wabridge.app

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
        "Android/media/com.whatsapp/WhatsApp/Media", // new (post-2021 scoped storage) - WhatsApp's own auto-download cache
        "WhatsApp/Media",                              // legacy (storage root) - WhatsApp's own auto-download cache
        // FIX (22.8.2026): on-device log confirmed the accessibility
        // flow now genuinely finds and taps the real "שמירה"/"Save"
        // menu item (see FIX51/52 doc comments in
        // WaSendAccessibilityService) - but STILL nothing showed up in
        // either path above afterward. That's expected: a manual "Save"
        // from the full-screen viewer's overflow menu writes a COPY to
        // the device's regular Gallery, a different location entirely
        // from WhatsApp's own auto-download cache (which is what the
        // two paths above are). Gallery saves commonly land under
        // Pictures/WhatsApp Images or DCIM/WhatsApp Images depending on
        // Android version/OEM - both are tried now. (For non-image
        // types these combine into paths that don't exist, e.g.
        // "Pictures/WhatsApp Voice Notes" - harmless, just skipped.)
        "Pictures",
        "DCIM"
    )
    private const val SUBFOLDER_IMAGES = "WhatsApp Images"
    private const val SUBFOLDER_VIDEO = "WhatsApp Video"
    private const val SUBFOLDER_VOICE_NOTES = "WhatsApp Voice Notes"
    // FIX (28.8.2026, voice-note research): web research on where
    // WhatsApp actually stores voice messages turned up a SECOND,
    // differently-named folder used on some Android/WhatsApp versions -
    // "WhatsApp Audio" - distinct from "WhatsApp Voice Notes". Our own
    // on-device logs so far only ever checked the "Voice Notes" name
    // (and found it present but essentially empty, just a .nomedia
    // marker) - worth trying this second name too before concluding the
    // file isn't reachable via folder scan at all.
    private const val SUBFOLDER_VOICE_NOTES_ALT = "WhatsApp Audio"
    // FIX (28.8.2026, breakthrough): the 09:49 on-device log's raw
    // list() dump for "WhatsApp Audio" showed exactly 2 entries -
    // "Sent" and "Private" - which is why list()=2 but listFiles()=0
    // (they're subfolders, not files, so the isFile() filter correctly
    // excludes them - not a permission bug after all). The broader
    // recursive scan confirmed WhatsApp Audio itself has 0 direct files,
    // consistent with everything actually living one level deeper in
    // these two subfolders. "Private" = received in a 1:1 chat (our
    // case); "Sent" = recorded/sent by this device. Try both.
    private const val SUBFOLDER_VOICE_NOTES_AUDIO_PRIVATE = "WhatsApp Audio/Private"
    private const val SUBFOLDER_VOICE_NOTES_AUDIO_SENT = "WhatsApp Audio/Sent"

    // How far back (and slightly forward, to absorb clock/IO ordering
    // slack between "file written" and "notification posted") from the
    // notification's arrival time to consider a file a match. Widened
    // from 15s to 30s (19.8.2026) - real-device testing on the legacy
    // path found the folder but nothing inside the original window;
    // the diagnostic added alongside this will confirm the exact real
    // gap so this can be tuned precisely instead of guessed again.
    private const val MATCH_WINDOW_MS = 30_000L

    data class FoundMedia(val file: File, val mimeType: String)

    /**
     * FIX (23.8.2026, multi-media): same matching strategy as
     * findRecentMediaFile (newest files within the time window, via
     * MediaStore first), but returns UP TO [maxCount] distinct files
     * instead of just the single newest one - needed for albums (e.g.
     * "2 תמונות" / "5 photos"). [excludePaths] lets the caller skip
     * files that were already located/sent for an earlier, near-
     * duplicate notification of the very same album (see
     * WaNotificationListener's sent-file tracking) so the same image
     * is never attached twice while genuinely different images in the
     * same album are still picked up.
     *
     * KNOWN LIMITATION: this only finds files WhatsApp (or a prior
     * "force download" tap) already wrote to disk/MediaStore. If Media
     * Auto-Download is off and none of the album's images have been
     * opened/downloaded yet, this can only ever return the single image
     * that the force-download automation (MediaDownloadLearner) opened -
     * that automation currently taps ONE bubble, it does not yet swipe
     * through the full-screen album viewer to force-download every item.
     * See MediaDownloadLearner's doc comment for that follow-up.
     */
    fun findRecentMediaFiles(
        context: Context,
        type: MediaClassifier.MediaType,
        notificationTimeMs: Long,
        maxCount: Int,
        matchWindowMs: Long = MATCH_WINDOW_MS,
        excludePaths: Set<String> = emptySet()
    ): List<FoundMedia> {
        if (type == MediaClassifier.MediaType.NONE || maxCount <= 0) return emptyList()
        if (!isAvailable(context)) return emptyList()

        // Voice notes are frequently not MediaStore-indexed (see
        // findViaMediaStore's doc comment) - for that type, the best we
        // can do without a bigger rewrite of the folder-scan fallback is
        // the single-file path, so just wrap that.
        if (type == MediaClassifier.MediaType.VOICE_NOTE) {
            val single = findRecentMediaFile(context, type, notificationTimeMs, matchWindowMs)
            return if (single != null && single.file.absolutePath !in excludePaths) listOf(single) else emptyList()
        }

        return findMultipleViaMediaStore(context, type, notificationTimeMs, matchWindowMs, maxCount, excludePaths)
    }

    fun isAvailable(context: Context): Boolean {
        // FIX (21.8.2026): was hardcoded `true` below API 30 with the
        // reasoning "permission concept doesn't exist pre-Android 11" -
        // true for MANAGE_EXTERNAL_STORAGE specifically, but WRONG in
        // general: READ_EXTERNAL_STORAGE is still a required runtime
        // (dangerous) permission on API 23-29, and this app never
        // declared or requested it (see AndroidManifest.xml fix, same
        // date). On a pre-Android-11 device that never got asked for it,
        // this hardcoded `true` was a straight-up lie that made every
        // subsequent listFiles() call silently return empty - exactly
        // the "0 files" symptom confirmed on-device, including for
        // long-pre-existing files a separate file manager could see
        // just fine. Now actually checks the real OS-reported state on
        // both branches instead of assuming.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Returns the best-guess file for this media type near the given
     * notification timestamp, or null if the permission isn't granted,
     * no candidate folder exists, or nothing recent enough was found.
     */
    fun findRecentMediaFile(context: Context, type: MediaClassifier.MediaType, notificationTimeMs: Long, matchWindowMs: Long = MATCH_WINDOW_MS): FoundMedia? {
        if (type == MediaClassifier.MediaType.NONE) return null

        if (!isAvailable(context)) {
            Log.w(TAG, "Storage read permission not granted - cannot locate media file")
            EventLog.log("Media: ⚠️ אין הרשאת קריאת אחסון - לא ניתן לאתר את קובץ המדיה")
            return null
        }

        val subfolders = when (type) {
            MediaClassifier.MediaType.IMAGE -> listOf(SUBFOLDER_IMAGES)
            MediaClassifier.MediaType.VIDEO -> listOf(SUBFOLDER_VIDEO)
            MediaClassifier.MediaType.VOICE_NOTE -> listOf(
                SUBFOLDER_VOICE_NOTES_AUDIO_PRIVATE,
                SUBFOLDER_VOICE_NOTES_AUDIO_SENT,
                SUBFOLDER_VOICE_NOTES,
                SUBFOLDER_VOICE_NOTES_ALT
            )
            MediaClassifier.MediaType.NONE -> return null
        }

        // FIX (23.8.2026): on-device log finally showed all 8 real
        // overflow-menu item labels (previous dumps only showed empty
        // container text): כל המדיה / להציג בצ'אט / שיתוף / שמירה /
        // יצירת מדבקה / חיפוש באינטרנט / להגדיר בתור… / הצגה בגלריה.
        // "שמירה" was confirmed to genuinely get tapped (the menu closed
        // afterward, confirmed via the post-tap dump) - yet still no
        // file appeared in ANY of the 4 guessed folder paths (legacy +
        // new WhatsApp cache, Pictures, DCIM). The presence of a SEPARATE
        // "הצגה בגלריה" ("Show in Gallery") item alongside "שמירה"
        // strongly suggests the save genuinely succeeds but lands
        // somewhere none of our guessed paths cover. Rather than keep
        // guessing folder names one at a time, this now queries
        // MediaStore directly FIRST - the OS-level index of every media
        // file regardless of which physical folder it's actually in,
        // which WhatsApp's "Save" must insert into to be scoped-storage
        // compliant. Falls back to the old folder-scanning approach
        // below only if MediaStore has nothing recent. FIX (28.8.2026):
        // now also tried for VOICE_NOTE via the Audio collection - see
        // findViaMediaStore's own doc comment.
        findViaMediaStore(context, type, notificationTimeMs, matchWindowMs)?.let { return it }

        val root = Environment.getExternalStorageDirectory()
        // FIX (22.8.2026): this used to stop at the FIRST candidate
        // folder that merely EXISTED (isDirectory), even if nothing in
        // it matched - meaning once the legacy WhatsApp/Media path was
        // found to exist (which it always was, with old cached files),
        // no other candidate (including the newly-added Gallery paths
        // above) would ever even get checked. Now checks every existing
        // candidate folder and picks the best (newest) match across all
        // of them, so a real match in a later candidate isn't hidden by
        // an earlier candidate that merely exists but has nothing recent.
        val triedDirs = mutableListOf<File>()
        var bestFile: File? = null
        var bestDir: File? = null

        for (basePath in CANDIDATE_BASE_PATHS) {
            for (subfolder in subfolders) {
            val candidateDir = File(root, "$basePath/$subfolder")
            if (!candidateDir.isDirectory) continue
            triedDirs.add(candidateDir)

            val filesHere = candidateDir.listFiles { f -> f.isFile } ?: emptyArray()
            // FIX (01.9.2026, cleanup): the "0 files here" mystery this used
            // to log verbosely for every candidate folder on every call is
            // solved and documented (see CANDIDATE_BASE_PATHS/SUBFOLDER_*
            // comments above) - WhatsApp's own "WhatsApp Audio" folder here
            // is expected to be empty of direct files (it only holds the
            // Sent/Private subfolders, and the real save now lands in
            // Downloads via the SAF "Save as…" flow instead). No longer
            // worth logging on every routine check.


            val matchHere = filesHere
                .filter { kotlin.math.abs(it.lastModified() - notificationTimeMs) <= matchWindowMs }
                .maxByOrNull { it.lastModified() }
            if (matchHere != null && (bestFile == null || matchHere.lastModified() > bestFile!!.lastModified())) {
                bestFile = matchHere
                bestDir = candidateDir
            }
            }
        }

        if (triedDirs.isEmpty()) {
            val tried = CANDIDATE_BASE_PATHS.flatMap { base -> subfolders.map { sf -> File(root, "$base/$sf").absolutePath } }.joinToString(" , ")
            Log.w(TAG, "Media folder not found in any candidate path: $tried")
            EventLog.log("Media: ⚠️ תיקיית מדיה לא נמצאה באף אחד מהנתיבים: $tried")
            logDiagnostics(root)
            return null
        }

        val best = bestFile

        if (best == null) {
            Log.w(TAG, "No recent file matched in any of: ${triedDirs.joinToString(" , ") { it.absolutePath }} within ${matchWindowMs}ms of $notificationTimeMs")
            if (type == MediaClassifier.MediaType.VOICE_NOTE) {
                // FIX (01.9.2026, cleanup): on-device testing confirmed voice
                // notes now reliably land in the plain Downloads folder via
                // the "Save as…" automation (WaSendAccessibilityService) -
                // check there FIRST, cheaply, before running the expensive
                // forensic folder scan below. That scan produces ~40 log
                // lines every single time and is only worth that noise if
                // Downloads ALSO comes up empty (a genuine, unexpected
                // failure) - not on every routine "not saved yet" check,
                // which is the common case (this locator runs once right
                // when the notification arrives, before the save has even
                // been triggered).
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val downloadsMatch = downloadsDir.listFiles { f -> f.isFile }
                    ?.filter { kotlin.math.abs(it.lastModified() - notificationTimeMs) <= matchWindowMs }
                    ?.maxByOrNull { it.lastModified() }
                if (downloadsMatch != null) {
                    EventLog.log("Media: ✅ נמצא קובץ בתיקיית ההורדות (דרך \"Save as…\"): ${downloadsMatch.name}")
                    return FoundMedia(downloadsMatch, guessMimeType(downloadsMatch.name))
                }
                EventLog.log("Media: ⚠️ לא נמצא קובץ תואם בזמן באף אחת מ-${triedDirs.size} תיקיות שנבדקו, ולא בתיקיית ההורדות")
                triedDirs.forEach { d -> logCandidateTimings(d, d.listFiles { f -> f.isFile } ?: emptyArray(), notificationTimeMs) }
                // Only reached if Downloads genuinely didn't have it either -
                // worth the full forensic scan at that point.
                logDiagnostics(root)
                // FIX (28.8.2026, root-cache research): every accessible
                // path has now been exhausted (shared storage, MediaStore,
                // UI menus). Forensic sources confirm WhatsApp genuinely
                // caches voice-note audio at /data/data/com.whatsapp/cache/
                // before it's moved/purged - but that path is inside
                // WhatsApp's own private, UID-sandboxed storage, which no
                // app (including this one) can read without root, by
                // Android's core security model - no API or permission
                // bypasses that. This only does anything on a rooted
                // device: uses `su` to copy a recent matching file out to
                // OUR app's own accessible storage, then reads it normally
                // from there. Safe no-op (returns null quickly) if root
                // isn't available.
                findViaRootCache(context, notificationTimeMs, matchWindowMs)?.let { rootFile ->
                    EventLog.log("Media: ✅ נמצא קובץ: ${rootFile.name} (דרך root, מטמון פרטי)")
                    return FoundMedia(rootFile, guessMimeType(rootFile.name))
                }
            } else {
                EventLog.log("Media: ⚠️ לא נמצא קובץ תואם בזמן באף אחת מ-${triedDirs.size} תיקיות שנבדקו")
                triedDirs.forEach { d -> logCandidateTimings(d, d.listFiles { f -> f.isFile } ?: emptyArray(), notificationTimeMs) }
            }
            return null
        }
        val dir = bestDir!!

        val mimeType = guessMimeType(best.name)
        Log.i(TAG, "Matched media file: ${best.absolutePath} (mime=$mimeType)")
        EventLog.log("Media: ✅ נמצא קובץ: ${best.name} (ב-${dir.absolutePath})")
        return FoundMedia(best, mimeType)
    }

    /**
     * Queries MediaStore for the newest IMAGE/VIDEO/VOICE_NOTE row added
     * within the match window, regardless of which physical folder it
     * lives in - see the long FIX (23.8.2026) comment in
     * findRecentMediaFile for why this exists. MediaStore's DATE_ADDED/
     * DATE_MODIFIED columns are in SECONDS (not ms) - converted
     * accordingly. FIX (28.8.2026): VOICE_NOTE used to be skipped here on
     * an untested assumption that voice notes are never MediaStore-
     * indexed - now actually queries the Audio collection to check for
     * real, since that assumption was never verified against Audio
     * specifically (only Images/Video were ever tried).
     */
    private fun findViaMediaStore(context: Context, type: MediaClassifier.MediaType, notificationTimeMs: Long, matchWindowMs: Long): FoundMedia? {
        val collection = when (type) {
            MediaClassifier.MediaType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaClassifier.MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            // FIX (28.8.2026, voice-note research): previously skipped
            // entirely on the assumption voice notes are "frequently not
            // MediaStore-indexed at all" - but that assumption was never
            // actually tested against the Audio collection specifically
            // (only Images/Video were ever queried). WhatsApp voice notes
            // are real .opus/audio files; if WhatsApp (or the eventual
            // real save flow) writes them through a MediaStore-compliant
            // API, they'd show up here under Audio, not Images/Video.
            // Worth checking for real instead of assuming.
            MediaClassifier.MediaType.VOICE_NOTE -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> return null
        }


        val windowSec = matchWindowMs / 1000
        val notificationSec = notificationTimeMs / 1000
        val minSec = notificationSec - windowSec
        val maxSec = notificationSec + windowSec

        // MediaStore.MediaColumns.DATA is deprecated but still populated
        // with a real filesystem path on devices where the app holds
        // MANAGE_EXTERNAL_STORAGE/READ_EXTERNAL_STORAGE (which this app
        // requires anyway - see isAvailable()), so it's fine to rely on
        // here rather than juggling a separate Uri-based read path just
        // for this one case.
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        val selection = "${MediaStore.MediaColumns.DATE_ADDED} BETWEEN ? AND ?"
        val selectionArgs = arrayOf(minSec.toString(), maxSec.toString())
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        try {
            val cursor: Cursor? = context.contentResolver.query(
                collection, projection, selection, selectionArgs, sortOrder
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val dataCol = it.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val nameCol = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val path = if (dataCol >= 0) it.getString(dataCol) else null
                    val name = if (nameCol >= 0) it.getString(nameCol) else null
                    if (path != null) {
                        val file = File(path)
                        if (file.isFile) {
                            EventLog.log("Media: ✅ נמצא קובץ דרך MediaStore: ${name ?: file.name} (${file.absolutePath})")
                            return FoundMedia(file, guessMimeType(file.name))
                        }
                    }
                }
            }
            EventLog.log("Media: 🔎 אבחון - שאילתת MediaStore לא מצאה תוצאה בחלון הזמן (${minSec}-${maxSec})")
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore query failed", e)
            EventLog.log("Media: 🔎 שאילתת MediaStore נכשלה: ${e.javaClass.simpleName}: ${e.message}")
        }
        return null
    }

    /**
     * Same query as findViaMediaStore but without LIMIT 1 semantics -
     * walks the whole (small, time-windowed) result set and collects up
     * to [maxCount] distinct files, skipping [excludePaths]. See
     * findRecentMediaFiles's doc comment for the album use case this
     * exists for.
     */
    private fun findMultipleViaMediaStore(
        context: Context,
        type: MediaClassifier.MediaType,
        notificationTimeMs: Long,
        matchWindowMs: Long,
        maxCount: Int,
        excludePaths: Set<String>
    ): List<FoundMedia> {
        val collection = when (type) {
            MediaClassifier.MediaType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaClassifier.MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> return emptyList()
        }

        val windowSec = matchWindowMs / 1000
        val notificationSec = notificationTimeMs / 1000
        val minSec = notificationSec - windowSec
        val maxSec = notificationSec + windowSec

        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val selection = "${MediaStore.MediaColumns.DATE_ADDED} BETWEEN ? AND ?"
        val selectionArgs = arrayOf(minSec.toString(), maxSec.toString())
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        val results = mutableListOf<FoundMedia>()
        // FIX (24.8.2026, duplicate-row bug): confirmed on-device - a
        // single MediaStore query call, 0 swipes involved, still
        // returned the SAME file path twice as if they were 2 distinct
        // photos (padding a real 2-photo result out to a false "3/3").
        // MediaStore can genuinely contain more than one row for the
        // same physical path (e.g. after a rescan re-indexes a file
        // that was already indexed, without cleaning up the old row).
        // The `path in excludePaths` check below only guards against
        // paths the CALLER already knew about across separate calls -
        // it never protected against the SAME query returning one path
        // twice. Tracked here with a local set, independent of and in
        // addition to excludePaths.
        val seenPaths = mutableSetOf<String>()
        // FIX (27.8.2026, same-content-different-name duplicate bug):
        // confirmed on a real 6-photo album - a stuck swipe re-tapped
        // "Save" on an image the viewer never actually advanced past,
        // and WhatsApp wrote that re-save to disk under its own auto
        // "(N)" collision suffix (e.g. "IMG-...WA0001.jpg" already
        // existed, so the re-save landed as "IMG-...WA0001(2).jpg").
        // That's a genuinely NEW row/path in MediaStore, so seenPaths
        // above never caught it - the query happily counted it as a
        // distinct 6th photo, silently padding the result out to the
        // expected album size while actually attaching one image twice
        // and never reaching the real, different 6th photo at all. The
        // accessibility service already had a same-base-name heuristic
        // (stripDuplicateSuffix) to stop TRUSTING such a file as swipe
        // progress, but that heuristic never touched the file list that
        // actually gets attached and sent - this is the one place that
        // list is assembled for every caller, so the dedupe belongs
        // here: skip any row whose base name (with WhatsApp's "(N)"
        // suffix stripped) was already accepted, and keep scanning the
        // cursor for a genuinely different photo instead of stopping at
        // maxCount rows.
        val seenBaseNames = mutableSetOf<String>()
        try {
            val cursor: Cursor? = context.contentResolver.query(
                collection, projection, selection, selectionArgs, sortOrder
            )
            cursor?.use {
                val dataCol = it.getColumnIndex(MediaStore.MediaColumns.DATA)
                val nameCol = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                while (it.moveToNext() && results.size < maxCount) {
                    val path = if (dataCol >= 0) it.getString(dataCol) else null
                    val name = if (nameCol >= 0) it.getString(nameCol) else null
                    if (path == null || path in excludePaths) continue
                    if (!seenPaths.add(path)) continue
                    val file = File(path)
                    if (!file.isFile) continue
                    val baseName = stripDuplicateSuffix(file.name)
                    if (!seenBaseNames.add(baseName)) {
                        EventLog.log("Media: 🔁 מדלג - '${file.name}' נראה כשמירה חוזרת של תוכן שכבר נמצא (אותו שם בסיס: '$baseName')")
                        continue
                    }
                    results.add(FoundMedia(file, guessMimeType(file.name)))
                    EventLog.log("Media: ✅ נמצא קובץ נוסף דרך MediaStore (${results.size}/$maxCount): ${name ?: file.name}")
                }
            }
            if (results.isEmpty()) {
                EventLog.log("Media: 🔎 אבחון - שאילתת MediaStore (ריבוי) לא מצאה תוצאה בחלון הזמן (${minSec}-${maxSec})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore multi-query failed", e)
            EventLog.log("Media: 🔎 שאילתת MediaStore (ריבוי) נכשלה: ${e.javaClass.simpleName}: ${e.message}")
        }
        return results
    }

    // FIX (27.8.2026): moved here (was private to WaSendAccessibilityService)
    // since the dedupe it powers now lives in the MediaStore query itself -
    // see the FIX (27.8.2026, same-content-different-name duplicate bug)
    // doc comment above in findMultipleViaMediaStore. Strips Android/
    // WhatsApp's auto-appended "(N)" collision suffix from a filename,
    // e.g. "IMG-20260823-WA0009(1).jpg" -> "IMG-20260823-WA0009.jpg" -
    // that suffix is added when something tries to save a file under a
    // name that already exists on disk, which is a strong signal it's
    // the same re-saved content, not a genuinely different photo that
    // coincidentally got the same WhatsApp-assigned base name.
    private val DUPLICATE_SUFFIX_REGEX = Regex("""^(.*?)\(\d+\)(\.[^.]+)?$""")

    private fun stripDuplicateSuffix(fileName: String): String {
        return DUPLICATE_SUFFIX_REGEX.replace(fileName, "$1$2")
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
            // FIX (28.8.2026, root-check): after fully exhausting every
            // shared-storage path, MediaStore, and UI menu for voice
            // notes (all confirmed empty/dead-end), the next real option
            // depends entirely on whether this device/emulator has root -
            // that would allow reading WhatsApp's private app storage
            // directly. User wasn't sure, so check for the common
            // indicators here (read-only, side-effect-free) and log the
            // result plainly instead of guessing.
            val rootIndicatorPaths = listOf(
                "/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/system/app/Superuser.apk", "/system/app/SuperSU.apk"
            )
            val foundRootPaths = rootIndicatorPaths.filter { File(it).exists() }
            val testKeys = Build.TAGS?.contains("test-keys") == true
            val whichSuFound = try {
                val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                output.isNotEmpty()
            } catch (e: Exception) {
                false
            }
            EventLog.log(
                "Media: 🔎 אבחון root - נתיבי su שנמצאו: ${if (foundRootPaths.isEmpty()) "(אין)" else foundRootPaths.joinToString(" | ")} | " +
                    "Build.TAGS מכיל test-keys=$testKeys | which su מצא=$whichSuFound"
            )

            val rootChildren = root.list()?.sorted() ?: emptyList()
            EventLog.log("Media: 🔎 אבחון - תוכן שורש האחסון (${root.absolutePath}): ${if (rootChildren.isEmpty()) "(ריקה/לא נגיש)" else rootChildren.joinToString(" | ")}")

            val whatsappLike = rootChildren.filter { it.contains("whatsapp", ignoreCase = true) }
            if (whatsappLike.isNotEmpty()) {
                EventLog.log("Media: 🔎 אבחון - תיקיות שמכילות \"whatsapp\" בשורש: ${whatsappLike.joinToString(" | ")}")
                whatsappLike.forEach { name ->
                    val sub = File(root, name)
                    val subChildren = sub.list()?.sorted() ?: emptyList()
                    EventLog.log("Media: 🔎 אבחון - תוכן \"$name\": ${if (subChildren.isEmpty()) "(ריקה)" else subChildren.joinToString(" | ")}")
                    // FIX (28.8.2026, method B): previously stopped here -
                    // never actually looked INSIDE "Media" to see the real
                    // subfolder names/contents (WhatsApp Images/Video/Voice
                    // Notes/Audio/etc.), only confirmed "Media" itself
                    // exists as a name. Recurse one more level into it.
                    val mediaDir = File(sub, "Media")
                    if (mediaDir.isDirectory) {
                        val mediaChildren = mediaDir.list()?.sorted() ?: emptyList()
                        EventLog.log("Media: 🔎 אבחון - תוכן \"$name/Media\": ${if (mediaChildren.isEmpty()) "(ריקה)" else mediaChildren.joinToString(" | ")}")
                        mediaChildren.forEach { mName ->
                            val mSub = File(mediaDir, mName)
                            val mSubCount = mSub.listFiles { f -> f.isFile }?.size ?: -1
                            EventLog.log("Media: 🔎 אבחון -   \"$name/Media/$mName\" - $mSubCount קבצים")
                        }
                    }
                }
            }

            val androidMedia = File(root, "Android/media")
            val androidMediaChildren = androidMedia.list()?.sorted() ?: emptyList()
            val waPkgLike = androidMediaChildren.filter { it.contains("whatsapp", ignoreCase = true) }
            EventLog.log("Media: 🔎 אבחון - חבילות עם \"whatsapp\" תחת Android/media: ${if (waPkgLike.isEmpty()) "(אין)" else waPkgLike.joinToString(" | ")}")
            // FIX (28.8.2026, method B): previously stopped at confirming
            // the package folder's NAME exists, never looked inside it -
            // the 19.8.2026 comment above ("completely empty") may be
            // stale for this device/WhatsApp version. Recurse two levels
            // in for every whatsapp-like package found under Android/media.
            waPkgLike.forEach { pkgName ->
                val pkgDir = File(androidMedia, pkgName)
                val pkgChildren = pkgDir.list()?.sorted() ?: emptyList()
                EventLog.log("Media: 🔎 אבחון - תוכן \"Android/media/$pkgName\": ${if (pkgChildren.isEmpty()) "(ריקה)" else pkgChildren.joinToString(" | ")}")
                pkgChildren.forEach { childName ->
                    val childDir = File(pkgDir, childName)
                    if (childDir.isDirectory) {
                        val grandChildren = childDir.list()?.sorted() ?: emptyList()
                        EventLog.log("Media: 🔎 אבחון -   תוכן \"Android/media/$pkgName/$childName\": ${if (grandChildren.isEmpty()) "(ריקה)" else grandChildren.joinToString(" | ")}")
                    }
                }
            }
        } catch (e: Exception) {
            EventLog.log("Media: 🔎 אבחון נכשל: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * FIX (28.8.2026, root-cache research): WhatsApp genuinely caches
     * voice-note audio at /data/data/com.whatsapp/cache/ before it's
     * moved or purged (confirmed via forensic-extraction sources) - but
     * that's inside WhatsApp's own UID-sandboxed private storage, which
     * Android's kernel-level permission model blocks every other app
     * from reading, root or not being an OS-level distinction no app
     * permission can bypass. This only ever does anything on a rooted
     * device: shells out to `su -c` to (1) list recent files in that
     * cache dir sorted by modification time, (2) copy the newest opus/
     * audio-looking one within the match window into OUR OWN app's
     * external files dir (which we can read normally, no su needed after
     * that), then returns it like any other found file. Every step is
     * wrapped so a non-rooted device just gets null quickly, same as
     * before this existed.
     */
    private fun findViaRootCache(context: Context, notificationTimeMs: Long, matchWindowMs: Long): File? {
        return try {
            val listProcess = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "ls -t --full-time /data/data/com.whatsapp/cache/ 2>/dev/null")
            )
            val listing = listProcess.inputStream.bufferedReader().readText()
            listProcess.waitFor()
            if (listing.isBlank()) {
                EventLog.log("Media: 🔎 [root] אין גישת su, או שהתיקייה הפרטית ריקה/לא נגישה")
                return null
            }
            EventLog.log("Media: 🔎 [root] תוכן /data/data/com.whatsapp/cache/: ${listing.lines().filter { it.isNotBlank() }.take(10).joinToString(" | ")}")

            // Names likely to be voice-note audio, not thumbnails/junk.
            val audioLine = listing.lines().firstOrNull {
                it.contains(".opus", ignoreCase = true) || it.contains(".mp3", ignoreCase = true) || it.contains(".m4a", ignoreCase = true)
            }
            val fileName = audioLine?.trim()?.substringAfterLast(' ')
            if (fileName.isNullOrBlank()) {
                EventLog.log("Media: 🔎 [root] לא נמצא קובץ אודיו (.opus/.mp3/.m4a) ברשימה")
                return null
            }

            val destDir = File(context.getExternalFilesDir(null), "root_cache_copies").apply { mkdirs() }
            val destFile = File(destDir, fileName)
            val copyProcess = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "cp '/data/data/com.whatsapp/cache/$fileName' '${destFile.absolutePath}'")
            )
            copyProcess.waitFor()

            if (!destFile.exists() || destFile.length() == 0L) {
                EventLog.log("Media: 🔎 [root] ההעתקה דרך su נכשלה או הקובץ ריק")
                return null
            }

            val diffSec = kotlin.math.abs(destFile.lastModified() - notificationTimeMs) / 1000.0
            EventLog.log("Media: ✅ [root] הועתק קובץ מהמטמון הפרטי: $fileName (הפרש מההתראה: ${"%.1f".format(diffSec)} שנ')")
            destFile
        } catch (e: Exception) {
            // Expected/normal on a non-rooted device - `su` simply
            // doesn't exist, which throws an IOException here.
            EventLog.log("Media: 🔎 [root] su לא זמין (${e.javaClass.simpleName}) - כנראה אין root במכשיר")
            null
        }
    }

    /**
     * DIAGNOSTIC (19.8.2026) - the folder itself was found this time
     * (legacy path confirmed working), but nothing matched within the
     * 15s window. Logs the actual candidate files and exactly how far
     * off (in seconds) each one's lastModified() is from the
     * notification's postTime - so we can tell, from real numbers,
     * whether MATCH_WINDOW_MS just needs widening or something else is
     * going on (e.g. empty folder, or timestamps wildly off).
     */
    private fun logCandidateTimings(dir: File, candidates: Array<File>, notificationTimeMs: Long) {
        try {
            if (candidates.isEmpty()) {
                EventLog.log("Media: 🔎 אבחון - התיקייה \"${dir.absolutePath}\" ריקה לגמרי (0 קבצים)")
                return
            }
            val newest = candidates.sortedByDescending { it.lastModified() }.take(5)
            EventLog.log("Media: 🔎 אבחון - ${candidates.size} קבצים בתיקייה, 5 החדשים ביותר:")
            newest.forEach { f ->
                val diffSec = (f.lastModified() - notificationTimeMs) / 1000.0
                val sign = if (diffSec >= 0) "+" else ""
                EventLog.log("Media: 🔎 אבחון -   ${f.name} (הפרש מההתראה: $sign${"%.1f".format(diffSec)} שנ')")
            }
        } catch (e: Exception) {
            EventLog.log("Media: 🔎 אבחון נכשל: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
