# Lumen (Phase 0, survival spike)

Lumen is a single-file audio player for long spoken-word recordings. Phase 0 is not a product.
It exists to answer one question on one device: does a Media3 playback service, written the
plain way, survive One UI on a Galaxy S23 Ultra (Android 16, One UI 8.5) well enough that the
listener never loses their place?

Package `app.lumen.player`. minSdk 29, targetSdk 36, compileSdk 37 (Compose 1.12 and later
require compileSdk 37; the app still targets 36). Dark only.

## What Phase 0 does

1. One screen: pick a file (Storage Access Framework, `audio/*` and `video/*`, persistable
   permission), then a Now Playing panel on true black with title, speaker, scrubber, elapsed and
   remaining time, back 10 s, play/pause, forward 30 s. Nothing animates. No spinner.
2. `PlaybackService`, a `MediaLibraryService` holding the only ExoPlayer. The UI drives it through
   a `MediaController` and never creates its own player. The notification and lock screen show
   seek back and seek forward instead of previous and next.
3. Position store (Room table `progress` plus a `kv` table for the last item), written on pause,
   seek, item transition, loss of audio focus, service destroy, and every 30 s while playing. Each
   write is also appended to `filesDir/positions.jsonl`. On resumption the player seeks to the
   saved position minus 3 s. Resumption is triggered by the system (media button, resumption
   card, boot) or by pressing play in the app while the player is empty, which makes the session
   call `onPlaybackResumption` and rebuild the last item from the store.
4. Diagnostics zip (ring-buffer log, device and One UI version, battery optimisation state, app
   standby bucket, service running flag, progress table dump, last 50 backup lines) shared through
   the system share sheet.
5. A once-per-24-hours check of the latest GitHub release with a one-line update banner.

No analytics, no crash reporter, no notification permission request, no battery exemption card.

## Build

Everything is built by GitHub Actions (`.github/workflows/build.yml`) on every push to `main`,
on every `v*` tag, and on demand.

- JDK 17, Android Gradle Plugin 9.4.0 (built-in Kotlin, so no `kotlin-android` plugin), Gradle
  9.6.1 through the wrapper, Kotlin 2.4.21 for the Compose compiler plugin (its Gradle plugin
  brings KGP 2.4.21 onto the build classpath, which AGP 9 permits, so compiler and Compose plugin
  always match), KSP 2.3.11 for Room. SDK packages installed in CI: `platforms;android-37` and
  `build-tools;36.0.0`.
- `versionName` comes from the tag (`v1.2.3` becomes `1.2.3`) or falls back to
  `0.1.0-run<run number>`. `versionCode` is the Actions run number.
- Signing: if the secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`
  exist, the release APK is signed with that key. If any is missing the build signs with the
  debug key instead and still succeeds.
- The in-app update check reads `https://api.github.com/repos/<owner>/lumen/releases/latest`;
  `<owner>` is the Gradle property `githubOwner` (default `aaronalmeida`), baked into
  `BuildConfig.GITHUB_OWNER`.

Locally: `./gradlew assembleRelease` (needs network for the first run).

Pinned versions were verified on 2026-10-19 against the official release pages: AGP
(developer.android.com/build/releases/gradle-plugin), Gradle (gradle.org/releases), Kotlin
(kotlinlang.org/docs/releases.html), KSP (github.com/google/ksp/releases), Compose BOM
(developer.android.com/develop/ui/compose/bom), Media3 (github.com/androidx/media/releases),
Room, Lifecycle, Activity and Core (developer.android.com/jetpack/androidx/releases/...).

## Phase 0 sheet (the 14-step protocol, summarised)

Run on the S23 Ultra with a file of at least two hours. Record pass or fail per line and attach
a diagnostics zip at the end.

| Step | What to do | Pass when |
|---|---|---|
| 1 | Pick a file, play 30 s, pause | Status line shows a saved position within 2 s |
| 2 | Scrub, back 10, forward 30 | Each action saves a position |
| 3 | Minimise, reopen (20 cycles while playing) | Audio never stops, UI reconnects every time |
| 4 | Swipe the app away from Recents while playing | Audio keeps playing, notification stays |
| 5 | Swipe away while paused, wait 30 min | Notification or resumption card can resume within 3 s |
| 6 | Lock the screen for 30 min while playing | No gap longer than 1 s |
| 7 | Unplug or disconnect headphones | Playback pauses, position saved |
| 8 | Take a phone call | Pauses on ring, resumes or stays paused as One UI dictates, position saved |
| 9 | Overnight paused (8 h or more) | Reopen and press play: resumes within 3 s at saved minus 3 s |
| 10 | Reboot the phone | Resumption from the media controls or app within 3 s |
| 11 | Force stop from Settings, reopen | App remembers the item and position |
| 12 | Enter Bluetooth, use headset play, pause, skip buttons | Buttons map to play, pause, back 10, forward 30 |
| 13 | Check the notification and lock screen controls | Show back 10 and forward 30, not previous and next |
| 14 | Share diagnostics | Zip arrives with log, device info, table dump and backup tail |

Resume rule under test everywhere: within 3 s of the request, at the saved position minus 3 s.

## Licence

Not yet decided; private use. See `NOTICE`.
