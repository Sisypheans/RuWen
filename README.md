<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.zh-CN.md">简体中文</a>
</p>

<h1 align="center">RuWen (如闻)</h1>

<p align="center">
  An offline-subtitle audio player for Android, built around <b>podcast subscriptions</b> and <b>on-device subtitle generation</b>, designed for language learning.
</p>

---

RuWen is a Material Design Android audio player that combines **podcast subscription** with
**fully offline subtitle generation**. It bundles [whisper.cpp](https://github.com/ggerganov/whisper.cpp)
and runs speech-to-text **entirely on the device** — no network, no uploads of your local audio.

- 🌐 **12 recognition languages** (the languages whisper.cpp recommends for best accuracy), with a
  vertical single-choice picker.
- 📥 **Per-language models** — English uses the dedicated `.en` models; the other 11 languages share a
  single multilingual model set (no per-language model bloat).
- 🎧 **Podcasts** — search (Apple Podcasts / Podcast Index), subscribe via RSS, OPML import, episode
  download, and persistent per-podcast sorting.
- 📝 **Synced subtitles** — highlights the current line while playing; swipe left/right to jump
  segments.

## Screenshots

| Playlists | Model settings | Podcasts | Search |
| --- | --- | --- | --- |
| <img src="docs/screenshots/0.jpg" width="240"/> | <img src="docs/screenshots/1.jpg" width="240"/> | <img src="docs/screenshots/2.jpg" width="240"/> | <img src="docs/screenshots/3.jpg" width="240"/> |

## Features

### Playback

- 📚 **Playlists** — create, edit, and delete playlists; import MP3 files from device storage in bulk.
- ▶️ **Full player** — play/pause, previous/next, seek bar, shuffle.
- 🔁 **3-state repeat** — off → list repeat → single repeat. Defaults to **off**, and **remembers** your
  last choice across app restarts.
- 🎧 **Persistent mini player** — sits at the bottom of the home and playlist screens; tap it to open the
  player. Reopening the app restores the last-played track.
- 😴 **Sleep timer** — presets (15/30/45/60/90 min), custom duration, or "stop after current episode";
  remaining time updates live.
- 🔔 **Media notification / lockscreen** — AndroidX Media3 `MediaSession` + `MediaStyleNotificationHelper`;
  controllable from the notification shade, lockscreen, Bluetooth, and voice assistants. Playback
  continues after screen-off (holds a `WAKE_MODE_LOCAL` wake lock).

### Podcasts

- 🔍 **Online search** — two sources: **Apple Podcasts** (iTunes Search API, no auth) and
  **Podcast Index** (requires API credentials, see *Getting Started*).
- ➕ **Subscribe / unsubscribe** — one-tap RSS subscription; unsubscribing cleans up locally downloaded
  episodes, subtitles, and cover caches.
- 📜 **Episode list** — title, duration, publish date, and cover on the podcast detail page.
  **Sort order persists** (by title or date, asc/desc) per podcast.
- ⬇️ **Episode download** — downloaded with OkHttp; state machine is
  `not downloaded → downloading → downloaded / failed`. Downloaded episodes can be added to a playlist
  and played offline.
- 📥 **OPML import** — bulk-import subscriptions from OPML (handles nested groups and
  UTF-8/GBK/ISO-8859-1 encodings), auto-deduplicated.
- 🖼️ **Cover loading** — podcast and episode covers use Coil 3 with a disk cache (no re-download on cold
  start); a "backfill covers" tool fixes early entries that were missing covers.

### Subtitles

- 🌏 **12 languages** — a single button opens a **vertical single-choice list** of the languages whisper.cpp
  recommends for the best accuracy. The app ships **no model**; you download one per language on first use.
- 📝 **AI subtitle generation** — on-device recognition, outputs SRT. The recognition language is **locked
  to your selection** (e.g. English → `en`, Chinese → `zh`); no auto-detect.
- 🗂️ **Serial generation queue** — multiple audio files are processed **strictly in enqueue order**, never
  concurrently (to avoid loading several models and thrashing the CPU).
- 💬 **Synced subtitle display** — the current sentence highlights in time with playback.
- 👆 **Swipe the subtitle area** — swipe left to jump to the previous segment, right to the next (relative
  to the current playback position); vertical scrolling is unaffected.
- 🔗 **Shared subtitles** — when the same audio is imported into multiple playlists (several `AudioItem`s
  with identical name/duration/file size), only one shared subtitle `shared_<md5>.srt` is generated and
  reused; legacy `<audioId>.srt` files are left intact.

### Misc

- ⚙️ **Quick settings entry** — a "Subtitle model" shortcut lives in the toolbar.
- 🎨 **Purple Material Design** — an elegant purple-themed UI.

## Tech Stack

| Item | Details |
| ------- | ------------------------------------------------------------------------------ |
| Language | Kotlin (JVM target 17) |
| Versions | minSdk 29 (Android 10) / targetSdk 34 / **compileSdk 36** |
| Arch | MVVM + Room (KSP codegen, schema exported for migrations) |
| UI | Material Design 3 (material 1.11.0) + ViewBinding (not Compose) |
| Player | AndroidX Media3 **1.10.1** (ExoPlayer + UI + Session) + foreground service + MediaSession media notification |
| Background | WorkManager 2.9.0 (single worker, serial consumer queue) |
| Speech | whisper.cpp + NDK / CMake, **arm64-v8a only** |
| Decode | MediaCodec hardware decode → resample to 16 kHz mono |
| Podcast / net | rssparser 6.1.2 (RSS), OkHttp 5.4.0 (search & download), DocumentFile 1.0.1 (storage access) |
| Images | Coil 3.5.0 + coil-network-okhttp 3.5.0 (covers, with disk cache) |
| Storage | Room 2.8.5 (playlists / podcasts / episodes / subtitle state) |

> **Native-library alignment:** `abiFilters` keeps only `arm64-v8a`; CMake enables
> `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` for Android 15+ 16 KB pages, links STL statically with
> `ANDROID_STL=c++_static`, and sets `android:extractNativeLibs="true"` to silence the "APK incompatible
> with 16 KB devices" warning.

## Getting Started

### 1. Set up the native dependency

```powershell
cd RuWen
.\scripts\setup_whisper.ps1
```

This downloads the whisper.cpp source into `app/src/main/cpp/whisper/`. **Models are not downloaded by this step.**

### 2. Build with Android Studio

> **Prerequisites:** in Android Studio's SDK Manager → SDK Tools, install **NDK (Side by side)** and **CMake 3.22.1** — this project contains native code and cannot build without them. See [BUILD_GUIDE.md](BUILD_GUIDE.md) for details.

Open the project root in Android Studio and choose `Build → Make Project`, or run it on a device.

> This repository does **not** ship `gradlew`. Opening it in Android Studio is the simplest path (the IDE
> manages Gradle for you). For command-line builds you must provide `JAVA_HOME` and `ANDROID_HOME` yourself;
> see [BUILD_GUIDE.md](BUILD_GUIDE.md) for details.

### 3. First run: download a subtitle model

Inside the app, tap the subtitle icon in the toolbar → choose a **language** → tap **download** on a size tier.
Models are downloaded to the app-private `files/models/` directory and do not inflate the APK size.

### 4. (Optional) Podcast Index credentials

Podcast search defaults to **Apple Podcasts** (no auth, works out of the box). To enable the **Podcast Index**
source, sign up at <https://api.podcastindex.org/signup> for an API key, then copy the repo-root template
`local.properties.example` to `local.properties` and fill in:

```properties
podcastIndex.apiKey=YOUR_KEY
podcastIndex.apiSecret=YOUR_SECRET
```

`local.properties` is already in `.gitignore`, so credentials never enter version control. If the keys are
missing, Gradle prints a warning at build time and the Podcast Index source is simply unavailable at runtime —
Apple search and OPML import are unaffected.

## Models

6 models = 3 sizes × 2 classes, all Q8_0 quantization:

| Scope | Model file | Size | Notes |
| --- | --- | --- | --- |
| English (`.en`) | `ggml-base.en-q8_0.bin` | 78 MB | English-only |
| English (`.en`) | `ggml-small.en-q8_0.bin` | 252 MB | English-only (default for English) |
| English (`.en`) | `ggml-medium.en-q8_0.bin` | 785 MB | English-only (large) |
| Multilingual | `ggml-base-q8_0.bin` | 78 MB | 99-language model, shared by 11 non-English languages |
| Multilingual | `ggml-small-q8_0.bin` | 252 MB | shared (default for non-English) |
| Multilingual | `ggml-medium-q8_0.bin` | 785 MB | shared (large) |

**Language ↔ model are decoupled.** whisper.cpp's convention: a model whose name contains `.en` is an
**English-only** model; without `.en` it is a **multilingual** model (covers 99 languages — we expose the
12 the project recommends). English uses the `.en` files; the other 11 languages (Chinese, German, Spanish,
Russian, Korean, French, Japanese, Portuguese, Polish, Dutch, Italian) all share the multilingual files. The
recognition language is locked to your selection at transcription time, not auto-detected.

Model source: `https://huggingface.co/ggerganov/whisper.cpp`.

## Subtitle Generation Pipeline

```
Audio file
  │  MediaCodec hardware decode → PCM
  ▼
Resample (16 kHz mono float — the format Whisper expects)
  │
  ▼
whisper.cpp on-device inference (JNI → nativeTranscribe; language locked to your selection)
  │
  ▼
SRT subtitle file (shared shared_<md5>.srt per audio; legacy <audioId>.srt kept; written to app-private dir)
  │
  ▼
Player highlights the current line in sync with playback
```

## Generation Queue & Cancellation

- Tapping "generate subtitles" on several audio files enqueues them into a **FIFO queue**
  (`SubtitleQueue`, persisted to SharedPreferences), processed strictly in tap order.
- Only one transcription runs at a time — concurrent model loads would tank performance.
- **Cancel the current item**: interrupts only the in-flight audio; queued items continue.
- **Cancel a not-yet-started item**: removed from the queue; nothing else is affected.

## Performance

Recognition is a heavy on-device task; real speed depends on device, model tier, and audio length. The
metric is **xRT (real-time factor)**:

```
xRT = audio duration / elapsed time     e.g. xRT = 2.0 means "1 s of audio computed in 0.5 s"
```

After generation, filter Logcat for `whisper` to see:

```
Transcribe finished in 12.4 s (audio 28.9 s) => xRT=2.325, threads=4, rc=0
```

Factors that affect speed:

| Factor | Note |
| --- | --- |
| Model tier | base → medium gains accuracy but costs time; size differs ~20× |
| Word timestamps | Toggleable in settings. On gives finer word alignment but is noticeably slower (off for long audio) |
| Threads | Auto-chosen by `WhisperCpuConfig` from the device's big-core count; measured to beat manual settings, so no manual option is exposed |
| Background throttling | For long audio, keep the app foreground + screen on, and disable battery optimization for the app |

> First run tip: check `build features: fp16_va=?` in the log. A `0` means ARM optimization didn't engage
> and speed will suffer.

## Podcasts

The podcast module is a lightweight subscription client — it ships no audio; everything comes from RSS.

**Search & subscribe entry points**

- `ui/search` — search page; switch between Apple Podcasts / Podcast Index; results can be previewed before subscribing.
- `data/remote`:
  - `ApplePodcastsSearcher.kt` — iTunes Search API, no auth.
  - `PodcastIndexSearcher.kt` — Podcast Index; needs API Key/Secret from `local.properties`; requests are signed with SHA-1 + timestamp.
  - `OpmlParser.kt` — parses OPML, flat-scans all `outline`s, keeps entries with `xmlUrl`, auto-deduplicates.
  - `PodcastSearchResult.kt` / `OkHttpExt.kt` — result model and OkHttp extensions.
- `data/repository/PodcastSearchRepository.kt` — search orchestration, **search-only, never subscribes**; subscription is unified through `PodcastRepository.subscribe` so search results, RSS URLs, and OPML all persist through one path.

**Subscription data**

- `PodcastRepository.kt` — `subscribe` (RSS parsed by rssparser, persists `Podcast` + `Episode`), `refresh`
  (incremental new episodes; downloaded paths/states are not overwritten on refresh), per-podcast sort
  persistence (`updateSortOrder`), episode download state machine
  (`markDownloading/Downloaded/Failed/NotDownloaded`), `unsubscribe` (returns the local-file cleanup list).
- `CoverBackfill.kt` — backfills legacy missing covers by downloading and caching from the remote URL.
- `PlaylistRepository.kt` — generic playlists and audio items (incl. shared-subtitle state); podcast
  episodes join playlists as `AudioItem`s too.

**RSS parsing caveat:** rssparser's `RssItem.image` is a "dirty field" (can be overwritten by the `<link>`
web URL). Episode covers should prefer `itunes:image` and filter URLs by "does this look like an image",
or covers will fail to load.

