# Pocket Option Helper

Android 16 app that runs in background, monitors screen for Pocket Option trading account balances ("qt real usd" or "qt demo usd"), and automatically writes the balance to Google Sheets at the row matching today's date.

## Features

- **Background Screen Capture**: Uses MediaProjection API with foreground service for continuous monitoring
- **On-Device OCR**: ML Kit Text Recognition processes screen content locally (no cloud)
- **Smart Balance Detection**: Parses "qt real usd" / "qt demo usd" and extracts nearby currency amounts
- **Google Sheets Integration**: Writes to column E at row matching today's date in column B (rows 6-35)
- **Battery Optimized**: Handles Doze mode, requests battery optimization exemption, uses WorkManager
- **Android 16 Compatible**: Foreground service types, exact alarm permissions, notification permissions

## Architecture

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  MediaProjection │────▶│  VirtualDisplay  │────▶│   ImageReader   │
└─────────────────┘     └──────────────────┘     └────────┬────────┘
                                                          │
                                                          ▼
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  Google Sheets  │◀────│  SheetsRepository │◀────│   TextParser    │
│      API        │     │   (Retry Logic)  │     │  (Regex Match)  │
└─────────────────┘     └──────────────────┘     └────────┬────────┘
                                                          │
                                                          ▼
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  Foreground     │────▶│   OcrProcessor   │────▶│  ML Kit Text    │
│  Service        │     │  (Bitmap Convert)│     │  Recognition    │
└─────────────────┘     └──────────────────┘     └─────────────────┘
```

## Project Structure

```
app_pocet/
├── app/
│   ├── src/main/
│   │   ├── java/com/pocet/screenmonitor/
│   │   │   ├── PocetApplication.kt          # Hilt Application
│   │   │   ├── MainActivity.kt              # Main UI & permission handling
│   │   │   ├── di/
│   │   │   │   ├── AppModule.kt             # Hilt DI module
│   │   │   │   ├── AuthModule.kt            # Auth DI bindings
│   │   │   │   └── ServiceEntryPoint.kt     # EntryPoint for Services
│   │   │   ├── model/
│   │   │   │   └── BalanceResult.kt         # Data model for parsed balance
│   │   │   ├── service/
│   │   │   │   ├── ScreenCaptureService.kt  # Foreground service (core)
│   │   │   │   ├── OcrProcessor.kt          # ML Kit OCR pipeline
│   │   │   │   ├── TextParser.kt            # Balance extraction logic
│   │   │   │   ├── SheetsRepository.kt      # Google Sheets API client
│   │   │   │   ├── SheetsAuthManager.kt     # OAuth 2.0 authentication
│   │   │   │   ├── SheetsAuthManagerImpl.kt # Google Sign-In implementation
│   │   │   │   └── CaptureScheduler.kt      # WorkManager scheduling
│   │   │   ├── receiver/
│   │   │   │   └── BootReceiver.kt          # Boot completed receiver
│   │   │   └── util/
│   │   │       ├── BatteryOptimizationUtils.kt
│   │   │       ├── DateUtils.kt
│   │   │       ├── EncryptedPrefsUtil.kt
│   │   │       └── PermissionUtils.kt
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml
│   │   │   ├── values/strings.xml, colors.xml, themes.xml
│   │   │   ├── drawable/ (icons)
│   │   │   ├── xml/ (backup_rules, data_extraction_rules)
│   │   │   └── mipmap-*/ (launcher icons)
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/gradle-wrapper.properties
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── README.md
```

## Requirements

- Android 8.0+ (API 26) - Min SDK
- Android 16 (API 35) - Target SDK
- Google Play Services (for ML Kit & Sign-In)
- Internet permission for Google Sheets API

## Setup Instructions

### 1. Google Cloud Console Setup

1. Create a project in [Google Cloud Console](https://console.cloud.google.com/)
2. Enable **Google Sheets API**
3. Configure **OAuth Consent Screen** (External user type)
4. Create **OAuth 2.0 Client ID** for Android:
   - Package name: `com.pocet.screenmonitor`
   - SHA-1 certificate fingerprint (debug & release)

### 2. Google Sheet Setup

1. Open the target sheet: `https://docs.google.com/spreadsheets/d/11I8BjFYYFDfXqTmu1u11lhJH-VIVdVS2tT8b5JqJmWQ/edit`
2. Ensure column B (rows 6-35) contains dates in MM/dd/yyyy format
3. Column E will receive the balance amounts
4. Share the sheet with the OAuth client email if using service account

### 3. Build & Install

```bash
# Build debug APK (use GitHub Actions on ARM64)
gh workflow run build.yml

# Install on device
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 4. Runtime Setup

1. Open app → **Sign In to Google** (grants Sheets access)
2. **Request All Permissions**:
   - Battery optimization exemption (critical for background)
   - Exact alarms (Android 14+)
   - Notifications (Android 13+)
3. **Start Capture** → Grant screen recording permission
4. App runs in background, checks screen every 10 seconds

## Google Sheet Format

| Row | Column B (Date) | Column E (Amount) |
|-----|----------------|-------------------|
| 6   | 01/15/2024     | *auto-filled*     |
| 7   | 01/16/2024     | *auto-filled*     |
| ... | ...            | ...               |
| 35  | 02/13/2024     | *auto-filled*     |

Date formats supported: `MM/dd/yyyy`, `yyyy-MM-dd`, `dd/MM/yyyy`, `MM-dd-yyyy`

## Key Implementation Details

### Foreground Service (Android 16)
```xml
<service
    android:name=".service.ScreenCaptureService"
    android:foregroundServiceType="mediaProjection"
    android:exported="false"
    android:stopWithTask="false" />
```

### MediaProjection Lifecycle
- User grants permission via system dialog
- Service creates `VirtualDisplay` → `ImageReader`
- Captures frames at 10-second intervals
- Processes with ML Kit on background thread

### OCR Processing
- Converts YUV_420_888 Image → Bitmap
- Downsamples to max 1920px for performance
- Runs ML Kit Text Recognition
- Parses text blocks for keywords + amounts

### Text Parsing Patterns
```kotlin
// Keywords
"qt real usd", "qt demo usd", "real usd", "demo usd"

// Amount formats
$1,234.56 | 1,234.56 | 1234.56 | 1.234,56 (EU)
```

### Google Sheets Write Logic
1. Read `B6:B35` range for dates
2. Find row where normalized date == today
3. Update `E{row}` with `USER_ENTERED` value input option
4. Exponential backoff retry (max 3 attempts)

### Battery Optimization
- Requests `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- Uses WorkManager with network/battery constraints
- Minimum 1-minute interval between sheet writes
- Pauses during Doze mode

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Service killed in background | Disable battery optimization, grant exact alarm permission |
| OCR not detecting text | Ensure screen brightness, check "qt real usd" is visible |
| Sheets write fails | Verify sign-in, check sheet sharing, check date format in column B |
| MediaProjection denied | User must grant in system dialog, cannot be auto-granted |

## Permissions Summary

| Permission | Purpose | Runtime Request |
|------------|---------|-----------------|
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Background screen capture | Manifest only |
| `MEDIA_PROJECTION` | Screen capture intent | System dialog |
| `INTERNET` | Google Sheets API | Manifest only |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prevent Doze kills | Settings intent |
| `SCHEDULE_EXACT_ALARM` (Android 14+) | Precise scheduling | Settings intent |
| `POST_NOTIFICATIONS` (Android 13+) | Foreground notification | Runtime dialog |

## License

MIT License - Feel free to modify for your trading setup.