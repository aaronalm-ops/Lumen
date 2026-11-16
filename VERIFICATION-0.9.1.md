# Lumen 0.9.1 verification

## Checked on Windows with the Android SDK (16 Nov 2026)
- `testDebugUnitTest`: all unit tests pass, including the new ones below. `assembleRelease` succeeded.
- RepeatLoopTest: the Off, All, One cycle; stored mode parsing; start before end; a minimum loop length; the end kept before the end of the recording; clearing on another item; saves held only while a loop runs; leaving the loop by seeking past its end; the loop carried in session extras.
- RepeatCommandTest (real PlaybackService session and a MediaController): the Repeat command cycles Off, All, One, Off; the song's Repeat button shows the current mode's icon; the mode is saved for the next start; Set start, Set end, the seek back to the start, clearing on the next item; an end before the start is refused.
- RepeatUiTest: Repeat opens the list in plain words; Set end and Clear loop appear once a start is set; the line under the transport and the seeker's spoken description show the loop and the mode.
- Journeys traced in code: a song in each mode, a playlist on Repeat queue, a sermon with a loop then resume (positions held during the loop), the car button, the notification and lock-screen button for songs.

## Not testable on a computer
- The loop's sound in real playback (the ExoPlayer message firing at B while audio plays), how Android Auto, Samsung's notification and the lock screen draw the Repeat button, and how the band looks on the phone.
