# Lumen (Phase 1a, local library)

Lumen is a player for long spoken-word recordings and the music that sits beside them. Phase 0
(v0.1.x) answered one question on one device: does a Media3 playback service survive One UI on a
Galaxy S23 Ultra well enough that the listener never loses their place? It did. Phase 1a builds
the first real product on that service: a library of chosen folders, labels, the two Now Playing
layouts, an equaliser, a widget, an Android Auto tree and app shortcuts. Google Drive and YouTube
Music are later phases.

Application id `com.aaronalmeida.lumen` (Kotlin package `app.lumen.player`; the id changed in 0.2.1 because `app.lumen.player` was already taken in the Android Developer Console). minSdk 29, targetSdk 36, compileSdk 37 (Compose 1.12 and later
require compileSdk 37). Light Home, Library and Settings; true black Now Playing. Design system:
`lumen-design/DESIGN.md`, Variant A (one amber accent).

## What Phase 1a does

1. **Library: the whole phone, plus picked folders (0.2.1).** Two ways in, both in Settings,
   Folders, and both feeding the same Room `media` table.

   *Scan whole phone* (the first row, and the Home empty state's "Scan my phone") reads the
   index Android already keeps: one cursor pass over `MediaStore.Audio.Media` and
   `MediaStore.Video.Media` (volume `external`) with the columns `_ID`, `DISPLAY_NAME`, `TITLE`,
   `DURATION`, `SIZE`, `DATE_ADDED`, `DATE_MODIFIED`, `MIME_TYPE`, `RELATIVE_PATH`,
   `BUCKET_DISPLAY_NAME` and, for audio, `ARTIST`. Each row's uri is
   `ContentUris.withAppendedId`, its folder is `mediastore:<RELATIVE_PATH>` (so every MediaStore
   folder appears in the Library under its bucket display name with a phone icon), `rel_path` is
   `RELATIVE_PATH + DISPLAY_NAME` and the fingerprint is `size|duration|title` as before. Rows
   under `Android/`, `Ringtones/`, `Notifications/` and `Alarms/` and anything under 3 seconds are
   left out. Rows are written in batches of 100; rows whose MediaStore ids are gone are removed by
   id, never by wiping, and picked-folder rows are never touched by a phone scan (nor the other
   way round). Embedded art is the slow part, so the scan reads it only for the newest 200 music
   files; every other row gets it the first time it is loaded in the player (`art_path` null
   means not looked yet, "" means none). It needs `READ_MEDIA_AUDIO` and `READ_MEDIA_VIDEO` (API
   33 and later) or `READ_EXTERNAL_STORAGE` (29 to 32), asked only when the user taps Scan. If
   Android refuses, one quiet line appears with an "Open settings" button and the app never asks
   again on its own. The index stays fresh cheaply: on app start a scan older than 6 hours is
   rerun in the background (only when the permission is already held), and while the app is in
   the foreground a ContentObserver on the two collections triggers a rescan 2 seconds after the
   last change, so a new download shows up without a tap.

   *Add a folder* (ACTION_OPEN_DOCUMENT_TREE with a persistable permission) stays for folders
   Android keeps out of the phone scan, such as app-private audio. The scanner walks each tree
   with DocumentsContract (one query per directory), keeps audio and video, reads title, artist,
   duration and art with MediaMetadataRetriever off the main thread. Only new or changed files are
   read again. Remove and Rescan apply to picked folders.

   Either way, files with the same fingerprint collapse to one row in the UI with an "x2" badge
   (so a file reached both ways is listed once). Music files keep their embedded art in
   `filesDir/art` with a desaturated dominant colour (Palette) for the music layout's base tint.
   Scanning shows as a 1 dp line, never a spinner; the Scan row counts files as they are seen.
2. **Home.** Wordmark and gear; CONTINUE card for the last played item (title, speaker, 2 dp
   progress line, mono times, amber Resume pill); a row of label chips (tap plays the label as a
   queue, long press edits the label); RECENT, the last eight played. Empty state: "Scan my phone"
   (amber) and "Add a folder" (outlined); once the library has files but nothing has been played,
   "Open Library".
3. **Library.** DATE or NAME sort, a search field that filters title, artist, folder and labels as
   you type, FOLDERS with counts (phone icon for MediaStore buckets, folder icon for picked
   folders; tap filters), ALL FILES as one lazy row per file, a mini bar above the bottom bar with
   play or pause that opens Now Playing. Long press a row for the label sheet.
4. **Now Playing, two layouts.** Speech when the item is over 20 minutes or carries the Sermons
   label; long press the title to override per item (persisted). Speech: eyebrow, 32 sp title,
   speaker, chips for speed (1.0x to 2.0x, pitch kept), EQ preset, sleep timer (15 to 60 minutes,
   20 s fade then pause) and bookmark (saved to Room, listed below, tap to seek, long press to
   remove), a 6 dp scrubber with position, duration and remaining, back 10 / play 80 dp / forward
   30. Music: art block or a lettered placeholder on the tinted base, 22 sp title, artist, label
   chip, 3 dp scrubber, shuffle, previous, play 72 dp, next, repeat, an "Up next" strip. Video
   items: an inline 16:9 PlayerView, full screen in landscape, and an "Audio only when you leave"
   switch that drops the video track while the surface is away and restores it on return.
5. **Labels.** Many-to-many with media (`labels`, `media_labels`). Name, one of six muted hue
   dots, three toggles: lock for kids, no interruptions (screen stays on, dimmed, sleep chip
   hidden), shuffle on open. Sermons, Worship, Kids and Upbeat are seeded on first run. Any file
   whose path contains "Suno", or whose artist is Suno, gets a "Suno" label automatically.
6. **Kids lock.** While a locked label plays, Now Playing shows three thumb pads and a lock glyph,
   back is swallowed, and the only exit is a two second press on the top-right corner.
7. **Equaliser in the service.** `DynamicsProcessing` bound to an explicit audio session id
   (`C.generateAudioSessionIdV21`, set on the player at build, rebound on
   `onAudioSessionIdChanged`): five pre-EQ bands (80, 250, 1000, 3500, 10000 Hz centres), one MBC
   band as a gentle compressor for Speech only (-24 dB, 3:1, 10 ms, 150 ms, +4 dB), limiter on.
   Presets Flat, Speech, Bass, Worship, Kids, Custom; the preset follows the item's label (Kids,
   Worship, Sermons or speech layout), otherwise the last one chosen. LoudnessEnhancer +3 dB on
   Speech. If DynamicsProcessing is refused, `android.media.audiofx.Equalizer` takes the nearest
   bands; if that fails too, the EQ section hides itself. Gains are applied with the effects'
   own setters, so preset changes do not click.
