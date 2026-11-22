# Lumen 0.10.1 verification

## Checked on Windows with the Android SDK
- All 100 unit tests pass and the release build assembles.
- TapSkipTest: each tap adds a step; skips stay between the start and one second before the end; a running loop keeps skips inside it; totals read "+30 s" or "-1 min 10 s"; the half tapped picks the direction; a double-tap and two more taps on the right show "+30 s, to 01:30" and make exactly one seek, 30 s forward, after the tapping stops, with no single-tap action; one tap alone runs the single-tap action and never seeks.
- Journeys traced in code: a song's cover, a sermon's title, a video in Now Playing (one tap still opens full screen), the full-screen player (one tap still toggles the controls), the tabletop layout, casting (the seek goes to the receiver), a running loop, Kids lock (its own screen, no tap to skip), YouTube Music remote mode (its own screen, no tap to skip), TalkBack actions, the Settings step and the hint retiring after the first use.

## Phone checks for Aaron
- Double-tap the right of a cover, then keep tapping: the total grows by 10 s each tap and the seeker shows where it lands; it jumps once when you stop. The same on the left, on a sermon's title, and on a video.
- One tap on a video still opens full screen; in full screen one tap still shows the controls.
- Settings, Skip intervals, change "Each tap on the cover" to 30 and check a tap now moves 30 s.
