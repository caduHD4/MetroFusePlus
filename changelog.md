---v7.0.19
# MetroFuse+ 7.0.19

- Fixed music recognition audio resampling on Media3 1.10.0 by replacing the incompatible SonicAudioProcessor path with the current Metrolist PCM 16-bit linear resampler.
- Added a microphone button to the search bar for direct access to music recognition.

---v7.0.18
# MetroFuse+ 7.0.18

- Fixed ANDROID_MUSIC YouTube Music history tracking by removing the browser-only account sync identifier from its native player context.
- Preserved the authenticated cookie and SAPISID request path used by ANDROID_MUSIC while keeping WEB_REMIX unchanged.

---v7.0.17
# MetroFuse+ 7.0.17

- Fixed the ANDROID_MUSIC history tracking client context by sending its required Android SDK and OS fields.
- Updated the isolated ANDROID_MUSIC tracking identity to a verified client version and matching User-Agent.

---v7.0.16
# MetroFuse+ 7.0.16

- Added experimental YouTube Music history tracking client controls for WEB_REMIX, ANDROID_MUSIC and WEB.
- Automatic tracking now tries WEB_REMIX, then ANDROID_MUSIC, then WEB when no client is selected.
- Keeps manually selected clients in the chosen product priority, including standard YouTube history as an optional fallback.

---v7.0.15
# MetroFuse+ 7.0.15

- Fixed progressive YouTube Music history sync stopping when WEB_REMIX cannot create an authenticated tracking session.
- Added authenticated client fallback for progressive history sessions while preserving the same client for subsequent playback heartbeats.

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
