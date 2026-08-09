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

- A Windows/Mac/Linux computer.
- [Android Studio](https://developer.android.com/studio) (free,
  official, download and install it like any normal application -
  the installer also installs the Android SDK you need).
- Either a real Android phone with WhatsApp installed, OR (since your
  setup uses NoxPlayer) you can side-load the built APK into
  NoxPlayer the same way you'd install any APK there.

## How to build it

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

## Once Phase 1 is confirmed working

Come back and we'll build Phase 2: a foreground service that polls
the same `?action=check` endpoint MacroDroid currently polls, and
uses an AccessibilityService (instead of MacroDroid's blind Wait-based
UI Interaction clicks) that waits for a real, confirmed
"WhatsApp conversation screen is now shown" event before clicking -
this directly targets the reliability problem we hit with MacroDroid
this session.
