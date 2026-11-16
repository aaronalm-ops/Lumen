# Lumen 0.9.0 verification

## Done in this session (no Android SDK available here)
- Gemini 3.5 Transcribe request and reply shapes taken from Google's transcription guide (Interactions API, `models/gemini-3.5-transcribe`, verbatim mode with word timestamps, 30-minute limit with timestamps).
- Unit tests added: TranscribePartsTest (offsets, word grouping, spreading, Interactions parsing, part plan, ADTS header bytes checked by hand), ClaudeClientTest (schema conversion, 529 retry, streamed tool JSON, refused key), PlaybackBrowserTest (new tabs, Play all and Shuffle rows, search). Existing Gemini recovery and journey tests updated for the new retry policy and three models.
- Full compile, all unit tests and signing run in the GitHub workflow on the v0.9.0 tag.

## Checked on Windows with the Android SDK (16 Nov 2026)
- `testDebugUnitTest`: 61 tests in 18 classes, 0 failures. `assembleRelease` succeeded.
- Journeys traced in code: notes with and without a Claude key, retry after a failed part (the checkpoint keeps how far the parts got), MP3 from Drive (downloaded, cut into parts), M4A phone recording (AAC-LC cut with ADTS headers; HE-AAC falls back), WAV (whole-file path), the car tree, search, transcript hit, voice search, Speed, Mark and Sleep, notification buttons, and the Claude key in Settings.
- Not testable here: live Google and Anthropic replies, the car display, and Samsung's notification card.

## Phone checks for Aaron
- A 35-minute Drive sermon and a 90-minute sermon: notification shows parts, transcript fills, notes finish (with and without a Claude key).
- Android Auto: tabs, tiles, markers, Play all, Shuffle, search by a word from a sermon, voice "play [title] on Lumen", Speed, Mark and Sleep buttons.
