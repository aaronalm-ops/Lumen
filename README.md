# Lumen (0.5.0, Phase 3: Chromecast and the YouTube Music remote)

Lumen is a player for long spoken-word recordings and the music that sits beside them. Phase 0
(v0.1.x) answered one question on one device: does a Media3 playback service survive One UI on a
Galaxy S23 Ultra well enough that the listener never loses their place? It did. Phase 1a builds
the first real product on that service: a library of chosen folders, labels, the two Now Playing
layouts, an equaliser, a widget, an Android Auto tree and app shortcuts. Phase 1b (v0.3.x) adds
the Google Drive source, offline copies, backup and restore, and the brand. 0.4.0 bundles the
Phase 2 items: Appearance, Clarity for sermons, chapters and notes, WhatsApp voice notes, Smooth
transitions, foldables and large screens (see "0.4.0" below). 0.5.0 adds Chromecast and the
YouTube Music remote (see "0.5.0" below).

Application id `com.aaronalmeida.lumen` (Kotlin package `app.lumen.player`; the id changed in 0.2.1 because `app.lumen.player` was already taken in the Android Developer Console). minSdk 29, targetSdk 36, compileSdk 37 (Compose 1.12 and later
require compileSdk 37). Light Home, Library and Settings; true black Now Playing. Design system:
`lumen-design/DESIGN.md`: light screens on round 4 Option C (graphite and cyan), black screens
on round 5 Option C (ice and ember: cyan controls with black glyphs, ice for secondary state,
amber only for a held place in a paused sermon and the LUMEN mark's core).

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
   left out, except WhatsApp's voice notes and audio under `Android/media` (0.4.0). Rows are
   written in batches of 100; rows whose MediaStore ids are gone are removed by
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
    Sound (0.4.0), Skip intervals (back 5 to 30 s, forward 10 to 60 s; the notification icons
    follow), Appearance (0.4.0: Automatic, Light or Dark; it replaced Night), Diagnostics, About
    (version, update check, GitHub).
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

### Google Drive (scopes `drive.readonly` and `drive.file`, Lumen's own folder browser)

The rule the owner set: choose the sermon folder once, no weekly re-login. 0.3.0 to 0.3.2 used
`drive.file` with Google's picker; on the owner's phone the scan of the picked folder returned 0
files, because `drive.file` grants the picked folder itself and not its children. The
pre-agreed fallback (ruling D2) shipped in 0.3.3: `drive.readonly` to see inside the folder,
`drive.file` kept for the app's own backup folder, and Lumen's own folder browser in place of
the picker. How it is built:

1. **Authorisation.** `com.google.android.gms:play-services-auth:22.0.0`,
   `Identity.getAuthorizationClient(context).authorize(AuthorizationRequest.builder()
   .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.readonly"),
   Scope("https://www.googleapis.com/auth/drive.file"))).build())`. A missing grant comes back
   with `hasResolution()`; MainActivity launches its `pendingIntent` through
   `StartIntentSenderForResult` (the Google sheet, the only sheet Drive ever shows) and reads the
   token back with `getAuthorizationResultFromIntent`. Once the grant is held, every later
   `authorize` call returns a fresh short-lived access token silently, so the service, the
   scanner and the backup all call `DriveAuth.tokenBlocking()` without any UI. A 401 clears the
   token (`clearToken`) and asks once more; a second 401 or any other 403 sets the "Sign in to
   Drive again" line in Settings. Never a dialog. (`data/drive/DriveAuth.kt`)

   **Grants from 0.3.0 to 0.3.2** hold `drive.file` only. On the first launch after the update
   the start-up check asks silently for both scopes; that needs the user, so `DriveAuth` asks
   again for `drive.file` alone (that token still serves backups and kept copies) and records
   that `drive.readonly` is missing. Settings then shows one line, "Reconnect Drive to see the
   files in your folder", with a Reconnect action that brings up the Google sheet for the new
   scope; once granted, the kept folder is scanned at once. The same line appears when
   `AuthorizationResult.grantedScopes` lacks `drive.readonly` (the scope unticked on the consent
   screen) or when Drive answers a listing with 403 `insufficientPermissions`.
2. **The folder browser** (`ui/DriveBrowserScreen.kt`, Settings, Google Drive, Choose folder).
   The top level lists My Drive (folder id `root`), Shared with me, and each shared drive the
   account belongs to. Tapping a folder goes in; a breadcrumb row and the back arrow go up. Each
   folder row shows a count such as "12 audio", loaded lazily as the row comes on screen (one
   cheap `files.list` of up to 1000 with only `mimeType`, at most four at a time; nothing is
   shown when the folder has no audio or video directly inside it). Loading is a 1 dp line;
   an error is one line with Retry. The bottom bar shows the current folder and the
   "Use this folder" pill, disabled on the top level and on My Drive, Shared with me and the
   shared drive roots. The Drive calls (REST v3, all with `supportsAllDrives=true` and
   `includeItemsFromAllDrives=true`, `spaces=drive`, `pageSize=1000`, every page):

   | What | Request |
   |---|---|
   | Shared drives | `GET drives?pageSize=100&fields=nextPageToken,drives(id,name)` |
   | Folders in a folder | `files.list`, `q = '<id>' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false`, `orderBy=name`, fields `nextPageToken,files(id,name,mimeType,driveId)`; inside a shared drive also `corpora=drive&driveId=<driveId>` |
   | Shared with me | `files.list`, `q = sharedWithMe = true and mimeType = 'application/vnd.google-apps.folder' and trashed = false`, `orderBy=name` |
   | Row count | `files.list`, `q = '<id>' in parents and trashed = false and (mimeType contains 'audio/' or mimeType contains 'video/')`, fields `nextPageToken,files(mimeType)`, one page |
   | Account email | `GET about?fields=user(emailAddress)` |
3. **Listing.** `files.list` with `q = '<folderId>' in parents and trashed = false`, fields
   `id,name,mimeType,size,modifiedTime,createdTime,driveId`, `pageSize=1000`, every page, with
   `supportsAllDrives=true&includeItemsFromAllDrives=true` (and `corpora=drive&driveId=<id>`
   when the chosen folder lives in a shared drive, read from `files.get` before each scan).
   Subfolders are walked breadth first down to 4 levels below the chosen folder (at most 500
   folders per scan). Audio and video rows are written to the existing `media` table:
   `uri = drive://<fileId>`, `folder_uri = drive:<chosenFolderId>` for every row however deep it
   was found, so the Library shows one folder entry, named after the chosen folder, with a cloud
   icon. `rel_path` keeps the subfolder path (`2024/March/file.mp3`), so search and the Suno rule
   still see it. Title from the name without its extension, `duration_ms` 0 until the first
   play, when the service writes the player's duration (and the refreshed fingerprint) back
   into the row. Folder rows and media rows left by any other Drive folder (from the 0.3.0
   picker, or before a change of folder) are removed on the next scan; `progress` rows are never
   touched. When a subfolder fails to list, nothing is removed on that pass. Rescan on app start
   when the last listing is older than 6 hours and the silent token holds `drive.readonly`, and
   on Rescan in Settings (the Folders Rescan also lists Drive). Each scan logs
   `drive: scan: N files (T top-level entries, S subfolders walked, depth limit 4), ...`.
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
   in the time slot and a tap does nothing. A grant without `drive.readonly`: one line in
   Settings, "Reconnect Drive to see the files in your folder". Any other 401 or 403: one line,
   "Sign in to Drive again". Disconnect removes the Drive rows and kept copies; positions and
   labels stay.
7. **Settings, Google Drive.** Connected: the folder name, "N files · listed 5 min ago", the
   account email (from `about.get`), then Choose folder, Rescan and Disconnect, the reconnect or
   sign-in line when needed, and "Allow downloads on mobile data".

### Backup and restore

Settings, Backup. "Back up" opens ACTION_CREATE_DOCUMENT (default name
`lumen-backup-YYYYMMDD.json`) and writes one JSON document: positions (with the fingerprint of
each uri), labels, media_labels (uri and fingerprint), bookmarks and the kv settings minus the
device-only keys. When Drive is connected the same bytes also go to a "Lumen" folder the app
creates (`files.create`, multipart, under `drive.file`; a same-day file is updated with PATCH).
With `drive.readonly` a name search also sees any folder the owner made called "Lumen", so only
a folder and file with `isAppAuthorized = true` (made by this app) are reused. "Restore" opens
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
- Maker credit: the owner's own Instinct mark, `aaron_instinct.xml` (charcoal body, cyan
  `#28D9E8` wedge) on light surfaces and `aaron_instinct_reverse.xml` (off-white body, cyan
  wedge) on black. The amber variant `aaron_instinct_lumen_reverse.xml` remains in res but is
  not drawn; `aaron_instinct_lumen.xml` was unreferenced and was removed in 0.4.0. Shown at 20 dp above "by Aaron Almeida" (Inter 13 sp, secondary,
  centred, 24 dp padding) at the bottom of Settings and nowhere else.
