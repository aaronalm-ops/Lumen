# Lumen 0.8.4 local verification

8 November 2026. Changes are based on GitHub commit 34409de (v0.8.3), on branch `fix/youtube-auto-seeker`. This is an AI-assisted implementation for Aaron's review and device testing.

## Automated checks

24 tests passed with no failures or skips on the final source. The suite includes:

- YouTube and Drive requests both isolate their scopes, and a Drive-only token cannot be accepted as a YouTube connection.
- Account playlist pagination includes private playlists on later pages. Imports read beyond the former forty-page cutoff, preserve order, omit unavailable entries, and fail rather than returning a partial result after a failed or repeated page.
- Real Room import journeys preserve selected order, avoid duplicate members on repeat import, create unique playlist names, and roll back a removed-destination failure. Global entry points default to a new playlist; the Playlists entry keeps its current destination.
- The real MainActivity opens the YouTube browser from Playlists, Library and Settings, returns to each, and returns from Android Auto setup with system Back.
- The media service is discoverable through its legacy browser intent filter, is exported and enabled, starts without an activity, returns its root categories, pages playlist contents in user order, and excludes YouTube links from those results.
- The seeker dispatches once on gesture release, supports TalkBack progress actions, and disables seeking for unknown durations. Native rendered frames differ during playback and become identical after pause.
- YouTube error recovery and private-playlist selection remain reachable in the UI.

## Visual checks

Native Robolectric renders were inspected for the music player and animated ribbon, YouTube account list, YouTube connection error and Android Auto setup. Sample titles and playlists in these renders are synthetic fixtures.

## Device checks still required

1. Install the signed update over the existing app without clearing data. Check that the existing library, playlists and sermon positions remain present.
2. Open Library > YouTube Music > Browse YouTube playlists > Connect YouTube. Select the account used in YouTube Music. Check an owned private playlist and a public playlist link, then confirm Drive still browses and plays.
3. Follow Settings > In the car > Set up Android Auto. Android Auto's installation-source policy can hide a sideloaded APK; the supported media-app Unknown sources testing setting or a suitable Play installation is external to Lumen. Check discovery and playback in the physical car.
4. Check music and sermon seeking on the phone, including chapters, pause, rotation and a foldable/tabletop layout if available. Samsung's system media card is rendered by One UI and requires its own device check.

No phone was connected, and local browser access was disabled. Live Google authorization, physical-car visibility and Samsung's media-card appearance are therefore unverified. YouTube audio continues to play in the official YouTube Music app. The current build does not enable the notification listener or promise automatic progression through a mixed-source queue after a YouTube song.

## Build and delivery

Version name 0.8.4, version code 56, application ID `com.aaronalmeida.lumen`. The build uses the existing Lumen release keystore. The final APK certificate was verified against the existing release identity, and the installer was copied into the sibling `releases/0.8.4/` folder. Its SHA-256 is `403534e678c31df3633b05561420c6302537b7b532b6abb9a8562a4cfb634930`.

This change has no database schema migration or new manifest permission. The updated CI workflow runs the regression suite before assembling a release, but CI has not run for this local branch. The verified local APK is the artifact for GitHub release v0.8.4. Google Play distribution and live device checks remain separate.
