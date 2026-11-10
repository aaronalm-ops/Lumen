# Lumen 0.8.5 verification

10 November 2026. AI-assisted implementation for Aaron, based on commit `631b908` (0.8.4). This update addresses stalled sermon transcription, YouTube handoff and stale queue selection, and downward dismissal of Now Playing.

## Automated evidence

Final signed-build suite result: 41 tests passed, zero failures, errors or skips. The complete suite and signed release assembly succeeded together..

- A local HTTP server exercises the real NotesService, Gemini HTTP client, SSE reader, Room transcript storage and preferences. A streamed transcript is interrupted by a synthetic Google quota failure. Saved text survives a fresh service instance; retry requests continuation after the saved timestamp and finishes without duplicates. Rewriting notes reuses the completed transcript with no new upload. Only synthetic bytes, text and credentials are used.
- A silent local generation endpoint is interrupted by the configured request deadline instead of leaving the job indefinitely pending.
- Stream parsing merges fragmented events, ignores thinking text, rejects truncated connections and surfaces error events inside an HTTP 200 response. Transcript batches continue across output limits, preserve partial text on exceptions and reject insufficient coverage of a known recording duration.
- Completion checkpoints reject changed source files and mismatched saved transcript counts. Learning the recording's duration during playback does not invalidate the audio checkpoint.
- The real MainActivity and Media3 service exercise a four-item mixed queue starting at its second song. The journey checks original ordering, previous/next selection and titles, the first-item boundary, retention while leaving for YouTube, dismissal to the originating playlist, and moving from YouTube to a local item. The local item is a selection fixture, not an audio-decoding fixture.
- Activity handoff tests inspect the outgoing song intent and delayed return into the existing Lumen task. Explicitly opening YouTube cancels a pending automatic return. These intent tests do not prove how the installed YouTube app or an OEM handles the switch.
- Native Compose gestures check full downward dismissal, short-pull rejection, scrolling within content and horizontal seeking. The UI checks previous/next bounds, saved transcript visibility, retry and timed playback from saved text.
- Existing YouTube scope isolation, account browsing, pagination, Room import, entry-point navigation, Android Auto browser discovery, paging/order, seeker animation and accessibility checks remain in the suite.

Native previews of YouTube Now Playing, the seeker and the transcript failure/retry screen were inspected. Preview content is synthetic.

## Scope and device checks

No new notification/session access permission or database schema migration is introduced. The existing release signing identity and production application ID are retained.

Install over the current app without clearing data, then check:

1. On Wi-Fi, retry the failed Drive sermon. Check the current stage and saved-line count, open Transcript while it works, and confirm notes eventually appear. If Google refuses or stalls, the error should name the stage and retry should keep saved text.
2. Open an imported multi-song playlist and select a middle song. Check that YouTube Music starts it and returns to Lumen. Use Lumen's previous/next buttons and confirm both playback and Lumen's selected title change in playlist order. Check the first and last items.
3. Pull down from the top of Now Playing. Confirm the originating playlist reappears and playback continues. Check the chevron, normal scrolling and seeking too.
4. Confirm existing Drive playback, YouTube connection, seeking and resume still work. Physical Android Auto visibility remains the outstanding check from 0.8.4.

Live Gemini output and latency, the actual YouTube Music handoff on the phone and physical Android Auto are unverified in this environment. Changes made independently inside YouTube Music cannot be mirrored while session access is disabled. Lumen's buttons now select its own known playlist items; this does not add automatic mixed-queue progression after a YouTube song ends.

## Build and delivery

Version 0.8.5, version code 58, application ID `com.aaronalmeida.lumen`.

APK SHA-256: `ee86dcb102bea3525bc7ae3b123467068b338def1f46e30dbdd4b9458ce8cba3`.

The signing certificate was verified against the existing release SHA-256 `e066c5323008406bc2b092289117a09620d45a653ed5cd84e2d0778e8c68e122`. The signed APK is intended for GitHub release `v0.8.5`, with an anonymous download and matching digest verified before delivery is reported.
