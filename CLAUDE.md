# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Screen Monitor Balance Sync** - Android app that runs in background, monitors screen for trading account balances ("qt real usd" or "qt demo usd"), and automatically writes the balance to Google Sheets at the row matching today's date.

**Key Technologies:**
- Kotlin + Jetpack (ViewBinding, WorkManager, Coroutines)
- Hilt for Dependency Injection
- ML Kit Text Recognition (on-device OCR)
- Google Sheets API v4
- MediaProjection API for screen capture
- Material 3 Components

## Build & Development Commands

### Local Build (requires x86_64 environment - use GitHub Actions on ARM64)
```bash
# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew testDebugUnitTest

# Run lint
./gradlew lintDebug

# Clean build
./gradlew clean
```

### GitHub Actions (Recommended - works on any architecture)
```bash
# Trigger build workflow
gh workflow run build.yml

# Trigger Android CI (includes lint)
gh workflow run android.yml

# Watch build progress
gh run watch <run-id>

# List recent runs
gh run list --workflow=build.yml --limit=5

# Download APK artifact
gh run download <run-id> -n app-debug
```

### Architecture-Specific Notes
- **Local builds fail on ARM64 (Raspberry Pi)** due to AAPT2 x86_64 binary incompatibility
- Always use `gh workflow run` for building and verification
- GitHub Actions runs on x86_64 ubuntu-24.04 runners

## Code Architecture

### Core Components

```
app/src/main/java/com/pocet/screenmonitor/
├── PocetApplication.kt          # @HiltAndroidApp entry point
├── MainActivity.kt              # UI, permissions, Google Sign-In
├── di/
│   ├── AppModule.kt             # Hilt DI module (singletons)
│   ├── AuthModule.kt            # Binds SheetsAuthManager
│   └── ServiceEntryPoint.kt     # EntryPoint for Service access
├── model/
│   └── BalanceResult.kt         # Data class (accountType, amount, confidence)
├── service/
│   ├── ScreenCaptureService.kt  # Foreground service (core capture loop)
│   ├── OcrProcessor.kt          # ML Kit OCR pipeline
│   ├── TextParser.kt            # Regex-based balance extraction
│   ├── SheetsRepository.kt      # Google Sheets API with retry logic
│   ├── SheetsAuthManager.kt     # OAuth 2.0 authentication interface
│   ├── SheetsAuthManagerImpl.kt # Google Sign-In implementation
│   └── CaptureScheduler.kt      # WorkManager + AlarmManager scheduling
├── receiver/
│   └── BootReceiver.kt          # BOOT_COMPLETED rescheduling
└── util/
    ├── BatteryOptimizationUtils.kt
    ├── DateUtils.kt
    ├── EncryptedPrefsUtil.kt
    └── PermissionUtils.kt
```

### Data Flow

1. **MainActivity** → User grants MediaProjection → `ScreenCaptureService` starts
2. **ScreenCaptureService** → `VirtualDisplay` + `ImageReader` → captures frames every 10s
3. **OcrProcessor** → Converts Image → Bitmap → ML Kit TextRecognition
4. **TextParser** → Finds "qt real usd"/"qt demo usd" keywords + nearby amounts
5. **SheetsRepository** → Reads B6:B35 for dates → Writes to E{row} with retry/backoff

### Hilt Dependency Injection

- **SingletonComponent**: All services are `@Singleton`
- **AppModule**: Provides SheetsRepository, OcrProcessor, TextParser, CaptureScheduler, etc.
- **AuthModule**: Binds `SheetsAuthManagerImpl` → `SheetsAuthManager`
- **ServiceEntryPoint**: Allows `ScreenCaptureService` (non-@AndroidEntryPoint) to access `SheetsRepository`

### Key Implementation Details

#### ScreenCaptureService (Foreground Service)
- `android:foregroundServiceType="mediaProjection"` (Android 14+)
- Captures at 10-second intervals (`CAPTURE_INTERVAL_MS = 10000L`)
- Minimum 1-minute between sheet writes (`MIN_WRITE_INTERVAL_MS = 60000L`)
- Uses `SupervisorJob` + coroutines for capture loop
- Handles MediaProjection callback for system stop

#### OCR Pipeline
- `OcrProcessor` lazy-initializes `TextRecognizer` with error handling
- Converts YUV_420_888/NV21 → direct `InputImage.fromMediaImage()`
- Converts RGBA_8888 → Bitmap → downsample to max 1920px
- Runs on `Dispatchers.IO`

#### TextParser Strategy
1. **Primary**: Find keyword → look at 4 lines BELOW for amount
2. **Secondary**: Same/adjacent line near keyword
3. **Fallback**: Any amount in full text (low confidence)
- Supports: `$1,234.56`, `1,234.56`, `1234.56`, `1.234,56` (EU)