8. **Queue.** Playing from Home, Library or a label builds a queue of MediaItems (mediaId = uri,
   title, artist, artwork uri, extras with isVideo, labelIds, speech, duration). On a transition
   the previous item's position is saved from the discontinuity's old position; the new item is
   resumed at its saved position minus 3 s when it is speech, and started at 0 when it is music.
9. **Widget.** A 4x2 Glance widget mirroring the lock screen: title, "47:12 · -22:20 · 1.25x" in
   mono, progress line, back, play or pause, forward. Buttons broadcast to `WidgetActionReceiver`,
   which forwards to the session through a short-lived MediaController (so it also works after
   process death through playback resumption). Redrawn on state changes and the 30 s heartbeat,
   never per second.
10. **Android Auto.** `onGetLibraryRoot` returns Continue, Labels (each label browsable), Sermons
    and Recent, served from Room. `automotive_app_desc` and the manifest meta-data are declared.
11. **App shortcuts.** Resume, Play Worship, Play Kids (static `xml/shortcuts.xml`, release
    application id).
12. **Settings.** Folders (Scan whole phone with its last scan time and count, then Add a
    folder), Equaliser (preset chips plus five band sliders, editable for Custom),
    Skip intervals (back 5 to 30 s, forward 10 to 60 s; the notification icons follow), Night
    (black Home and Library), Diagnostics, About (version, update check, GitHub).
13. **Kept from Phase 0.** The service is the only player; the UI drives it through a
    MediaController. Positions are written on pause, seek, transition, focus loss, destroy and
    every 30 s, to Room and `positions.jsonl`. Resumption through the system (media button, card,
    boot) and through play on an empty player. Restore-last-item-paused on launch. Diagnostics
    zip, now with the media permission state, the last MediaStore scan time and count, the media
    row count and the folder list in `device.txt`. Daily GitHub update check.
    No analytics, no crash reporter, no notification permission request.

Settings (speed, presets, custom bands, sleep timer, night, skip intervals, layout overrides,
last MediaStore scan time and count) live in the Phase 0 `kv` table; the UI writes and the
service observes, so there is one path.

## Data

