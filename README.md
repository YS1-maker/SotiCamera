# SotiCamera

An Android camera application managed via SOTI MobiControl.

## MobiControl Scripts

### Launch the Application

Send the following script via MobiControl to start the SotiCamera app on a managed device:

```
sendintent -a "intent:#Intent;action=android.intent.action.MAIN;component=com.soti.soticamera/.MainActivity;end"
```

### Show a Message Over the Captured Image

Use MobiControl's native `showmessagebox` command to display a dialog on top of the captured image:

```
showmessagebox "Your message here" NO_TIMER 1
```

**Parameters:**
- First argument — the message text (quote if it contains spaces)
- `NO_TIMER` — dialog stays until dismissed; replace with a number (seconds) for auto-dismiss
- `1` — dialog type: `1`=Info, `2`=Yes/No, `3`=Warning, `4`=OK/Cancel, `5`=Error

**Example — auto-dismiss info after 10 seconds:**
```
showmessagebox "Compliance check complete" 10 1
```

`showmessagebox` is a system-level overlay executed by the MobiControl agent (device owner), so it appears on top of any running app including the fullscreen image view. When the dialog is dismissed the app automatically restores its immersive fullscreen mode.

---

## Project Context

**SotiCamera** is a silent surveillance Android app deployed and controlled via SOTI MobiControl MDM.

- Package: `com.soti.soticamera`
- Git repo: `YS1-maker/SotiCamera` (branch: `main`)
- Build system: Gradle with Kotlin DSL

### How It Works

1. **MainActivity.kt** — On launch, silently opens the front camera (no preview shown to the user), waits 800ms for the sensor to stabilise, then captures a photo saved as `BG1.jpg` in `Pictures/SOTI/` on the device. Before capturing, it deletes any existing SOTI images so MediaStore doesn't rename the file to `BG1(1).jpg`. After capture, it uploads the image to a GitHub repo via the GitHub Contents API (base64 encoded, PUT request), then launches `FullScreenActivity` and finishes itself.

2. **CaptureReceiver.kt** — A `BroadcastReceiver` that listens for the action `com.soti.soticamera.CAPTURE` and launches `MainActivity`. This allows MobiControl to remotely trigger a photo capture.

3. **FullScreenActivity.kt** — Displays the captured photo fullscreen (EXIF-rotation corrected), keeps the screen on, hides system bars (immersive mode), shows a blinking red recording dot, and blocks the back button (app lifecycle is managed by MobiControl).

### Key Details

- GitHub token is hardcoded in `MainActivity` for repo `YS1-maker/SotiCamera`
- Upload uses GitHub REST API v3: `PUT /repos/{owner}/{repo}/contents/images/BG1.jpg`
- If `BG1.jpg` already exists on GitHub, the SHA is fetched first and included in the PUT body
- Camera uses CameraX library, front-facing camera, `MINIMIZE_LATENCY` capture mode
- App lifecycle (launch/kill) is managed externally by SOTI MobiControl MDM

### Source Files

| File | Purpose |
|------|---------|
| `app/src/main/java/com/soti/soticamera/MainActivity.kt` | Camera capture + GitHub upload |
| `app/src/main/java/com/soti/soticamera/CaptureReceiver.kt` | MDM broadcast trigger |
| `app/src/main/java/com/soti/soticamera/FullScreenActivity.kt` | Fullscreen image display |
| `app/src/main/AndroidManifest.xml` | Permissions + component registration |
| `app/src/main/res/layout/activity_main.xml` | Main activity layout |
| `app/src/main/res/layout/activity_fullscreen.xml` | Fullscreen layout with blinking dot |
