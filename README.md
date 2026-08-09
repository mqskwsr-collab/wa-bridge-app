# WA Bridge - Phase 1 (Android app, replaces MacroDroid macro #1)

This is a real, buildable Android Studio project. It does ONE thing:
reads incoming WhatsApp notifications and forwards them (title + text)
to your EXISTING Apps Script Web App - the exact same endpoint
MacroDroid's macro #1 ("בדיקה - נוטיפיקציה") already posts to.

**No changes to Code.gs / Apps Script are needed for this phase.** The
app sends the identical JSON shape `{"title":"...","text":"..."}` that
`doPost()` already expects.

This is intentionally the SMALLEST possible first step, so you can
confirm the whole toolchain (Android Studio -> build -> install ->
grant permission -> real WhatsApp message -> email arrives) works
before we build the harder part (auto-sending replies back, which
needs Accessibility and will come in Phase 2).

## What this does NOT do yet

- Does NOT send replies back into WhatsApp (that's Phase 2 - macro
  #2/#3 replacement).
- Does NOT handle media (images/video/voice) yet (Phase 3).
- Does NOT classify group vs. private - it just forwards raw
  title/text, exactly like MacroDroid does, and lets your existing
  Code.gs `classifyNotification()` handle that server-side, unchanged.

## Requirements

- Either: a Windows/Mac/Linux computer with [Android Studio](https://developer.android.com/studio) installed, OR
- (Recommended, no installs needed): a free [GitHub](https://github.com) account - GitHub's own servers build the APK for you, in the cloud, and you download the finished file.
- Either a real Android phone with WhatsApp installed, OR (since your
  setup uses NoxPlayer) you can side-load the built APK into
  NoxPlayer the same way you'd install any APK there.

## How to build it - Option A: GitHub Actions (cloud build, nothing to install)

This folder already includes `.github/workflows/build.yml`, which
tells GitHub to compile the APK automatically.

1. Unzip this project on your computer (if you haven't already).
2. Go to https://github.com, sign up (free) or log in.
3. Click the "+" in the top-right corner -> "New repository". Give it
   any name (e.g. `wa-bridge-app`), choose Public or Private (either
   is fine - no secrets/passwords are hardcoded in this code), leave
   everything else default, click "Create repository".
4. On the new (empty) repository page, click "uploading an existing
   file" (or "Add file" -> "Upload files").
5. Open the unzipped `wa-bridge-app` folder on your computer, select
   ALL its contents (not the outer folder itself, its contents:
   `app`, `.github`, `build.gradle.kts`, `settings.gradle.kts`,
   `gradle.properties`, `README.md`), and drag them into the browser
   upload area. Wait for the upload to finish, then click
   "Commit changes".
6. Click the "Actions" tab at the top of your repository. You should
   see a workflow run called "Build Debug APK" already running (a
   yellow/orange dot = in progress). If you don't see one, click
   "Build Debug APK" on the left, then "Run workflow" -> "Run
   workflow".
7. Wait about 3-5 minutes. When the dot turns green (success), click
   into that run, scroll down to "Artifacts", and click
   "wa-bridge-debug-apk" to download it - it's a small zip containing
   `app-debug.apk`.
8. Unzip that, and you have your real, installable APK file
   (`app-debug.apk`).
9. Transfer it into NoxPlayer the same way you'd install any APK
   there (commonly: drag-and-drop the .apk file straight onto the
   NoxPlayer window, or use NoxPlayer's built-in APK installer
   icon/tool), and install it.

If the build fails (red X), open the failed run and send me a
screenshot of the error - I'll fix the project files accordingly.

## How to build it - Option B: Android Studio (local, needs install)

1. Install Android Studio, open it.
2. Choose "Open" (not "New Project") and select this whole folder
   (`wa-bridge-app`).
3. Android Studio will show a banner asking to create/sync the Gradle
   wrapper - click "OK"/"Sync Now". First sync can take a few minutes
   (it downloads the Android SDK build tools).
4. Once sync finishes with no red errors, click the green ▶ (Run)
   button at the top, with your phone (or NoxPlayer's ADB connection)
   selected as the target device.
5. The app installs and opens automatically.

## First-time setup on the device (do this once)

1. In the app, paste your existing Apps Script Web App URL into the
   text field (the SAME URL already in Code.gs / MacroDroid, e.g.
   `https://script.google.com/macros/s/AKfycby.../exec`) and tap
   "שמור כתובת".
2. Tap "פתח הגדרות גישה להתראות" - this opens Android's Notification
   Access settings. Find "WA Bridge" in the list and toggle it ON.
   Android will show a warning about the app being able to read your
   notifications - this is expected and required, tap "Allow"/"OK".
3. Go back into the app - the status line should now show
   "✅ גישה להתראות מאושרת".

## Testing

Send yourself (or have someone send) a WhatsApp message on the
regular number (0553169395). Within a second or two, check Android's
Logcat in Android Studio (filter by tag "WaBridge") to see the
notification being read and the POST result - and check your Gmail
inbox for the forwarded email, exactly like macro #1 currently
produces.

## Important note about NoxPlayer specifically

NotificationListenerService relies on Android's real notification
system. This should work the same way inside NoxPlayer as on a real
phone, since NoxPlayer emulates a full Android notification shade -
but this hasn't been verified in this project yet. If notifications
don't seem to trigger the service at all inside NoxPlayer, that's the
first thing to debug (check that WhatsApp's notifications are even
appearing in NoxPlayer's status bar / notification drawer, since some
Android emulator setups suppress the status bar by default).

## Phase 2 (now included): sending replies back into WhatsApp

This adds two new components, replacing MacroDroid macros #2 and #3
entirely:

- **PollingService** - a foreground service that polls the same
  `?action=check` endpoint MacroDroid polled, launches WhatsApp to
  the right chat/group, and calls `?action=markSent` once the send is
  confirmed.
- **WaSendAccessibilityService** - instead of MacroDroid's fixed pixel
  coordinates and blind `Wait` actions, this listens for Android's own
  "WhatsApp's window actually changed" event, then searches the real
  on-screen UI tree for the message box and send button (by role, not
  position) and only clicks once they're genuinely present. This
  directly targets the reliability problem from the MacroDroid
  sessions (screen-focus timing).

### One-time setup (in the app, after installing the new APK)

1. Enter/confirm the Web App URL and save it, same as Phase 1.
2. Grant Notification access (same as Phase 1), if not already done.
3. Tap "פתח הגדרות נגישות (Accessibility)" - find "WA Bridge" in the
   list, tap it, and toggle it ON. Android will show a warning about
   this permission being powerful (it can view screen content and
   perform actions) - this is expected, it's exactly what's needed to
   click Send. Tap "Allow"/"OK".
4. Back in the app, both status lines should show green checkmarks.
5. Tap "התחל שירות שליחה" (Start sending service) - a persistent
   notification appears confirming it's running.

### Important: turn off MacroDroid macros #2 and #3

Once this is running, MacroDroid's macros #2 and #3 are fully
redundant (and would race with this app if both are polling/sending
at once) - disable their switches in MacroDroid. Macro #1 can also be
disabled since Phase 1 of this app already replaces it.

## Once Phase 1 is confirmed working

Come back and we'll build Phase 2: a foreground service that polls
the same `?action=check` endpoint MacroDroid currently polls, and
uses an AccessibilityService (instead of MacroDroid's blind Wait-based
UI Interaction clicks) that waits for a real, confirmed
"WhatsApp conversation screen is now shown" event before clicking -
this directly targets the reliability problem we hit with MacroDroid
this session.
