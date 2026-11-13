# Lumen 0.9.0 verification

## Done in this session (no Android SDK available here)
- Gemini 3.5 Transcribe request and reply shapes taken from Google's transcription guide (Interactions API, `models/gemini-3.5-transcribe`, verbatim mode with word timestamps, 30-minute limit with timestamps).
- Unit tests added: TranscribePartsTest (offsets, word grouping, spreading, Interactions parsing, part plan, ADTS header bytes checked by hand), ClaudeClientTest (schema conversion, 529 retry, streamed tool JSON, refused key), PlaybackBrowserTest (new tabs, Play all and Shuffle rows, search). Existing Gemini recovery and journey tests updated for the new retry policy and three models.
- Full compile, all unit tests and signing run in the GitHub workflow on the v0.9.0 tag.

## Phone checks for Aaron
- A 35-minute Drive sermon and a 90-minute sermon: notification shows parts, transcript fills, notes finish (with and without a Claude key).
- Android Auto: tabs, tiles, markers, Play all, Shuffle, search by a word from a sermon, voice "play [title] on Lumen", Speed, Mark and Sleep buttons.