## Project Structure

```
RuWen/
├── app/src/main/
│   ├── java/com/ruwen/audioplayer/
│   │   ├── data/
│   │   │   ├── entity/             # AudioItem / Playlist / Podcast / Episode
│   │   │   │                       #   / SubtitleCue / SubtitleIdentity
│   │   │   ├── dao/                # AudioItemDao / PlaylistDao / PodcastDao
│   │   │   ├── db/                 # Room database & migrations
│   │   │   ├── remote/             # ApplePodcastsSearcher / PodcastIndexSearcher
│   │   │   │                       #   / OpmlParser / PodcastSearchResult / OkHttpExt
│   │   │   ├── repository/         # PlaylistRepository / PodcastRepository
│   │   │   │                       #   / PodcastSearchRepository / CoverBackfill
│   │   │   └── SubtitleProgressStore.kt
│   │   ├── service/
│   │   │   ├── PlaybackService.kt  # playback service (3-state repeat, sleep timer, MediaSession, media notification)
│   │   │   └── PlaybackPrefs.kt    # repeat mode / last-played persistence
│   │   ├── ui/
│   │   │   ├── MainActivity.kt     # home (with model-settings entry)
│   │   │   ├── MiniPlayerController.kt  # shared bottom mini player
│   │   │   ├── WhisperModelSettingsDialog.kt  # language picker + model download/delete
│   │   │   ├── home/               # home (playlists)
│   │   │   ├── playlist/           # playlist detail
│   │   │   ├── player/             # player page (SwipeAwareFrameLayout gestures, LockableScrollView)
│   │   │   ├── podcast/            # podcast list / detail / info / preview
│   │   │   ├── search/             # podcast search page & results
│   │   │   ├── adapter/            # list adapters
│   │   │   ├── viewmodel/          # ViewModel
│   │   │   └── util/               # UI utilities
│   │   ├── whisper/
│   │   │   ├── WhisperModel.kt      # 6 models + 12-language enum + download/delete
│   │   │   ├── WhisperSettings.kt   # language & model-tier persistence
│   │   │   ├── WhisperManager.kt    # decode, resample, infer, SRT output
│   │   │   ├── WhisperNativeLibrary.kt  # JNI native-library wrapper
│   │   │   ├── SubtitleQueue.kt     # FIFO generation queue
│   │   │   ├── SubtitleGenerationWorker.kt  # WorkManager serial consumer
│   │   │   ├── SubtitleGenerationException.kt
│   │   │   ├── WhisperCpuConfig.kt  # thread-count auto strategy
│   │   │   └── AudioResampler.kt
│   │   ├── util/                   # utilities (CoverStore cover cache, Digest, …)
│   │   └── RuWenApplication.kt     # implements Coil SingletonImageLoader.Factory with disk cache
│   ├── cpp/
│   │   ├── CMakeLists.txt
│   │   ├── whisper_jni.cpp          # JNI binding layer
│   │   ├── whisper_stub.cpp         # stub impl (used when whisper.cpp isn't compiled)
│   │   └── whisper/                 # whisper.cpp source (downloaded by the script)
│   └── res/
├── scripts/setup_whisper.ps1        # environment setup script
├── BUILD_GUIDE.md
└── README.md
```