#### Google Sheets Integration
- Spreadsheet ID: `11I8BjFYYFDfXqTmu1u11lhJH-VIVdVS2tT8b5JqJmWQ`
- Date range: `B6:B35` (column B, rows 6-35)
- Amount column: `E`
- Date formats: `MM/dd/yyyy`, `yyyy-MM-dd`, `dd/MM/yyyy`, `MM-dd-yyyy`
- Exponential backoff retry (max 3, base 1s, max 30s + jitter)
- Auth errors (401/403) don't retry - need re-auth

#### Battery Optimization
- Requests `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` via Settings intent (NOT runtime permission)
- WorkManager constraints: network connected, battery not low
- Exact alarms (Android 14+) for precise scheduling
- Doze mode awareness

#### Permission Handling
- **Manifest only**: INTERNET, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PROJECTION, MEDIA_PROJECTION, RECEIVE_BOOT_COMPLETED
- **Settings intent**: REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, SCHEDULE_EXACT_ALARM
- **Runtime dialog**: POST_NOTIFICATIONS (Android 13+)
- **System dialog**: MEDIA_PROJECTION (screen capture)

## Common Development Tasks

### Adding a New Service
1. Create service class
2. Add to `AppModule.kt` if needs DI
3. If non-Activity (Service/Receiver), add method to `ServiceEntryPoint`
4. Register in `AndroidManifest.xml`

### Modifying OCR Detection
- Edit `TextParser.kt` keyword lists (`qtRealPatterns`, `qtDemoPatterns`)
- Adjust `amountPatterns` regex for new formats
- Modify confidence thresholds in `calculateConfidence()`

### Changing Sheet Target
- Update `SPREADSHEET_ID` in `SheetsRepository.kt`
- Modify `DATE_RANGE` and `AMOUNT_COLUMN` constants
- Adjust `DateUtils` formatters if date format changes

### Debugging Screen Capture
- Check logcat for `Timber` tags: `ScreenCaptureService`, `OcrProcessor`, `TextParser`
- Verify MediaProjection permission granted
- Check foreground service notification visible
- Monitor `CaptureWorker` in WorkManager

## Testing

```bash
# Unit tests (run on GitHub Actions)
./gradlew testDebugUnitTest

# Instrumented tests (require device/emulator)
./gradlew connectedAndroidTest

# Single test class
./gradlew testDebugUnitTest --tests "com.pocet.screenmonitor.service.TextParserTest"
```

## Configuration Files

| File | Purpose |
|------|---------|
| `build.gradle.kts` (root) | Plugin versions (AGP 8.7.3, Kotlin 2.0.21, Hilt 2.51.1) |
| `app/build.gradle.kts` | Dependencies, compileSdk 35, minSdk 26, Java 17 |
| `settings.gradle.kts` | Plugin management, root project name |
| `gradle.properties` | JVM args, parallel, configuration cache |
| `.github/workflows/build.yml` | Build + release workflow |
| `.github/workflows/android.yml` | CI + lint workflow |

## Important Notes for Future Development

1. **Never build locally on ARM64** - Use `gh workflow run build.yml`
2. **Hilt EntryPoint pattern** - Required for any non-@AndroidEntryPoint class needing DI
3. **EncryptedPrefsUtil fallback** - Handles keystore failures gracefully
4. **PermissionUtils** - `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` uses Settings intent, NOT `requestPermissions()`
5. **OcrProcessor lazy init** - TextRecognizer may fail on some devices; handled gracefully
6. **CaptureWorker** - Cannot start capture without MediaProjection user consent
7. **SheetsRepository** - Uses `USER_ENTERED` value input option for proper number formatting
8. **Date normalization** - Multiple formats supported for sheet date comparison

## Release Process

1. Push to `master` branch or create tag `v*`
2. GitHub Actions builds APK automatically
3. Release created/updated at `https://github.com/Lumus-0x/PocetOption_fix/releases/tag/master`
4. Download `app-debug.apk` from release assets

## Troubleshooting Common Issues

| Issue | Cause | Fix |
|-------|-------|-----|
| Build fails locally | ARM64 AAPT2 incompatibility | Use `gh workflow run build.yml` |
| App crashes on startup | Hilt EntryPoint missing | Add to `ServiceEntryPoint` |
| OCR not working | ML Kit init failed | Check logs, verify `google-services.json` not needed for ML Kit |
| Sheets write fails | Auth expired / sheet format | Re-sign in, verify column B dates |
| Service killed | Battery optimization | Grant exemption, exact alarm permission |