- Launcher icon: still the Resume arc. `mipmap-anydpi-v26/ic_launcher.xml` now points its
  foreground at `drawable/ic_launcher_fg.xml`, a layer-list holding the arc vector. **To ship
  the glow icon:** add `res/mipmap-xxxhdpi/ic_launcher_foreground_glow.png` (432 by 432 px,
  artwork inside the central 66 percent safe zone) and change the single `android:drawable` in
  `drawable/ic_launcher_fg.xml` to `@mipmap/ic_launcher_foreground_glow`. The `<monochrome>`
  layer must stay on `@drawable/ic_launcher_foreground` (the flat arc) for themed icons.

### Drive setup for the owner (once)

No API key and no GitHub secret are needed any more: the picker and its key are gone, and the
old picker key secret in the repository's Actions settings is simply unused (delete it at will).

1. Google Cloud console: the project used so far. **APIs and Services, Library**: **Google
   Drive API** enabled (the Google Picker API is no longer used and may be disabled).
2. **Google Auth Platform, Data Access** (formerly the OAuth consent screen's scopes):
   **Add or remove scopes** and make sure both are listed:
   `https://www.googleapis.com/auth/drive.readonly` (restricted) and
   `https://www.googleapis.com/auth/drive.file`. Save. Leave the app **In production**, not
   Testing (a Testing app expires its grants after 7 days, which is exactly the weekly re-login
   the owner ruled out).
3. **Credentials**: the existing **Android** OAuth client stays as it is: package name
   `com.aaronalmeida.lumen`, SHA-1 `01:CA:FE:7A:87:31:5F:5D:C6:AC:86:14:85:C1:15:65:6E:49:1E:5B`
   (the release key from the Actions secrets). An Android client has no secret and nothing is
   pasted into the app: Play Services matches the package and the signing certificate. The old
   Picker API key can be deleted.
4. **The unverified-app screen is expected.** `drive.readonly` is a restricted scope and Lumen
   is not going through Google's verification, so the consent flow shows "Google hasn't verified
   this app". Tap **Advanced**, then **Go to Lumen (unsafe)**, then allow. An unverified app is
   limited to 100 users over its lifetime, which is far more than a family needs.
5. First use: Settings, Google Drive, **Choose folder**. The Google sheet appears once (account,
   the unverified-app screen, consent for "See and download all your Google Drive files" and
   "See, edit, create and delete only the specific Google Drive files you use with this app"),
   then Lumen's folder browser; go into the sermon folder and tap **Use this folder**. The
   listing runs at once. On a phone that already ran 0.3.0 to 0.3.2, Settings shows
   "Reconnect Drive to see the files in your folder" instead: tap **Reconnect**.

Privacy policy: `docs/privacy.html` (Google Drive section: read-only access to list and play
the audio and video in the chosen folder, nothing modified or deleted, `drive.file` only for
Lumen's own backup files, and the Limited Use statement).

## Data

Room database `lumen.db`, version 4. `MIGRATION_3_4` (0.4.0) creates the `chapters` table and
its index and nothing else (SQL in the 0.4.0 section). `MIGRATION_2_3` runs
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

0.4.0 dependencies, verified on 2026-10-27:
`androidx.window:window:1.5.1` and `androidx.window:window-core:1.5.1`
(developer.android.com/jetpack/androidx/versions/stable-channel lists "Window Version 1.5.1",
19 November 2025, as the latest stable; developer.android.com/jetpack/androidx/releases/window
for the WindowSizeClass, WindowInfoTracker and WindowMetricsCalculator notes). Compose Material3
Adaptive 1.3.0 was considered and not used: 1.3.0 deprecates `currentWindowAdaptiveInfo` in
favour of a V2, and androidx.window alone gives the width class and the fold.

0.5.0 dependencies, verified on 2026-10-27:
`androidx.media3:media3-cast:1.11.1` (developer.android.com/media/media3/cast/create-castplayer
lists it beside media3-exoplayer and media3-session 1.11.1; the androidx/media tag 1.11.1 release
notes carry `CastPlayer.Builder` (1.9.0), `CastParams` and `MediaRouteButtonState` (1.11.0)) and
`com.google.android.gms:play-services-cast-framework:22.3.1` (developers.google.com/android/guides/releases,
7 April 2026; it is the version media3-cast 1.11.1 is built against, `gmsPlayServicesCastFrameworkVersion`
in androidx/media 1.11.1's `gradle/libs.versions.toml`). 23.0.0 (19 October 2026) exists but moves
the Cast framework onto `androidx.mediarouter:mediarouter:1.9.0-beta01`, so it is not taken yet.
media3-cast brings `androidx.mediarouter:mediarouter:1.8.1`, `androidx.appcompat:appcompat:1.6.1`
and Compose Material3 (resolved to this project's BOM) with it. NanoHTTPD was not added: the
Chromecast file server is about 300 lines on `java.net.ServerSocket` (`cast/CastMediaServer.kt`).

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
| 13 | Backup export | See the Phase 1b sheet below |
| 14 | Share diagnostics | Zip arrives with log, device info, media permission state, last MediaStore scan time and count, media count, folder list, table dump and backup tail |
| 15 | Pick Speech, Bass, then Custom and drag a band; play, pause, swipe away, reopen | No click on preset change; the chosen preset and custom bands are still shown and audible |

Resume rule under test everywhere: within 3 s of the request, at the saved position minus 3 s.

## Phase 1b sheet

Needs a Drive folder with a few sermons (one in a subfolder two levels down), the Data Access page
listing `drive.readonly` and `drive.file`, and the phone on Wi-Fi to begin with.

| Step | What to do | Pass when |
|---|---|---|
| 1 | Cold start after install | Black system splash with the arc, then the LUMEN lockup on black for well under a second, one fade, then Home; a warm start (back to Recents and back) shows neither |
| 2 | Settings | The lockup sits above Folders; the Aaron Instinct mark and "by Aaron Almeida" sit at the very bottom and nowhere else; the Google Drive card shows "Choose folder" (on a phone upgraded from 0.3.0 to 0.3.2 with a folder kept: the folder, and the line "Reconnect Drive to see the files in your folder" with Reconnect; Reconnect shows the Google sheet once, the line clears and the scan finds the files) |
| 3 | Choose folder | The Google sheet appears once (account, "Google hasn't verified this app": Advanced, Go to Lumen, then consent), then Lumen's folder browser: My Drive, Shared with me and any shared drives; "Use this folder" is disabled there; go into the sermon folder (rows show counts such as "12 audio" as they appear, a 1 dp line while loading) and tap Use this folder; the Drive card shows the folder name, "N files · listed just now" and the account email; Library lists one folder named after it with a cloud icon, holding the files from its subfolders too, time "--:--" |
| 3a | Add a file to a subfolder from a computer, then Rescan | The count rises by one; the log line `drive: scan: N files (... S subfolders walked ...)` records it |
| 3b | Choose a folder in Shared with me, then one in a shared drive (if there is one) | Both list their files; choosing again replaces the previous folder's rows, positions stay |
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

## 0.4.0

One release for the Phase 2 items. The Phase 0 promise is unchanged: a saved position is never
written over with 0, and speech resumes at the saved position minus 3 s. `PositionStore` is not
touched. In `PlaybackService` the transition guard (`pendingResumeId`, `save`, the "left item"
save) is not touched; the one change to the resume path is item 4 (below): `onNewItem` and
`onSetMediaItems` decide whether to look up the saved position with `resumes` instead of
`speech`, because a long voice note resumes while playing in the music layout.

1. **Appearance.** Settings, Appearance: Automatic, Light, Dark (kv `appearance`). Automatic
   follows the phone's dark theme (`isSystemInDarkTheme`, read from the configuration's uiMode)
   and recomposes when it changes. A phone with the 0.3 Night toggle on gets `appearance=dark`
   written once on start; until then the parser reads `night=1` as Dark too. Now Playing stays
   black in every mode. `values-night/themes.xml` makes the start-up window black at night so
   Automatic does not flash the light ground.
2. **Clarity for sermons.** Settings, Sound, and the "Clarity" chip menu on the speech Now
   Playing, with two switches. *Level out voices* (kv `level_out`): the Speech preset already
   compresses (MBC -24 dB, 3:1, +4 dB), so on a speech item the band goes to -30 dB, 3.5:1,
   release 200 ms, makeup +6 dB, and the limiter from -2 to -3 dB; never on music, and disabled
   with the Equaliser fallback engine (no compressor there). *Trim silences* (kv
   `trim_silences`): `ExoPlayer.setSkipSilenceEnabled(true)` (Media3's
   SilenceSkippingAudioProcessor) only while a speech item is current; music never. **No
   "Trimmed" counter:** Media3 1.11.1 reports a skip only as
   `Player.DISCONTINUITY_REASON_SILENCE_SKIP`, and its old position is the app thread's last
   published position, not the position before the skip (`ExoPlayerImpl.getPreviousPositionInfo`),
   so the skipped amount cannot be read; `onSkipSilenceEnabledChanged` carries only the switch.
   Per the brief, the counter is omitted.
3. **Chapters and notes.** Long press the Mark chip on the speech Now Playing: a sheet in the
   Label sheet's style with a title field; the chapter is saved at the position of the press to
   the new `chapters` table. The Chapters list shows chapters and bookmarks together by time
   (tap seeks, long press removes, the row the playhead is in has the ink dot and an ink
   timecode). Its overflow has "Export notes": a plain-text file in `cacheDir/notes` (title,
   speaker, date the file was added, then "47:12  Title" lines) shared through the FileProvider
   (`notes/` path) with ACTION_SEND. Chapters are in the backup JSON (`chapters`: uri,
   fingerprint, position_ms, title, created_at) and merge on restore by uri, position and title.

   `MIGRATION_3_4`:

   ```sql
   CREATE TABLE IF NOT EXISTS `chapters` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `media_uri` TEXT NOT NULL, `position_ms` INTEGER NOT NULL, `title` TEXT NOT NULL, `created_at` INTEGER NOT NULL);
   CREATE INDEX IF NOT EXISTS `index_chapters_media_uri` ON `chapters` (`media_uri`);
   ```
4. **WhatsApp voice notes and audio.** The phone scan still leaves out `Android/`, except
   `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/` and `.../WhatsApp Audio/`,
   and the WhatsApp Business equivalents under `Android/media/com.whatsapp.w4b/WhatsApp Business/Media/`
   (both the "WhatsApp Business Voice Notes" and "WhatsApp Voice Notes" spellings). Every voice
   note, whatever dated subfolder WhatsApp put it in, is filed under one Library folder,
   `mediastore:whatsapp-voice-notes`, named "WhatsApp voice notes", always listed newest first;
   WhatsApp Audio keeps its own buckets. Voice notes skip the art read. Under 20 minutes a
   voice note plays in the music layout (the 20 minute rule, unchanged for everything else);
   over 3 minutes it resumes at its saved place minus 3 s like speech
   (`MediaRepository.resumes`, carried on the queue item as `lumen.resume`).
5. **Smooth transitions.** Settings, Sound: Off, 2, 4 or 6 s (kv `crossfade_s`, default Off).
   A fade through, not an overlap, so it is labelled "Smooth transitions": the volume ramps down
   over the last N seconds of a music item and up over the first N seconds of the next, with
   `player.volume`, from a coroutine that checks the position every 50 ms inside a ramp and
   otherwise sleeps until the window opens (at most 1 s). Only music; never speech, a voice
   note, video, an item with a Lock for kids label, repeat one, or when the next item is speech,
   a voice note or remote (Drive, http). The fade-out starts only when playback crosses into the
   window, so a seek into the last seconds plays at full volume. Every pause, seek, skip,
   non-automatic transition, change of the setting and ineligible item sets the volume back to
   1. While the sleep timer fades, the transition fade leaves the volume alone (sleep wins).
6. **Foldables and large screens.** `androidx.window`: from 600 dp of window width
   (`WindowSizeClass.BREAKPOINTS_V1`, `isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND)`,
   from `WindowMetricsCalculator`) the app shows two panes as in the mockup's foldable frame: a
   Home and Library rail and the Library side (Home, Library, Settings) on the left in the
   Appearance colours, a hairline, and Now Playing on the right, always black and always shown
   (no close chevron there). A half-opened fold with a horizontal hinge (`WindowInfoTracker`,
   `FoldingFeature.State.HALF_OPENED`, `Orientation.HORIZONTAL`) splits Now Playing at the
   hinge: title, art and scrubber above, chips, transport and chapters below. Phones keep one
   pane. Kids lock always takes the whole window.
7. **Small fixes.** A manual pause (app, notification, widget, headset:
   `PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST`) cancels the sleep timer; audio focus loss (a
   call), becoming noisy and the timer's own pause do not. The Continue card's playing time is
   in the ink on light. `aaron_instinct_lumen.xml` was unreferenced and is removed.

### 0.4.0 sheet

Needs a sermon over 20 minutes, a few music files (two in a row with no labels), WhatsApp with a
voice note over 3 minutes and one under, and, for item 6, a Galaxy Z Fold or a resizable
emulator (Pixel Fold, or a tablet).

| Item | What to do | Pass when |
|---|---|---|
| 1 | Install over 0.3.x with Night on; open Settings | Appearance shows Dark selected; Home is black |
| 1 | Pick Automatic, switch the phone's dark theme on and off from quick settings | Home, Library and Settings follow at once; Now Playing is black throughout |
| 1 | Pick Light with the phone dark, then Dark with the phone light | Each sticks; reopening the app keeps it |
| 2 | Play a sermon, open the Clarity chip, switch Level out voices on | Quiet passages come up, loud ones do not get louder; the chip turns active; Settings, Sound shows it on |
| 2 | Switch Trim silences on during a sermon with pauses | Pauses shorten; the position stays right after pause and resume (saved minus 3 s) |
| 2 | Play music with both on | No trimming and no extra compression on music (the log shows `skip silence off` and `levelOut=false`) |
| 3 | Long press Mark at some point in a sermon | The sheet opens with that time; type a title, Save; the Chapters list shows it among the bookmarks by time; tap seeks to it |
| 3 | Long press a chapter row | It is removed |
| 3 | Chapters overflow, Export notes, share to Notes or Gmail | A text file: title, speaker, date, then "47:12  Title" lines in time order |
| 3 | Back up, reinstall, scan, Restore | Chapters return with "N chapters" in the restore line |
| 4 | Rescan the phone | Library shows one folder "WhatsApp voice notes", newest first even with the Name sort; nothing else from Android/ appears; WhatsApp Audio appears as its own folder |
| 4 | Play a voice note over 3 minutes to the middle, play something else, come back | Music layout; it resumes at saved minus 3 s |
| 4 | Play a voice note under 3 minutes, leave, come back | It starts at 0 |
| 5 | Smooth transitions 4 s, play two music files in a row | The first fades out over its last 4 s, the second fades in over its first 4 s |
| 5 | Pause, seek or skip in the middle of a fade | Volume is back to full at once and stays there |
| 5 | Music followed by a sermon, a Drive file or a voice note; or a Kids label | No fade |
| 5 | Sleep timer 15 min ending during a fade | The sleep fade wins; after it pauses and you press play, the volume is full |
| 6 | Unfold a Fold (or resize an emulator past 600 dp) | Rail and Library on the left, Now Playing black on the right; tapping a row plays it on the right; Back moves within the left side only |
| 6 | Half fold the Fold in landscape (tabletop) with Now Playing showing | Title, art and scrubber above the hinge; chips and transport below; nothing on the hinge |
| 6 | Fold it shut (cover screen) | The phone layout, as before |
| 7 | Set the sleep timer, pause in the app | The Sleep chip goes back to "Sleep" |
| 7 | Set the sleep timer, take a call | The timer keeps running |
| 7 | Home on light while playing | The Continue card's playing time is in the deep cyan ink |

Positions under test throughout: every resume is at the saved position minus 3 s, and a seek,
fade or trim never writes 0 over a saved position.

## 0.5.0

Owner rulings D9 (Chromecast, Phase 3) and D1 (YouTube Music as a universal remote). The position
promise is unchanged: a saved position is never written over with 0, and speech resumes at the
saved position minus 3 s. `PositionStore` is not touched. `PlaybackService`'s save guard gains one
condition beside `pendingResumeId` (the cast handover, below) and a skip for YouTube Music uris;
both only ever prevent a write.

### Chromecast

- **Player.** Media3's documented pattern (developer.android.com/media/media3/cast/create-castplayer):
  `CastPlayer.Builder(this).setLocalPlayer(exo).setRemotePlayer(remote).setTransferCallback(...)`,
  wrapped by `SkipPlayer` and handed to the one `MediaLibrarySession`. When a Cast session starts or
  ends, CastPlayer swaps the active player underneath the session (its `setPlayer`), so the
  notification, lock screen, widget and Auto keep talking to the same session. ExoPlayer, its audio
  session, the effects and silence skipping stay exactly as they were.
- **Handover** (`PlaybackService.CastTransfer`). Before the queue moves, the source position is
  saved ("cast start" or "cast end"), never when it is 0 and never while a resume lookup or another
  handover is settling. Then `castHandoverId` blocks every snapshot of that item (and the "left
  item" save the swap looks like) until the new player reports READY within 5 s of the handed-over
  position, or the owner seeks. Coming back, a 0 from a receiver whose session is already gone is
  replaced by the last cast position Lumen noted in memory (once a second, never written). To the
  receiver: speed 1.0 and no YouTube Music items (a queue whose current item is YouTube Music is not
  sent). Back on the phone: the cast position, paused, at the item's own speed. Ending a cast
  never starts the phone playing by itself.
- **What is cast.** The Default Media Receiver (`CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID`,
  from `cast/LumenCastOptionsProvider`, named in the manifest) fetches a plain URL and cannot add
  headers, so it can neither read a `content://` file nor send a Drive bearer token. The phone
  serves the file itself (`cast/CastMediaServer`): bound only while casting, on the Wi-Fi IPv4
  address (never mobile data, never all interfaces), port 0, GET and HEAD with Range (206 and
  Content-Range, `bytes=a-b`, `a-`, `-n`), one request per connection. Each item handed to the
  receiver in this session gets an unguessable path `/m/<32 hex>` from SecureRandom; anything else
  is a 404 and every token is forgotten when the session ends. Bytes come from Lumen's own data
  source factory, so local files, the Drive offline copy, the 200 MB cache and Drive with the token
  all work. `cast/LumenCastConverter` keeps the Lumen uri as the Cast content id, so the receiver's
  items map back to the original MediaItems and positions keep their keys; local art is served the
  same way; video goes as a movie.
- **Sound.** EQ, Level out voices, Trim silences and Smooth transitions act on ExoPlayer only and
  so do not apply while casting; fades and the sleep fade now set ExoPlayer's own volume, so the
  TV's volume is never touched. Speed is 1.0 on the receiver and the speed chip is disabled. One
  ice line sits under the chip row: "Casting: sound settings apply on the phone only".
- **Button.** Media3's Compose `MediaRouteButton` on the Now Playing top row, before the label
  button, shown only while the Cast framework reports a device (`CastState` is not
  NO_DEVICES_AVAILABLE, `cast/CastAvailability`) or while casting; ink while casting.
- Kids lock works as before: the pads drive whichever player is active.

### YouTube Music remote

- **Adding.** Share a song or playlist from YouTube Music to Lumen (ACTION_SEND text/plain).
  `remote/YtmLink` reads `music.youtube.com/watch?v=`, `youtube.com/watch?v=`, `youtu.be/` (a song,
  `ytm://<videoId>`) and `music.youtube.com/playlist?list=` (a playlist or album, `ytm-list://<listId>`),
  with the title and artist from the subject or text ("Title - Artist") when present. The row goes
  in the Library folder "YouTube Music" (`ytm:`) with a "via YouTube Music" chip, duration 0 (music
  layout), and the label sheet opens. Any other shared text gets one short line. Rows sit in labels
  beside local files; Android Auto never lists them. They are not in the backup file (only their
  label links are).
- **Playing.** A YouTube Music item is set as the current queue item (ExoPlayer holds it on one
  second of generated silence, so the queue can move into it) and the service stops at once, with
  its notification hidden (`SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER` until the queue moves on). The
  ViewModel opens `https://music.youtube.com/watch?v=<id>` (playlists `watch?list=<id>`) in
  `com.google.android.apps.youtube.music` and brings Lumen back to the front 600 ms later
  (`RemoteController.RETURN_DELAY_MS`). No position is stored for these items.
- **Controlling.** With Notification access (Settings, YouTube Music, "Allow Lumen to control
  YouTube Music"; the system page opens from that row only), `remote/RemoteController` finds YouTube
  Music's session through `MediaSessionManager.getActiveSessions` with `LumenNotificationListener`
  as the credential, and Now Playing shows remote mode: YouTube Music's art, title and artist, its
  position on the scrubber, cyan transport (previous, play, next through its TransportControls),
  the "via YouTube Music" chip and a greyed EQ chip with "EQ is handled by YouTube Music".
  Without access, or before YouTube Music's session appears: "Open YouTube Music to control
  playback" and a button, and the queue does not move on by itself.
- **End of item** (`remote/YtmWatch`). The first track YouTube Music plays after Lumen opened the
  link is the baseline (at once when its media id is the video id, otherwise after 2.5 s of
  playing). The item is over when YouTube Music stops, pauses on its last second, or, for a song,
  plays a different track. Lumen then pauses YouTube Music and plays its next queue item, local or
  YouTube Music. A playlist moves on only when YouTube Music stops or pauses at its end. While
  YouTube Music plays, the ViewModel keeps its controller connected after the activity stops, so
  this also works with Lumen in the background; opening a second YouTube Music item from the
  background is only possible when YouTube Music's session accepts play-from-uri, otherwise it
  opens the next time Lumen is on screen.

### 0.5.0 sheet

Needs a Chromecast (or a Google TV with Cast) on the same Wi-Fi, a sermon over 20 minutes on the
phone, the Drive folder from Phase 1b, YouTube Music installed and signed in (Premium), and a label
holding two local songs.

| Item | What to do | Pass when |
|---|---|---|
| Cast | Open Now Playing with no Cast device on the network | No cast button |
| Cast | Turn the Chromecast on | The cast button appears before the label button within a few seconds |
| Cast, local | Play the sermon on the phone to some minute, tap the cast button, pick the Chromecast | The TV picks the sermon up within a few seconds at the phone's position (not 0, not minus 3 s); the button turns ink; the ice line "Casting: sound settings apply on the phone only" shows under the chips; the speed chip is greyed; the scrubber follows the TV |
| Cast, local | Seek on the phone's scrubber, pause and play from the notification | The TV follows each; Share diagnostics shows `cast: server: listening on 192.168.x.x:<port>` and saves with reason `seek`, `heartbeat` |
| Cast, Drive | Cast a Drive sermon that is not kept offline, then one that is | Both play on the TV; seeking on the phone works (Range requests) |
| Cast, Drive | Cast a Drive file with the phone on Wi-Fi only, then turn Wi-Fi off | The TV stops within its buffer; casting ends; nothing is served over mobile data |
| Cast, end | Mid-sermon, stop casting from the cast button (route controller, Stop casting) | The phone shows the sermon paused at the TV's position to the second; the server is stopped (`cast: server: stopped`); press play: the phone plays from there, at the sermon's speed |
| Cast, end | Then leave the app, swipe it away, open it again | The Continue card and Resume are at that position minus 3 s, never 0 |
| Cast, sound | With Smooth transitions on, cast two songs in a row; set the sleep timer | No fade on the TV and the TV's volume never moves; the timer pauses the TV |
| Cast, kids | Play the Kids label, cast it | The three pads still play, pause and skip on the TV; the corner hold still exits |
| YTM, add | In YouTube Music, Share on a song, pick Lumen | Lumen opens on the Library's "YouTube Music" folder with the label sheet; tick a label; the row shows "via YouTube Music" and no time |
| YTM, add | Share a playlist; share plain text from another app | The playlist lands as a row; the plain text gives one short line and no row |
| YTM, label, without access | Settings, YouTube Music shows "Allow"; put the YouTube Music song between the two local songs in one label (dates or Name sort), play the label | Local song 1 plays; at its end Lumen flashes to YouTube Music (about a second) and comes back; the song plays in YouTube Music; Now Playing reads "Open YouTube Music to control playback" with the button; Lumen's own notification is gone; the queue stays on the song when it ends |
| YTM, label, with access | Allow in Settings (the row reads "On"), play the label again | At the YouTube Music song Now Playing shows its art, title, artist and moving position; pause, seek and play act on YouTube Music; the EQ chip is greyed with its line; when the song ends Lumen pauses YouTube Music and local song 2 plays with Lumen's notification back |
| YTM, label, with access | Repeat with the screen off during the YouTube Music song | Local song 2 still starts when the song ends (best effort: Android may refuse to start playback from the background) |
| YTM, positions | Share diagnostics after the runs | No `store: saved ytm://` lines; the sermon's rows are untouched |

## 0.5.1

### Library sections
The Library opens on a row of section chips under the title: All, Phone, Drive, YouTube Music, Suno, each with its count. The last section chosen is remembered.
- All: every file, no folders.
- Phone: the phone's folders (MediaStore buckets, picked folders, WhatsApp) and their files.
- Drive: a card with the folder, file count, when it was listed and the account, with Rescan, Change folder and Reconnect when needed; the first level of subfolders inside the Drive folder as folder rows; then the files. A large Drive audio file with no duration yet (over 40 MB) is shown as a sermon.
- YouTube Music: shared songs and playlists; an empty state explains Share to Lumen; a hint row offers notification access when it is off.
- Suno: every file the Suno rule labelled, from the phone or Drive; an empty state explains the folder naming.
- Back clears a folder before it leaves the Library.
- "Use this folder" in the Drive browser now lands on the Library's Drive section, where the listing shows as it runs. Back from the browser's top level returns to the screen that opened it.

### 0.5.1 sheet
1. Open Library: the chips show All, Phone, Drive, YouTube Music and Suno with counts.
2. Tap Drive: the card shows the folder name and file count; subfolders show as rows; tap one to filter, Back to clear.
3. Tap Change folder, pick a folder: Lumen lands back on the Drive section with the listing line, then the count.
4. Tap YouTube Music and Suno: either the files or a card saying how to fill the section.
5. Close and reopen Lumen: the Library opens on the last section chosen.

## 0.8.2 (red team round 2, the "later" list)

- Played and unplayed: a sermon listened to the end (last 30 s or 97 percent) shows a tick and "Played"; a started one a thin bar of how far through. Library has an Unplayed chip (sermons not yet finished). The item sheet has Mark as played / Mark as unplayed.
- Stop after each sermon (Settings, Sound; on by default): the player pauses when a sermon ends (pauseAtEndOfMediaItems); Play carries on to the next item.
- Sleep timer: a manual pause holds the timer (time left kept, tile shows it) and play picks it up; Turn off clears it.
- Back a little after a pause, sermons only: 5 s after a minute, 15 s after ten minutes, 30 s after an hour or after the sleep timer stopped it.
- Drive: "Download new sermons automatically" (Settings, Drive; off by default). After each listing, sermons new since the last one are kept on the phone over Wi-Fi; turning it on keeps the three newest now. The first listing of a folder never starts downloads. Disconnect asks twice.
- Sound: music presets that lift a band lower the input by the biggest lift (DynamicsProcessing input gain), so loud songs do not pump.
- Backups now carry YouTube Music songs, playlist order, and sermon notes with transcripts. A restore into an empty library says to scan first and restore again.

## 0.8.1

### Pick songs from a YouTube Music playlist
- Sharing a playlist or album link from YouTube Music to Lumen (or "Pick songs from a YouTube Music playlist" in Playlists, or "From YouTube" inside a playlist) opens a song picker: every song with its artist and length, ticked one by one or all at once, then added in the playlist's order to a Lumen playlist (an existing one, or a new one named after the YouTube playlist). Each song is its own row that plays in YouTube Music. "Add the whole playlist as one link instead" keeps the 0.5.0 behaviour.
- YouTube Data API v3 (playlists.list, playlistItems.list, videos.list for lengths) with the owner's Google account through Play Services AuthorizationClient, scope youtube.readonly, asked once. Deleted and private videos are left out. Mixes YouTube makes on the fly (radio) cannot be read.
- Owner setup (once), Google Cloud project 189838965059: enable "YouTube Data API v3"; on the Data Access page add the scope youtube.readonly.

## 0.8.0

### Playlists tab
- Third tab: Home, Library, Playlists. A playlist is what a label was (Kids lock, No interruptions and Shuffle on open stay as its settings); it now keeps an order.
- Room v6: `media_labels.position` (numbered as added by the migration); new members go to the end. The destructive migration fallback is removed: a missing migration must never wipe saved places.
- Overview: each playlist with its count, total time, sources (Phone, Drive, YouTube Music) and a play button. Detail: Play, Shuffle, Add, Edit; rows in order with a drag handle (the order saves when the finger lifts), tap plays from that row, the menu removes it. Add: every file in the library by section with search; a tick adds to the end or takes it out.
- "Labels" reads "Playlists" everywhere (the item sheet, Now Playing's tile and More).

### YouTube Music controls without notification access
- Once YouTube Music has the song, play, pause, previous and next reach it as media keys (AudioManager.dispatchMediaKeyEvent, no permission). Playing or paused comes from AudioManager.isMusicActive while Lumen is paused. Next in Lumen moves Lumen's queue on. Lumen still cannot read YouTube Music's title, position or the end of its song.

## 0.7.0 (Now Playing redesign; Aaron, 2026-10-28: Option B for sermons, Option A for music)

- Nothing scrolls sideways and nothing needs a long press. The hint lines are gone.
- Sermon: title, scrubber, back 10, play, forward 30, then four tiles (Speed, Sleep, Mark, More), then the companion panel filling the rest of the screen with Notes, Chapters and Transcript. Notes opens on the section now playing (amber time); the transcript follows the line now playing until the listener scrolls ("Follow along" brings it back). On a short screen (a phone on its side) the panel becomes Notes and Chapters buttons.
- Music: art at 82 percent width, title, scrubber, transport, then five tiles (Sleep, Labels, Sound, Queue, More), then what plays next.
- Speed, Sleep, Sound and Queue open a list showing every choice (Sleep adds "End of this recording" and 90 and 120 minutes). More holds Sound and the two clarity switches (sermons), Speed (music), Labels, New chapter here, Keep offline, Audio only (video), Notes full screen, Show as music or sermon, Share chapter list.
- Mark saves the moment and shows "Marked 47:12 · Name it"; naming turns it into a chapter. Each chapter row has a menu with Name it and Remove; Remove offers Undo.
- Sleep "End of this recording" pauses when the item ends (pauseAtEndOfMediaItems), whatever skips or speed changes happen meanwhile; no fade. Labels marked No interruptions hide the Sleep tile.
- The Queue sheet is a lazy list opened at the item playing. The transcript stops following on a real drag; a "Follow along" button floats over it.
- The eyebrow says Sermon instead of Speech. Notes opened from Now Playing stay on black.
- Library sections wrap instead of running off screen. Home's pill reads "Open player" while playing.

## 0.6.1 (red team round 2 fixes; report in RED-TEAM-ROUND-2.md)

- Position: one speech rule everywhere, including a large audio file whose length is not known yet (a Drive sermon before its first play), so it resumes and takes the sermon layout from the start. The service decides from the fresh library row. A load-time save near 0:00 never replaces a place more than 30 s in.
- A finished item (last 30 s or 97 percent) starts again at 0 instead of replaying its last 3 seconds.
- Saves land one at a time in the order asked for.
- Playback errors show on Now Playing and the mini bar in plain words, with Try again.
- Drive: network wake mode (Wi-Fi lock) with the WAKE_LOCK permission declared; offline, the cache plays without a token and no "sign in again" is raised.
- Open with: matched in the database (ready on a cold start), by uri or size plus name; a file with a one-off permission is never made the resume target.
- Lock screen, notification and car: sermons keep back/forward by the skip lengths; songs get previous and next.
- Music and video duck for notification sounds; sermons pause.
- YouTube Music without control: Next moves Lumen's queue; "Next in Lumen" button.
- Cast: a missing file type is worked out from the name.
- Video: screen stays on while a video plays; a long video keeps its picture in the sermon layout; rotation no longer restarts the screen; a landscape phone keeps one pane.
- Notes: wake and Wi-Fi locks during the job; carries on until the transcript reaches the end; clearer errors; "Set up notes" goes to Settings; the time limit on Android 15 and later is handled.
- Now Playing: chips wrap instead of scrolling sideways; Notes comes first; music gets a Sleep chip. The full redesign follows in 0.7.0.

## 0.6.0

### Installs again
- 0.5.0 and 0.5.1 asked for notification access (the YouTube Music control). Play Protect blocks sideloaded apps that ask for it, so neither could install. 0.6.0 drops the listener; `RemoteController.CONTROL_ENABLED` is false and every row that offered it is hidden. Shared YouTube Music songs still open and play in YouTube Music.

### Sermon notes (Gemini, the owner's own key)
- Settings, Sermon notes: paste a Gemini key from Google AI Studio. Stored in private preferences (`lumen_secrets`), never in `kv`, so never in a backup.
- Now Playing, Notes chip (audio on the phone or in Drive). Write notes needs Wi-Fi.
- `NotesService` (foreground, dataSync): uploads the recording to the Files API (the kept copy, the phone file, or straight from Drive), asks `gemini-3.8-flash` (fallback `gemini-3.6-flash`) for a timed transcript as JSON segments, carrying on past the output limit up to 6 calls, deletes the upload, then asks for notes from the transcript text alone.
- Notes template (Aaron, 2026-10-27): Study notes and Journal mixed with nothing added. Sections in the speaker's order with time, scriptures cited, what was said as paragraphs, points with times, lines word for word; announcements last. The prompt forbids questions, prayers, applications and summaries the speaker did not say.
- Every time plays from itself. The transcript tab searches its lines; the line now playing has the amber time.
- Room v5: `notes` (one row per uri: status, step, JSON, model) and `transcript_segments`. A job cut short by the process dying is marked stopped at the next start.

### Search what was said
- Library search of three letters or more also lists "Said in recordings": tap plays from that line, hold opens the transcript at the search.

### Deferred
- True crossfade (two players) moves to 0.6.1. Notes are not in backups yet.

### 0.6.0 sheet
1. Install 0.6.0 over 0.4.1. It should install without the Play Protect block.
2. Settings, Sermon notes, Get a key, sign in to Google AI Studio, create a key, copy it, paste, Save.
3. Play a sermon, tap Notes, Write notes, allow notifications. Leave Lumen; the notification shows each step.
4. When done: read the notes, tap a time, check the jump lands on what was said.
5. Transcript tab: search a word; Library search the same word.

## Licence

Not yet decided; private use. See `NOTICE`.
