# Lumen 0.10.0 verification

## Checked on Windows with the Android SDK
- All unit tests pass and the release build assembles (see the release entry in DECISIONS.md for the count).
- YoutubeMirrorTest (local fake of the YouTube Data API): a first send makes a private playlist with Lumen's description and adds the songs in order; repeats are sent once; an update removes, moves and adds so YouTube matches Lumen exactly; a playlist deleted on YouTube is made again; a song YouTube no longer has is skipped; running out of the daily allowance reports how far it got; a missing grant asks to sign in again; the change plan uses one move for a song brought to the top.
- CarFoldersTest: natural order (Part 2 before Part 10, case ignored); the Library tab lists Recent, Folders and Drive; a folder lists Play all, Shuffle and its items in natural order; Play all expands to the folder in that order. PlaybackBrowserTest now expects the Library tab where Recent was.
- QueueAndMirrorUiTest: queue rules (Play next position, moves keep the current item with its row); the item sheet offers Play next, Add to queue and Rename for YouTube Music songs; the playlist page offers Send to YouTube Music, says what stays in Lumen, and shows Update and Open after a send.
- VideoRulesTest: only wide videos turn the screen; picture in picture keeps the shape within Android's limits; subtitle tracks are named by label or language.

## Phone checks for Aaron
- Send to YouTube Music on a playlist with YouTube Music songs: allow the Google sheet, then find the playlist in YouTube Music (phone, then the car). Reorder in Lumen, tap Update, and check the order follows.
- Play next and Add to queue from a long press; drag and remove in the Queue sheet.
- Android Auto: Library tab, Folders, a folder's Play all and Shuffle, Drive.
- A video: tap for full screen, double-tap both sides, pinch, Subtitles on an MKV or MP4 with subtitles, picture in picture by button and by going home, then close the small window.
- Settings, Restore on a phone with an empty library asks once more.
