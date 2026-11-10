# Lumen 0.8.6 verification

10 November 2026. AI-assisted implementation for Aaron, based on commit `7d0e6f9` (0.8.5).

## Fixed design

Aaron explicitly requested that artwork never change Lumen's colours or styling. Now Playing always uses its product background; the legacy artwork tint column is ignored and new artwork caching no longer derives a palette. The existing in-app mini player and widget already use fixed product tokens. Cover images remain within their artwork blocks.

The player exposed through the media session replaces original artwork bytes and artwork URIs with a constant Lumen brand image. Both metadata getters and metadata-change callbacks follow this policy, while titles, artists and transport behavior are preserved. The original cover remains available to the app from its library cache.

Android and Samsung control the final system card layout and colour processing. Lumen controls the artwork it supplies to its own session; it cannot restyle a notification owned by YouTube Music. [Android's media notification guidance](https://developer.android.com/media/media3/session/background-playback#notification-customization) explains why session metadata is used rather than a notification-only override.

## Transcription recovery

The screenshot's previous message maps to a Gemini server-error response. The previous implementation immediately stopped on that response; model fallback ran only for missing models. It did not establish the exact HTTP code or prove that every such failure was overload.

Generation now retries temporary HTTP 500/502/503/504 responses once per configured model, with exponential backoff and jitter, and tries the alternate model when the first keeps failing. Short Retry-After values are honoured; waits longer than 30 seconds fail visibly rather than starting a long hidden sleep. Existing thinking-option negotiation and missing-model fallback remain. Authentication and quota errors are not blindly retried. Automatic retries reuse the uploaded audio and the existing transcript accumulator. Final server errors retain their HTTP code in the UI and the provider error remains in diagnostics.

This is bounded recovery, not a guarantee that Google will accept a live request during an outage. No real Gemini key or recording was available for live verification in this environment.

## Checks

Final result: 48 tests passed with zero failures, errors or skips. The full suite and signed release assembly succeeded together..

- Local HTTP fixtures cover same-model recovery, primary-model failure followed by fallback success, bounded repeated failures, Retry-After handling and immediate failure for key/quota errors.
- The real NotesService is exercised through streamed partial text, four server-error attempts on one audio upload, a visible terminal HTTP 503 error, later retry from saved text, and completed-transcript reuse without another upload.
- Native graphics tests check original cover visibility inside its block and identical surrounding UI despite changing old stored tint values. The real Media3 service and a connected Media3 controller are checked across artwork URIs, embedded images and track-title changes, including listener callbacks. Robolectric stubs platform MediaController metadata, so actual OEM/system-card rendering remains a phone check.
- The existing import, Drive/YouTube scope, navigation, playlist order, browser/Android Auto discovery, gesture, seeker, transcript and timeout regressions remain in the full suite.

## Delivery

Version 0.8.6, version code 60, production ID `com.aaronalmeida.lumen`. No new permissions or database migration. Existing release signing identity retained.

APK SHA-256: `6b9448cfb658689f1c8c71d7011ffc38822083665c5ce382f4aaf421c8722926`.

The tag workflow now uses `overwrite_files: false` so its rebuild preserves a signed APK already uploaded to that release. This fixes the 0.8.5 delivery issue where the tag workflow replaced the initially verified file. See the [release action's documented input](https://github.com/softprops/action-gh-release/blob/v3/action.yml).

Phone checks: install over the current app without clearing data; play music with colourful artwork and inspect both Now Playing and Lumen's system media card; retry the affected sermon on Wi-Fi and confirm completion or a specific retained error. Actual Samsung rendering and live Gemini output remain unverified until those checks pass.
