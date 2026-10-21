# Lumen (Phase 1b, Google Drive and backup)

Lumen is a player for long spoken-word recordings and the music that sits beside them. Phase 0
(v0.1.x) answered one question on one device: does a Media3 playback service survive One UI on a
Galaxy S23 Ultra well enough that the listener never loses their place? It did. Phase 1a builds
the first real product on that service: a library of chosen folders, labels, the two Now Playing
layouts, an equaliser, a widget, an Android Auto tree and app shortcuts. Phase 1b (v0.3.x) adds
the Google Drive source, offline copies, backup and restore, and the brand. YouTube Music is a
later phase.

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

## Phase 1b

### Google Drive (scope `drive.file` plus Google's picker)

The rule the owner set: pick the sermon folder once with Google's own picker, no whole-Drive
scope, no weekly re-login. How it is built:

1. **Authorisation.** `com.google.android.gms:play-services-auth:22.0.0`,
   `Identity.getAuthorizationClient(context).authorize(AuthorizationRequest.builder()
   .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.file"))).build())`.
   A missing grant comes back with `hasResolution()`; MainActivity launches its `pendingIntent`
   through `StartIntentSenderForResult` (the Google sheet, the only sheet Drive ever shows) and
   reads the token back with `getAuthorizationResultFromIntent`. Once the grant is held, every
   later `authorize` call returns a fresh short-lived access token silently, so the service, the
   scanner and the backup all call `DriveAuth.tokenBlocking()` without any UI. A 401 clears the
   token (`clearToken`) and asks once more; a second 401 or any 403 sets the "Sign in to Drive
   again" line in Settings. Never a dialog. (`data/drive/DriveAuth.kt`)
2. **The folder picker: WebView route.** Google documents an Android Picker trigger for the
   authorisation flow (`AuthorizationRequest.ResourceParameter.PICKER_OAUTH_TRIGGER`, with
   `PICKER_ALLOW_FOLDER_SELECTION`, the result in `AuthorizationResult.getTokenResponseParams()`
   under `picked_file_ids`; see
   https://developers.google.com/workspace/drive/picker/guides/desktop-mobile-picker; the
   parameters arrived in play-services-auth 21.6.0, 27 May 2026). The reference page that
   names the `AuthorizationRequest.Builder` setter for those parameters is rendered client-side
   and could not be read from the build environment, and a guessed method name would have
   broken the build, so this build ships the documented fallback: `DrivePickerActivity` hosts a
   WebView that loads the bundled `assets/picker.html`, which uses the Picker JS API
   (`https://apis.google.com/js/api.js`, `gapi.load('picker')`, `PickerBuilder` with
   `DocsView(ViewId.FOLDERS).setIncludeFolders(true).setSelectFolderEnabled(true)`,
   `setOAuthToken(token)`, `setDeveloperKey(BuildConfig.PICKER_API_KEY)`) and posts the picked
   folder id and name back through the `LumenPicker` JavascriptInterface. The page is loaded
   under the base URL `https://appassets.androidplatform.net/picker/` so the Picker has an
   https origin. Swapping to the native trigger later touches `MainActivity.startDriveAuthorisation`
   and nothing else. If `PICKER_API_KEY` is empty the Drive card reads "Drive not configured in
   this build" and does nothing.
3. **Listing.** `files.list` with `q = '<folderId>' in parents and trashed = false`, fields
   `id,name,mimeType,size,modifiedTime,createdTime`, `pageSize=1000`, every page, then the same
   for each subfolder one level down. Audio and video rows are written to the existing `media`
   table: `uri = drive://<fileId>`, `folder_uri = drive:<folderId>` (one folder row named
   "Drive", cloud icon in the Library), title from the name without its extension, `duration_ms`
   0 until the first play, when the service writes the player's duration (and the refreshed
   fingerprint) back into the row. Rescan on app start when the last listing is older than 6
   hours and the silent token still works, and on Rescan in Settings (the Folders Rescan also
   lists Drive). Each scan logs `drive: N files (...)`.

   **The open spike.** `drive.file` only sees files the user picked or that the app created.
   Google's picker hands the app the folder, and whether files added to that folder later are
   listed depends on Drive granting folder access through the picker grant. The count in each
   scan line is how the owner tells: drop a new file into the folder, Rescan, compare.
4. **Playback.** `playback/LumenDataSources.kt`: `LumenDataSourceFactory` keeps Media3's
   `DefaultDataSource` for `content://` and `file://` and routes `drive://` to
   `DriveDataSource`, which opens `https://www.googleapis.com/drive/v3/files/<id>?alt=media`
   through `DefaultHttpDataSource` with `Authorization: Bearer <token>` as a default request
   property (Range requests pass straight through), wrapped in `CacheDataSource` over one
   `SimpleCache` (`LeastRecentlyUsedCacheEvictor`, 200 MB, `StandaloneDatabaseProvider`, in
   `cacheDir/drive`) so a seek back does not fetch again. The cache key is `drive:<id>`, so a
   new token does not invalidate what is cached. MediaItems keep the `drive://` uri, which means
   the progress keys stay `uri|duration` exactly as in Phase 0. The only changes in
   `PlaybackService`: `setMediaSourceFactory(DefaultMediaSourceFactory(this)
   .setDataSourceFactory(lumenDataSourceFactory(this)))` on the ExoPlayer builder, and
   `learnDriveDuration()` on `STATE_READY`, which writes the media row only. `PositionStore` is
   untouched.
