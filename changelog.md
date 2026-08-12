---v7.0.7
# MetroFuse+ 7.0.7

- Added in-app APK downloads for GitHub updates instead of redirecting to the browser.
- Added live download progress with transferred size, speed, elapsed time and estimated remaining time.
- Added secure APK handoff through FileProvider, unknown-source permission handling, retry and cancellation.
- Changed update notifications to open MetroFuse+ directly instead of opening the APK URL.

---v7.0.6
# MetroFuse+ 7.0.6

- Added configurable preloading for the next 0–10 queue tracks to reduce the delay between songs.
- Reused the playback resolver and cache with adaptive preload sizes, queue-aware shuffle/repeat navigation, limited concurrency and cancellation of obsolete work.
- Added safe handling for cache hits and partial cache entries while leaving unsupported adaptive, DRM and special-provider streams to normal playback.

---v7.0.5
# MetroFuse+ 7.0.5

- Fixed the Home pull-to-refresh job cancelling itself and leaving the loading indicator spinning indefinitely.
- Enabled fast audio-source search, Deezer resolver fallback, accurate SoundCloud health rows, cached-audio priority, YouTube Music history sync and progressive history tracking by default for users without an existing preference.
- Added an automatic in-app update dialog at startup with the matching APK download and a link to the release changelog.
- Fixed version tracking so the in-app changelog is marked as seen only after it is dismissed.
- Updated the built-in updater to use the canonical `caduHD4/MetroFusePlus` repository.

---v7.0.4
# MetroFuse+ 7.0.4

- Rebranded the application and launcher artwork as MetroFuse+.
- Added optional concurrent source matching with ordered results.
- Added optional provider attempt timeouts and complete fallback before prompting to skip.
- Added optional Deezer-first playback and Deezer resolver fallback.
- Added optional detailed playback diagnostics and corrected SoundCloud health rows.
- Added optional cached-audio priority after quality changes.
- Added authenticated YouTube Music history synchronization after a real listen, independent of the selected audio source.

All MetroFuse+ playback reliability features remain disabled by default under **Settings → Experimental**.