Room database `lumen.db`, version 2 (0.2.1 adds queries only, no schema change). The Phase 0
`progress` and `kv` tables are unchanged and `MIGRATION_1_2` only creates `folders`, `media`,
`labels`, `media_labels` and `bookmarks`, so every saved position survives the upgrade. MediaStore
buckets are `folders` rows whose uri starts with `mediastore:`; picked folders keep their tree
uri. `fallbackToDestructiveMigration(true)` is set for the
rest of Phase 1 only. A save with an unknown duration never creates a second `|unknown` row: when
the uri already has a row it updates that row instead.

## Build

Everything is built by GitHub Actions (`.github/workflows/build.yml`) on every push to `main`,
on every `v*` tag, and on demand.

- JDK 17, Android Gradle Plugin 9.4.0 (built-in Kotlin), Gradle 9.6.1, Kotlin 2.4.21 Compose
  compiler plugin, KSP 2.3.11 for Room.
- `versionName` comes from the tag (`v1.2.3` becomes `1.2.3`) or falls back to
  `0.1.0-run<run number>`. `versionCode` is the Actions run number.
- Signing: if the secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`
  exist, the release APK is signed with that key, otherwise with the debug key.
- The in-app update check reads `https://api.github.com/repos/<owner>/lumen/releases/latest`;
  `<owner>` is the Gradle property `githubOwner` (default `aaronalmeida`).

Phase 1a dependencies, verified on 2026-10-21 on the AndroidX release pages:
`androidx.glance:glance-appwidget:1.2.0` (releases/glance, stable 26 August 2026),
`androidx.palette:palette-ktx:1.0.0` (releases/palette), `androidx.documentfile:documentfile:1.1.0`
(releases/documentfile, 7 May 2025), `androidx.media3:media3-ui:1.11.1` (same Media3 version as
Phase 0), `androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0` (same Lifecycle version).

Locally: `./gradlew assembleRelease` (needs network for the first run).

## Phase 1 sheet

Run on the S23 Ultra with the phone holding at least one two-hour sermon, a few short music files
(one with embedded art, one named with "Suno") and one video, somewhere MediaStore sees them
(Music, Download, Movies). Record pass or fail per line and attach a diagnostics zip at the end.

| Step | What to do | Pass when |
|---|---|---|
| 1 | Install over the previous build | App opens on Home; the last item appears on the Continue card, or the empty state offers "Scan my phone" and "Add a folder" |
| 2 | Open Settings, Share diagnostics before anything else | `progress-table.txt` lists every earlier row; `device.txt` shows `media permission`, `mediastore last scan: never` and the folder list |
| 3 | Tap Scan whole phone, allow audio and video | A 1 dp line and a rising count while scanning, then Library lists every file with durations under phone-icon folders named after their MediaStore buckets; nothing from Ringtones, Notifications, Alarms or Android; duplicates show x2 |
| 3a | Deny the permission on a fresh install instead | One line "Lumen needs access to audio and video files to scan the phone" with Open settings; grant it there, return, and the scan runs on its own; the app never asked again by itself |
| 3b | Download an audio file while the app is open | It appears in the Library within a few seconds without tapping anything |
| 3c | Add a folder as well | The picker still works; the folder appears with a folder icon; Remove and Rescan act on it only |
| 4 | Tap Resume on the Continue card | Audio within 300 ms at the saved position minus 3 s |
| 5 | Minimise and reopen 20 times while playing | Audio never stops; the timecode is correct every time |
| 6 | Swipe the app away from Recents while playing | Audio keeps playing; the notification stays; the widget still shows the title |
| 7 | Pause, leave it 5 minutes, press play on the widget | Plays within 3 s at saved minus 3 s |
| 8 | Take a phone call during playback | Pauses on ring; position saved; resumes or stays paused as One UI dictates |
| 9 | Bluetooth headset play, pause, skip buttons | Play, pause, back 10, forward 30 (or the intervals set in Settings) |
| 10 | Overnight paused (8 h or more), then press play | Resumes within 3 s at saved minus 3 s; the preset and speed are the ones last used |
| 11 | Reboot the phone, then play from the lock screen controls or the widget | Resumption within 3 s |
| 12 | Play the video, switch "Audio only when you leave" on, leave Now Playing | Sound continues; coming back shows the picture again |
| 13 | Backup export | Not in this build; Phase 1b. Note "n/a" |
| 14 | Share diagnostics | Zip arrives with log, device info, media permission state, last MediaStore scan time and count, media count, folder list, table dump and backup tail |
| 15 | Pick Speech, Bass, then Custom and drag a band; play, pause, swipe away, reopen | No click on preset change; the chosen preset and custom bands are still shown and audible |

Resume rule under test everywhere: within 3 s of the request, at the saved position minus 3 s.

## Licence

Not yet decided; private use. See `NOTICE`.