## Whisper Integration Architecture

```
┌─────────────────────────────────────────┐
│           Kotlin layer                   │
│  WhisperManager.kt                       │
│  ├── model resolution (downloaded / bundled / missing)  │
│  ├── audio decode (MediaCodec hardware)  │
│  ├── resample (16 kHz mono)              │
│  ├── language selection (12 langs; English→.en model, others→multilingual) │
│  ├── progress management & callbacks     │
│  └── SRT subtitle output                 │
└───────────────┬─────────────────────────┘
                │ JNI
┌───────────────▼─────────────────────────┐
│           C++ JNI layer                  │
│  whisper_jni.cpp                         │
│  ├── nativeInitFromFile() - load model   │
│  ├── nativeTranscribe()  - run recognition │
│  ├── nativeCancel()      - interrupt     │
│  ├── nativeRelease()     - release       │
│  ├── SRT formatting                      │
│  └── progress callback bridge            │
└───────────────┬─────────────────────────┘
                │
┌───────────────▼─────────────────────────┐
│           whisper.cpp (C/C++)            │
│  ├── GGML tensor library                 │
│  ├── Whisper Encoder / Decoder           │
│  ├── ARM NEON optimizations              │
│  └── multi-threaded inference            │
└─────────────────────────────────────────┘
```

