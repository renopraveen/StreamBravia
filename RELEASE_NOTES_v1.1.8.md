## StreamBravia-TV v1.1.8 (Build 19)

### 🚀 Core Performance & Swarm Engine Optimizations
- **Tracker & DHT Announcement Hot-Path Fix**: Removed forced tracker and DHT re-announces from routine ExoPlayer HTTP Range requests and metadata configuration loops. Added tracker endpoint deduplication (`mAddedTrackerUrls`) to prevent redundant announcements on the Wi-Fi network.
- **RFC 7233 HTTP Range Parser Compliance**: Added robust support for suffix ranges (`bytes=-N`), standard ranges (`bytes=start-end`, `bytes=start-`), and full file requests (`200 OK`). Returns `416 Range Not Satisfiable` with `Content-Range: bytes */totalLength` for out-of-bounds requests.
- **Dedicated HTTP HEAD Handling**: Fast-path for `Method.HEAD` returns container metadata and range capabilities immediately without disk I/O.
- **Piece Index Safety & Verification**: Replaced index clamping with strict bounds checking. `waitForPiece()` and `isPieceDownloaded()` immediately return `false` on missing handles or out-of-range piece indices.
- **Stream Generation Isolation**: Added an atomic generation token (`mGeneration`) incremented on torrent stop/replacement. `TorrentRangeInputStream` aborts with `IOException` if stream generation changes, preventing cross-stream piece leaks.
- **Hardened Range Input Stream**: Added zero-length read guard (`len == 0 -> return 0`), array bounds validation, cancellation flag (`mClosed`), and bounded piece wait timeout (40s) aligned with Media3 network limits.
- **Hardware Decoder vs Display Capability Separation**: `DeviceCapabilities` now strictly separates display physical resolutions (`mDisplayWidth`/`mDisplayHeight`) from hardware decoder limits (`mMaxDecodeWidth`/`mMaxDecodeHeight`), preventing 1080p devices on 4K TVs from misreporting 4K decoder support.
- **First-Paint & Resource Optimization**: Removed external Google Fonts blocking network requests on boot (relying on native system `Roboto`). Removed synchronous `hls.min.js` from boot; added `ensureHlsLoaded()` to lazy-load HLS MSE on demand only when `.m3u8` streams are played.
- **De-jittered Seeking**: Eliminated linear time-to-byte seek estimates in `NativePlayerActivity`; native Media3 byte-range requests directly drive stream piece prioritization.
- **Non-Blocking UI Thread**: Libtorrent session startup and disk cache sweep run asynchronously in background workers; HUD buffering status queries are executed via dedicated `sStatsExecutor`.
- **Security Hardening**: Set `android:exported="false"` on `NativePlayerActivity`.

### 📦 Release Assets
- `StreamBravia-TV.apk` (12.4 MB) — Fully signed, zipaligned, and verified for Android TV / Fire TV (API 26+).
