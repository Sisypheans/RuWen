# ============================================================
#  RuWen App - whisper.cpp environment setup script
# ============================================================
#  What it does:
#  1. Downloads the whisper.cpp source into app/src/main/cpp/whisper/
#  2. Explains how to get models (models are NOT bundled in the APK;
#     they are downloaded on demand at runtime)
#
#  Usage:
#  1. Open PowerShell
#  2. cd to the project root (RuWen/)
#  3. Run: .\scripts\setup_whisper.ps1
#
#  Notes:
#  - Requires network access (only for the first whisper.cpp clone)
#  - Model files are NOT packaged into the APK (the previously bundled
#    medium.en FP16, ~1.5 GB, has been removed)
#  - On first use the app downloads the default small.en (Q8_0) model,
#    ~0.25 GB
#  - Models go to the app-private files/models/ directory and do not
#    inflate the APK size
# ============================================================

$ErrorActionPreference = "Stop"

# Colored output helper
function Write-ColorOutput($ForegroundColor) {
    $fc = $host.UI.RawUI.ForegroundColor
    $host.UI.RawUI.ForegroundColor = $ForegroundColor
    if ($args) {
        Write-Output $args
    }
    $host.UI.RawUI.ForegroundColor = $fc
}

Write-ColorOutput Cyan "=============================================="
Write-ColorOutput Cyan "  RuWen App - whisper.cpp environment setup"
Write-ColorOutput Cyan "=============================================="
Write-Output ""

# Get the project root directory
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir
$CppDir = Join-Path $ProjectRoot "app\src\main\cpp"
$WhisperDir = Join-Path $CppDir "whisper"

Write-Output "Project root: $ProjectRoot"
Write-Output "whisper directory: $WhisperDir"
Write-Output ""

# ============================================================
#  Step 1: Check that git is available
# ============================================================
Write-ColorOutput Yellow "[1/3] Checking git..."

$gitAvailable = Get-Command git -ErrorAction SilentlyContinue
if (-not $gitAvailable) {
    Write-ColorOutput Red "Error: git not found. Please install Git for Windows first."
    Write-Output "Download: https://git-scm.com/download/win"
    exit 1
}

Write-ColorOutput Green "  OK: git is available"
Write-Output ""

# ============================================================
#  Step 2: Download the whisper.cpp source
# ============================================================
Write-ColorOutput Yellow "[2/3] Downloading whisper.cpp source..."

if (Test-Path $WhisperDir) {
    if (Test-Path (Join-Path $WhisperDir "CMakeLists.txt")) {
        Write-ColorOutput Green "  whisper.cpp already present, skipping download"
        Write-Output ""
    } else {
        Write-ColorOutput Yellow "  whisper directory exists but is incomplete, re-downloading..."
        Remove-Item -Recurse -Force $WhisperDir
    }
}

if (-not (Test-Path $WhisperDir)) {
    Write-Output "  Cloning whisper.cpp..."
    Set-Location $CppDir
    git clone --depth 1 --branch v1.5.4 https://github.com/ggerganov/whisper.cpp.git whisper
    Set-Location $ProjectRoot

    if (Test-Path (Join-Path $WhisperDir "CMakeLists.txt")) {
        Write-ColorOutput Green "  whisper.cpp downloaded successfully"
    } else {
        Write-ColorOutput Red "  Failed to download whisper.cpp"
        exit 1
    }
}

Write-Output ""

# ============================================================
#  Step 3: Model download notes (not bundled, fetched on demand)
# ============================================================
Write-ColorOutput Yellow "[3/3] Model download notes..."

Write-Output "  Models are NOT bundled into the APK (the previously bundled"
Write-Output "  medium.en FP16, ~1.5 GB, has been removed)."
Write-Output "  On first use the app downloads the default small.en (Q8_0) model, ~0.25 GB."
Write-Output "  Download location: app-private files/models/ (does not affect APK size)."
Write-Output "  For other tiers (base/medium etc.), switch and download in the app's"
Write-Output "  'Subtitle recognition model' settings."
Write-Output ""

# ============================================================
#  Done
# ============================================================
Write-ColorOutput Green "=============================================="
Write-ColorOutput Green "  Setup complete!"
Write-ColorOutput Green "=============================================="
Write-Output ""
Write-Output "Next steps:"
Write-Output "  1. Open the project in Android Studio"
Write-Output "  2. Wait for the Gradle sync to finish"
Write-Output "  3. Connect a phone or start an emulator"
Write-Output "  4. Press Run to build and install"
Write-Output ""
Write-Output "Notes:"
Write-Output "  - The first build compiles whisper.cpp natively and takes longer"
Write-Output "  - Models are downloaded on demand on first use; no manual placement needed"
Write-Output ""