5. **Keep offline.** On Now Playing (a chip after the others; "Kept" with a tick once done, tap
   again to remove) and in the long-press sheet ("Keep offline" / "Remove download"). The file
   streams to `filesDir/offline/<id>.<ext>` with a 1 dp progress line, on an unmetered network
   only unless Settings, Google Drive, "Allow downloads on mobile data" is on
   (`ConnectivityManager`, `NET_CAPABILITY_NOT_METERED`). When the copy exists the data source
   plays it with `FileDataSource`; the row's `offline_path` records it (MIGRATION_2_3). Remove
   deletes the file and clears the column.
6. **Errors.** Offline with nothing cached and no copy: the Library row is greyed with "Offline"
   in the time slot and a tap does nothing. 401 or 403 anywhere: one line in Settings,
   "Sign in to Drive again". Disconnect removes the Drive rows and kept copies; positions and
   labels stay.

### Backup and restore

Settings, Backup. "Back up" opens ACTION_CREATE_DOCUMENT (default name
`lumen-backup-YYYYMMDD.json`) and writes one JSON document: positions (with the fingerprint of
each uri), labels, media_labels (uri and fingerprint), bookmarks and the kv settings minus the
device-only keys. When Drive is connected the same bytes also go to a "Lumen" folder the app
creates (`files.create`, multipart; a same-day file is updated with PATCH). "Restore" opens
ACTION_OPEN_DOCUMENT and merges, never deletes: positions win by `updated_at`, labels match by
name, media by uri first and then by fingerprint, bookmarks by uri and position, settings only
where this phone has no value. A weekly automatic Drive backup runs on app start when Drive is
connected and the last Drive copy is older than 7 days. (`data/backup/BackupManager.kt`)

### Brand

- `res/drawable/lumen_lockup.xml`: the light trail as a VectorDrawable with `aapt:attr`
  gradients (cool silver `#C7CBD1` lower left, warm white `#FFF4E6` over the top, amber
  `#E8862B` core with a radial glow). The wordmark is Compose text, Manrope 500, 0.18 em
  tracking, in `LumenLockup()` (`ui/Branding.kt`). Used on the branded cold-start screen (black,
  600 ms, a 200 ms fade, shown once per process after the Android 12 system splash from
  `androidx.core:core-splashscreen:1.2.0`, `Theme.Lumen.Splash`) and at the top of Settings.
- Maker credit: `aaron_instinct_lumen.xml` (charcoal body, amber `#C2410C` accent, light
  surfaces) and `aaron_instinct_lumen_reverse.xml` (off-white body, amber `#E8862B`, black
  surfaces) are the owner's mark with only the `#28D9E8` accent fill replaced; silhouette and
  proportions untouched. Shown at 20 dp above "by Aaron Almeida" (Inter 13 sp, secondary,
  centred, 24 dp padding) at the bottom of Settings and nowhere else.
- Launcher icon: still the Resume arc. `mipmap-anydpi-v26/ic_launcher.xml` now points its
  foreground at `drawable/ic_launcher_fg.xml`, a layer-list holding the arc vector. **To ship
  the glow icon:** add `res/mipmap-xxxhdpi/ic_launcher_foreground_glow.png` (432 by 432 px,
  artwork inside the central 66 percent safe zone) and change the single `android:drawable` in
  `drawable/ic_launcher_fg.xml` to `@mipmap/ic_launcher_foreground_glow`. The `<monochrome>`
  layer must stay on `@drawable/ic_launcher_foreground` (the flat arc) for themed icons.

### Drive setup for the owner (once)

1. Google Cloud console: create a project (any name), **APIs and Services, Library**: enable
   **Google Drive API** and **Google Picker API**.
2. **OAuth consent screen**: user type External, app name Lumen, support and developer email,
   scope `.../auth/drive.file`, then **Publish** (a Testing app expires its grants after 7 days,
   which is exactly the weekly re-login the owner ruled out).
3. **Credentials, Create credentials, OAuth client ID**, type **Android**, package name
   `com.aaronalmeida.lumen`, SHA-1 `01:CA:FE:7A:87:31:5F:5D:C6:AC:86:14:85:C1:15:65:6E:49:1E:5B`
   (the release key from the Actions secrets). An Android client has no secret and nothing is
   pasted into the app: Play Services matches the package and the signing certificate.
4. **Credentials, Create credentials, API key**: restrict the key to the **Google Picker API**.
   Application restriction: for this WebView route use **Websites** (HTTP referrers) with
   `https://appassets.androidplatform.net/*`; the "Android apps" restriction only matches
   requests made by Play Services libraries, which a WebView page is not. If the native Picker
   trigger is adopted later, switch the restriction to Android apps with the package and SHA-1.
