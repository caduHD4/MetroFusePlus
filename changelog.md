---v7.0.14
# MetroFuse+ 7.0.14

- Fixed YouTube Music playback for videos rejected by the legacy VR, iOS and Web clients by prioritizing the existing visionOS client with the current visitor session.
- Prevented successful Innertube responses without a directly playable audio URL from being cached as reusable playback responses.
- Preserved normal provider fallback when YouTube returns only unsupported server-driven ABR metadata.

---v7.0.13
# MetroFuse+ 7.0.13

- Added a safe automatic fallback to exact provider candidates when strict metadata matching rejects otherwise playable sources.
- Fixed misleading playback errors that appeared to blame Qobuz when every configured provider had been attempted.
- Fixed YouTube Music `signatureCipher` resolution by routing signed formats through the existing cipher deobfuscator.
- Preserved Innertube failure details instead of discarding them as generic null responses.
- Applied YouTube `n` parameter transformation consistently, including cached player responses.
- Prioritized complete cached audio before online provider resolution and removed the unnecessary network delay for cached playback.

---v7.0.11
# MetroFuse+ 7.0.11

- Fixed offline playback so fully cached songs play without resolving online providers.
- Prevented network, expired URL and provider errors from deleting valid cached audio.
- Changed the Cached playlist to show only complete, reproducible audio resources.
- Preserved manual provider overrides while safely discarding an incorrect cached match.

---v7.0.10
# MetroFuse+ 7.0.10

- Fixed release compilation for the persistent in-app update banner.
- Kept the fixed update banner with inline changelog, internal installation and swipe-up dismissal.

---v7.0.9
# MetroFuse+ 7.0.9

- Replaced the automatic update modal with a persistent in-app update banner at the top of the app.
- Added inline release notes, in-app update installation controls and swipe-up dismissal for the update banner.

---v7.0.8
# MetroFuse+ 7.0.8

- Added experimental synchronized lyrics to Android Auto playback metadata.
- Added adaptive segmentation for long lyric lines so the complete text can be shown without delaying the next synchronized line.
- Added Unicode-safe word and punctuation boundaries, visual-width balancing, proportional timing and seek-aware segment updates.

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
