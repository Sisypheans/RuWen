# RuWen — Quick Build Guide

## 1. Set up the whisper.cpp environment

### Prerequisites

- Git for Windows (download: https://git-scm.com/download/win)
- Android Studio Hedgehog (2023.1.1) or newer
- Android NDK 25.2.9519653 (install via Android Studio → SDK Manager → SDK Tools)
- CMake 3.22.1 (install via Android Studio → SDK Manager → SDK Tools)

### Steps

**Step 1: Run the setup script**

Open PowerShell and go to the project root:

```powershell
cd path\to\RuWen
.\scripts\setup_whisper.ps1
```

The script automatically:

1. Clones the whisper.cpp v1.5.4 source into `app/src/main/cpp/whisper/`
2. (Models are **not** bundled) The app downloads the default `small.en` Q8_0 model
   on first use at runtime.

**Step 2: Build with Android Studio**

1. Open Android Studio.
2. Choose "Open an existing project".
3. Select the RuWen project root.
4. Wait for the Gradle sync to finish (the first build is slower — whisper.cpp is compiled natively).
5. Connect a phone or start an emulator.
6. Press Run.

## FAQ

### Q: Build fails with "NDK not found"

A: In Android Studio open SDK Manager → SDK Tools, then check and install
"NDK (Side by side)" and "CMake".

### Q: Compile fails because the whisper directory is missing or empty

A: Re-run `scripts\setup_whisper.ps1` and make sure the whisper.cpp source is
fully downloaded (the script skips the download if the directory is already complete).

### Q: Models are huge — do they have to go into the APK?

A: No. The APK bundles **no model at all**. Models are downloaded on demand at
runtime into the app-private `files/models/` directory, so they never inflate the APK size.

### Q: Can I use a different model tier (base / small / medium)?

A: Yes — no code changes needed. In the app, open the subtitle-model settings
(toolbar → subtitle icon), pick a language, and download the tier you want.
Model files are fetched from the official HuggingFace repo
(`ggerganov/whisper.cpp`) at runtime.

## Architecture

```
┌─────────────────────────────────────────┐
│           Kotlin (UI / Logic)           │
│  WhisperManager.kt                      │
│  - audio decode (MediaCodec)            │
│  - SRT subtitle generation              │
│  - model file management                │
└───────────────┬─────────────────────────┘
                │ JNI
┌───────────────▼─────────────────────────┐
│           C++ (whisper_jni.cpp)         │
│  - JNI binding layer                    │
│  - SRT formatting                       │
│  - progress callbacks                   │
└───────────────┬─────────────────────────┘
                │
┌───────────────▼─────────────────────────┐
│           whisper.cpp (C/C++)           │
│  - GGML tensor library                  │
│  - Whisper model inference              │
│  - ARM NEON optimizations               │
└─────────────────────────────────────────┘
```

## Performance reference (Snapdragon 8s Gen 4)

All shipped models are Q8_0 quantized (the tiny tier has been removed):

| Model | Size | RTF | 1 h of audio takes |
| ----- | ------ | ------ | --------- |
| base (Q8_0) | 78 MB | ~0.15x | ~9 min |
| small (Q8_0) | 252 MB | ~0.3x | ~18 min |
| medium (Q8_0) | 785 MB | ~0.8x | ~48 min |

> RTF (Real-Time Factor) = processing time / audio duration. Lower is faster:
> 0.5x means 1 hour of audio takes 30 minutes.
> Note: the main README uses the inverse metric **xRT** = audio duration / elapsed
> time (higher is faster), so xRT = 1 / RTF.