## Known Limitations

- **arm64-v8a only** — whisper.cpp ships full optimizations for arm64; medium-tier inference needs GBs of
  RAM, which a 32-bit process can't hold.
- **arm64 device + Android 10+ required.**
- **Models must be downloaded** — the APK bundles no model. The source is HuggingFace; in restricted
  networks you may need a proxy or retries.
- **Watch your data** — small ≈ 252 MB, medium ≈ 785 MB. Downloading over a metered network (mobile data)
  triggers a confirmation.
- **Heavy on-device load** — transcribing tens of minutes of audio can take a long time; the device will
  heat up and drain battery. Keep it plugged in and foreground.
- **Podcasts need network** — search and episode download require connectivity; Podcast Index also needs
  your own API credentials (see *Getting Started*), while Apple Podcasts works with no auth.
- **Covers need network explicitly enabled** — Coil 3 does not load http(s) images by default; you must
  include `coil-network-okhttp`. Without it, podcast/episode covers won't show (unrelated to connectivity).

## Contributing

Contributions are welcome! This is a personal, indie project, so please open an issue to discuss
non-trivial changes before sending a pull request.

1. Fork and create a feature branch.
2. Keep changes focused; Kotlin style follows the existing code.
3. Make sure `:app:assembleDebug` builds.
4. Open a PR describing the motivation and the change.

## License

- Project code: MIT License
- Whisper models: MIT License (OpenAI)
- whisper.cpp: MIT License (ggerganov)

## Acknowledgements

- [whisper.cpp](https://github.com/ggerganov/whisper.cpp) — an excellent C/C++ Whisper port
- OpenAI Whisper — the powerful speech recognition model
- AndroidX Media3 — the modern Android media stack (ExoPlayer / MediaSession)
- [rssparser](https://github.com/prof18/RSS-Parser) — a lightweight RSS/Atom parser
- [Coil](https://github.com/coil-kt/coil) — the first-class Kotlin image loader
