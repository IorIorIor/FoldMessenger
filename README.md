# Fold Messenger

Internal messaging app for 8 Samsung Galaxy Fold phones. An operator sends text + image
from `sender.html` (any browser); each phone shows the message on the cover screen and,
when unfolded, full-size on the inner screen.

## How it works

```
sender.html ──HTTPS──> ntfy.sh (push service) ──WebSocket──> Fold Messenger app
```

- Each phone subscribes to its own topic (`fm-…-p1` … `fm-…-p8`) plus a broadcast topic (`fm-…-all`).
- The topic prefix is a **shared secret** — anyone who knows it can send to the phones.
  It lives in two places and must match: `Config.kt` (`TOPIC_BASE`) and `sender.html` (`TOPIC_BASE`).
  Change both + rebuild to rotate the secret.
- Images and videos are uploaded to ntfy.sh as attachments (max 15 MB); the app downloads
  them immediately, so the 3-hour attachment expiry on ntfy.sh doesn't matter.
- The person a secret belongs to travels in ntfy's `title` field as `p<roster position>`;
  the phone looks up the matching avatar and name from its own baked-in roster.

⚠️ **ntfy.sh free tier has a daily bandwidth cap.** Heavy testing with full-size images
already hit it once (`HTTP 413 attachment too large, or bandwidth limit reached`). Before
a live event either keep attachments small (the sender compresses them), buy an ntfy plan,
or self-host ntfy and change `NTFY_SERVER` in `Config.kt` and `SERVER` in `sender.html`.

## Tables and players

Three tables of six players are baked into the APK. Only one is in play at a
time: the admin picks a table in the sender and every phone follows.

A player's seat **is** their phone number — the first name at a table is on
phone 1 — so a handset works out who is holding it from its own number, with
nothing to set up on the night. That mapping is what lets the closing question
leave your own face out and label your answer.

To swap in the real cast:

1. edit the three `table_<n>_names` arrays in `app/src/main/res/values/tables.xml`,
   keeping six names per table, in phone order
2. drop square photos in as
   `app/src/main/res/drawable-nodpi/avatar_t<table>_p<phone>.png`
   (`avatar_t2_p3.png` is the third player at table two — the one on phone 3)
3. rebuild and release

The 18 faces currently in the repo are **placeholders** (coloured circles with an
initial). Avatars are centre-cropped to a circle, so square images look best.

## Sending secrets

Secrets are made in advance: the text, name and avatar are part of the artwork,
so the sender only picks phones and uploads the image or video. The optional text
box adds a caption underneath. A photo or clip is shown at about 60% of the
screen, floating on the live shader background; add a caption and the card comes
back behind it so the words have something to sit on.

## Background

The background is the live [UfoldedFX](https://github.com/IorIorIor/UfoldedFX) aura-heart
shader rather than a baked PNG. `app/src/main/assets/heart-view.html` is a self-contained
WebGL page (no network requests) rendered in a WebView behind the viewer; `FxBackground.kt`
animates it between four states as the app changes screen:

| app state | background state |
| --- | --- |
| idle, waiting | `IDLE` |
| cover screen, "New Secret!" | `NEW REVEAL` |
| reveal, text-only secret | `TEXT MESSAGE` |
| reveal, photo or clip | `MEDIA MESSAGE` |

Transitions are ~1.4 s eased and interrupt cleanly, so a burst of messages still looks
continuous. The animation is frozen in `onPause()` to save power.

To change the look, edit the states in the UfoldedFX app, then re-bake them there
(in a checkout of the UfoldedFX repo):

```sh
curl https://<your-railway-app>/api/presets > android/states.json
node android/build-viewer.js
```

and copy the regenerated `android/heart-view.html` into this repo's
`app/src/main/assets/`. The four state names above must keep their spelling —
`FxBackground` looks them up by name.

`bg_cover.png` is still used, but only for the lock-screen notification teaser, which
needs a static bitmap.

## Installing on each phone

1. Copy `FoldMessenger.apk` to the phone and open it (allow "install unknown apps" when prompted).
2. Open the app, tap the phone's number (1–8).
3. Allow **notifications** when asked.
4. Allow **"Appear on top"** when the settings page opens (lets the app pop the image
   onto the cover screen the moment a message arrives).
5. Allow **ignore battery optimizations** when asked (keeps the connection alive).
6. Allow **"install unknown apps"** for Fold Messenger when asked — this is what lets
   the app install its own updates later (see *Releases and auto-update*).
7. If a full-screen-intent settings page opens, enable it for Fold Messenger
   (lets a message take over the screen instantly).
8. Enable **Settings → Display → Continue apps on cover screen** for **Fold Messenger**,
   so the viewer keeps running on the cover screen when you fold the phone closed.
   (On the Z Fold line, apps run on the cover screen natively — an incoming message
   takes over the cover display without any extra setting. The "Apps allowed on cover
   screen" Labs toggle only exists on the Z Flip.)

A small persistent notification ("Listening as phone N") means the phone is connected.

## Sending

Open `sender.html` in any browser (double-click the file, or host it anywhere).
Pick target phones (or "All phones"), type text and/or attach an image, hit **Send**.
The log at the bottom shows per-phone delivery status.

Command-line alternative:

```bash
curl -d "Hello phone 3" https://ntfy.sh/fm-pw3h5q3z-p3
```

```bash
curl -T photo.jpg -H "X-Filename: photo.jpg" https://ntfy.sh/fm-pw3h5q3z-all
```

## Building the APK

Requires JDK 17 and the Android SDK (path in `local.properties`):

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/FoldMessenger-v<version>.apk` (debug-signed — fine
for sideloading, not for the Play Store).

**Versioning:** bump `versionCode` (integer, must always increase for in-place updates)
and `versionName` (shown in the filename) in `app/build.gradle.kts`, then commit and tag.

## Releases and auto-update

Pushing a version tag builds a signed APK in GitHub Actions and publishes it as a
GitHub Release:

```bash
git tag v1.8.1 && git push origin v1.8.1
```

Every phone polls the latest release every 15 minutes. When a newer version is out it
downloads the APK and opens Android's install dialog — someone taps **Update** once on
each phone. Sideloaded apps can't install silently; one tap per phone is the floor.
A live round is never interrupted: if a secret is currently on screen, the prompt waits
until the phone is back at idle.

**Signing.** Every build — local and CI — is signed with the same release keystore, which
is what lets updates install over the running app and keep each phone's number and setup.
`foldmessenger-release.keystore` and `keystore-password.txt` sit in the repo root but are
**git-ignored**; the same pair lives in GitHub Actions secrets as `FM_KEYSTORE_B64` and
`FM_KEYSTORE_PASSWORD`. Keep a backup — losing the keystore means every phone has to
uninstall and reinstall to take another update.

Building a release locally uses the same key:

```bash
./gradlew assembleRelease
```

## Known limitations

- Text + image only; video is a possible follow-up.
- Shows only the latest message (no history).
- ntfy.sh is a public service: the secret topic name is the only access control.
  Self-host ntfy and change `NTFY_SERVER` if you need more.
