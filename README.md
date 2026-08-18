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

## Running from the laptop (optional, faster)

The phones can talk to a small server on the laptop instead of ntfy.sh. It is
the same protocol — the server answers the slice of ntfy the app uses — so
nothing about the app changes except the address it points at.

Why bother: a message crosses the room instead of the internet, attachments come
off the LAN rather than six phones sharing the venue uplink, and the night no
longer depends on venue Wi-Fi reaching the outside world or on the ntfy token
still being valid.

```bash
cd server && npm install     # once
npm start
```

It prints the address to use, e.g. `http://192.168.1.20:8080`. Then:

1. Open that address in a browser — the sender is served from it, so the page
   already knows where it is.
2. Press **📡 ZET TELEFOONS OP DEZE LAPTOP**. The phones are told over whichever
   channel they are on right now, so no handset has to be touched.
3. **☁︎ Terug naar internet (ntfy)** puts them back.

**The laptop is never the only option.** Every reconnect probes it first and
falls back to ntfy.sh within a second and a half if it cannot be reached, so
closing the lid or wandering off the network degrades the show rather than
ending it. Phones return to the laptop by themselves once it answers again.

If the address is known in advance it can be baked in instead of broadcast:

```bash
./gradlew assembleRelease -PfmLocalServer=http://192.168.1.20:8080
```

⚠️ **Check the access point allows client-to-client traffic.** Plenty of venue
and guest Wi-Fi has client isolation switched on, which blocks the phones from
seeing the laptop at all — and no amount of app code can work around it. Bring a
travel router if the venue network is not yours. The laptop also needs a stable
address (a DHCP reservation) and must not sleep.

## The ntfy token

Traffic is authenticated with an ntfy access token, which is what lifts the anonymous
rate and bandwidth limits (Supporter tier: 25 MB per attachment, 500 MB stored,
1 GB/day). Without it a busy night hits `HTTP 413 … bandwidth limit reached` and
secrets simply stop arriving.

**The token is never committed** — this repo is public. It lives in:

- `ntfy-token.txt` in the repo root (git-ignored) for local builds
- the `FM_NTFY_TOKEN` GitHub Actions secret for released builds
- the admin's browser (`localStorage`) — paste it once into the token box in the sender

⚠️ **ntfy tokens expire.** Check the expiry before an event:

```bash
curl -s -H "Authorization: Bearer $(cat ntfy-token.txt)" https://ntfy.sh/v1/account
```

If it has expired, mint a new one in the ntfy account settings, then update all three
places above (and re-release the APK, since it is baked in at build time).

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

## Revealing on all six phones at once

Six phones used to chime at six different moments, up to a minute apart. The
cause was ordering: each phone downloaded the whole picture or clip *before* it
alerted, so every handset chimed in proportion to its own download speed. Six
phones pulling a 25 MB file through one access point diverge by exactly that
much.

Two things fix it, and the first one is automatic.

**Send alerts immediately.** The teaser on the cover screen is baked into the
app, so the bytes are not needed to tell anyone a secret has arrived — and there
are seconds of human time between the chime and the phone being unfolded for the
picture to land in. A phone now alerts in tens of milliseconds regardless of file
size, and holds on the teaser if it is unfolded before the media is there, rather
than revealing an empty card and popping the artwork in late. Nothing to operate:
this is just how **Send** behaves.

**Preload, then reveal.** For a reveal that has to be exact, or for a large clip:

1. Pick the phones, choose the file, press **📥 LAAD SECRET (stil)**. The phones
   fetch it and say nothing.
2. The table fills in as each one reports ready. **✨ ONTHUL NU** unlocks at 6/6.
3. Press it. The reveal carries no attachment — it is a few bytes — so every
   phone shows the secret on the same beat.

The table also answers "how far apart were they?" from the arrival times the
laptop stamps, rather than from six handsets that disagree about the clock.

Measured on the emulator, a 25 MB secret: alert at **15 ms**, media at
**22675 ms**. Under the old ordering the chime was the second number. A
preloaded reveal lands in **41 ms**.

⚠️ **Commands go to both servers.** A phone listens to the laptop *or* ntfy
depending on what it could see when it last reconnected, so a fleet can end up
split. Every command — reveal, next round, final question, selfies, table — is
published to both, and answers are read from both, because a reveal that reaches
only half the phones is the worst failure this thing has. Phones also stay on a
laptop that misses a probe or two rather than defecting on the first miss.

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
2. Open the app, tap the phone's number (1–6).
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

**Changing a handset's number later:** long-press the number in the bottom-left of
the idle screen and the picker comes back. It is a long-press on purpose — it must
not be reachable by accident mid-round.

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