5. GitHub repository, **Settings, Secrets and variables, Actions**: add `PICKER_API_KEY` with the
   key. The workflow passes it as `-PpickerApiKey=...`; `app/build.gradle.kts` turns it into
   `BuildConfig.PICKER_API_KEY`. Without the secret the build still succeeds and the Drive card
   says "Drive not configured in this build".
6. First use: Settings, Google Drive, **Choose folder**. The Google sheet appears once (account
   and consent), then Google's picker; pick the sermon folder. The listing runs at once.

## Data

Room database `lumen.db`, version 3. `MIGRATION_2_3` runs
`ALTER TABLE media ADD COLUMN offline_path TEXT` and nothing else; `progress` and `kv` are
untouched. Version 2 (0.2.1) added queries only, no schema change. The Phase 0
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

Phase 1b dependencies, verified on 2026-10-21:
`com.google.android.gms:play-services-auth:22.0.0` (developers.google.com/android/guides/releases,
26 August 2026; mvnrepository.com lists 22.0.0 on 25 August 2026; this version drops the old
Google Sign-In classes, which Lumen never used, and keeps the Identity AuthorizationClient),
`androidx.core:core-splashscreen:1.2.0` (developer.android.com/jetpack/androidx/releases/core,
listed as the stable release), `androidx.media3:media3-datasource:1.11.1` and
`androidx.media3:media3-database:1.11.1` (already transitive through media3-exoplayer; named so
`SimpleCache` and `StandaloneDatabaseProvider` are pinned to the Phase 0 Media3 version).

Locally: `./gradlew assembleRelease -PpickerApiKey=<key>` (needs network for the first run; the
key may be left out).

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
| 13 | Backup export | See the Phase 1b sheet below |
| 14 | Share diagnostics | Zip arrives with log, device info, media permission state, last MediaStore scan time and count, media count, folder list, table dump and backup tail |
| 15 | Pick Speech, Bass, then Custom and drag a band; play, pause, swipe away, reopen | No click on preset change; the chosen preset and custom bands are still shown and audible |

Resume rule under test everywhere: within 3 s of the request, at the saved position minus 3 s.

## Phase 1b sheet

Needs a Drive folder with a few sermons (one in a subfolder), a build with `PICKER_API_KEY`, and
the phone on Wi-Fi to begin with.

| Step | What to do | Pass when |
|---|---|---|
| 1 | Cold start after install | Black system splash with the arc, then the LUMEN lockup on black for well under a second, one fade, then Home; a warm start (back to Recents and back) shows neither |
| 2 | Settings | The lockup sits above Folders; the Aaron Instinct mark and "by Aaron Almeida" sit at the very bottom and nowhere else; the Google Drive card shows "Choose folder" (or "Drive not configured in this build" on a build without the key, and then nothing is tappable) |
| 3 | Choose folder | The Google sheet appears once (account, consent for Drive files you pick), then Google's picker; picking the folder closes it; the Drive card shows the folder name, then "N files · listed just now"; Library lists a "Drive" folder with a cloud icon and the files, time "--:--" |
| 3a | Add a file to the folder from a computer, then Rescan | Note whether the count rises (the open spike); the log line `drive: N files` records it either way |
| 4 | Play a Drive sermon | Audio within a few seconds; the row's time fills in after the first play; seek back 2 minutes: no refetch pause; Share diagnostics shows `drive:` in device.txt |
| 5 | Pause, swipe the app away, press play on the widget | Resumes at saved minus 3 s, from Drive, with no sheet |
| 6 | Keep offline from the Now Playing chip | A 1 dp line under the chips, then the chip reads "Kept"; aeroplane mode: the item still plays; other Drive rows are greyed with "Offline" and a tap does nothing |
| 7 | Long press a Drive row, Remove download | The sheet row flips back to "Keep offline"; the file under filesDir/offline is gone |
| 8 | Mobile data only, Keep offline with the toggle off, then on | Off: nothing starts, log says "Waiting for Wi-Fi"; on: the download runs |
| 9 | Revoke Lumen's access at myaccount.google.com, then Rescan | One line "Sign in to Drive again" in the Drive card, no dialog; tapping it shows the Google sheet once and the line clears |
| 10 | Back up | The system file picker, a `lumen-backup-YYYYMMDD.json` file; the card reads "Backed up to the file and to Drive"; Drive shows a "Lumen" folder with the file |
| 11 | Restore the same file | "Restored N positions"; nothing in the library disappears; a position older than the local one is not written over |
| 12 | Reinstall, scan the phone, Restore | Positions and labels come back by uri; a file reached only under another uri comes back by fingerprint |
| 13 | Leave the app alone for 8 days, open it | A new dated backup appears in the Drive Lumen folder (weekly automatic) |
| 14 | Disconnect in the Drive card | Drive rows and kept copies gone, positions and labels still there, "Choose folder" again |

## Licence

Not yet decided; private use. See `NOTICE`.
