# Pocket Option Helper — План исправления багов и вылетов при запуске

## Обзор
Данный документ описывает критические и высокоприоритетные проблемы, выявленные при анализе кодовой базы, которые могут вызывать краш приложения при запуске или в процессе работы.

---

## 🔴 КРИТИЧЕСКИЕ ПРОБЛЕМЫ (Блокеры запуска) — ✅ **ИСПРАВЛЕНО**

### 1. **AppModule.kt: Блокирующий вызов `runBlocking` в Hilt модуле** — ✅ **ИСПРАВЛЕНО**
**Исправление:** Удален `provideSheetsService()` из модуля. `SheetsRepository` вызывает `getSheetsService()` асинхронно внутри корутин.

### 2. **SheetsAuthManagerImpl.kt: Принудительный unwrap `account!!`** — ✅ **ИСПРАВЛЕНО**
**Исправление:** Добавлен метод `getAccountOrNull()` возвращающий `Result<GoogleSignInAccount>`. Убран `signInInteractively()` с throw.

### 3. **SheetsAuthManagerImpl.kt: `signInInteractively()` выбрасывает исключение** — ✅ **ИСПРАВЛЕНО**
**Исправление:** Метод удален. Новый `getAccountOrNull()` возвращает `Result.failure` при необходимости интерактивного входа.

### 4. **PermissionUtils.kt: Неверный запрос `SCHEDULE_EXACT_ALARM`** — ✅ **ИСПРАВЛЕНО**
**Исправление:** Убраны `SCHEDULE_EXACT_ALARM` и `USE_EXACT_ALARM` из runtime permissions. Используются Settings intents через `BatteryOptimizationUtils`.

---

## 🟠 ВЫСОКИЙ ПРИОРИТЕТ (Нестабильность, утечки памяти) — ✅ **ИСПРАВЛЕНО**

### 5. **OcrProcessor.kt: Lazy-инициализация выбрасывает исключение** — ✅ **ИСПРАВЛЕНО**
**Исправление:** `textRecognizer` сделан nullable, инициализация в `init` блоке с graceful handling ошибки.

### 6. **ScreenCaptureService.kt: Дублирование логики захвата** — ✅ **ИСПРАВЛЕНО**
**Исправление:** Удален `processFrame()` и `OnImageAvailableListener`. Оставлен только `startCaptureLoop()` с периодическим `acquireLatestImage()`.

### 7. **ScreenCaptureService.kt: Инициализация репозитория в `onCreate()`** — ✅ **ИСПРАВЛЕНО**
**Исправление:** Убран вызов `initSheetsRepository()` из `onCreate()`. Lazy-инициализация в `getSheetsRepository()`.

### 8. **CaptureScheduler.kt: `Thread.sleep()` в корутине** — ✅ **ИСПРАВЛЕНО**
**Исправление:** Заменено на `delay(5000)`.

### 9. **CaptureScheduler.kt: Утечка LiveData observer** — ✅ **ИСПРАВЛЕНО**
**Исправление:** Удален метод `ensureServiceRunning()` с `observeForever()`.

### 10. **ScreenCaptureService.kt: Невыпущенные ресурсы при ошибках** — ✅ **ИСПРАВЛЕНО**
**Исправление:** Рефакторинг `stopCapture()` с отдельными `try-finally` для каждого ресурса.

---

## 🟡 СРЕДНИЙ ПРИОРИТЕТ (Надежность, UX) — ✅ **ИСПРАВЛЕНО**

### 11. **SheetsAuthManagerImpl.kt: Отсутствует silentSignIn fallback при ошибке** — ✅ **ИСПРАВЛЕНО**
**Исправление:** Добавлен try-catch вокруг `silentSignIn()`, возвращает `Result.failure` при ошибке.

### 12. **SheetsRepository.kt: Небезопасный cast `as? List<List<Any?>>`** — ✅ **НЕ ТРЕБУЕТ ИСПРАВЛЕНИЯ**
Elvis operator `?: return Result.failure(...)` корректно обрабатывает null. Логирование уже есть.

### 13. **DateUtils.kt: `SimpleDateFormat` не thread-safe** — ✅ **ИСПРАВЛЕНО**
**Исправление:** Заменено на `DateTimeFormatter` (java.time) — потокобезопасен.

### 14. **MainActivity.kt: `checkSignInStatus()` запускается каждый `onResume`** — ✅ **ПРИНЯТО КАК ЕСТЬ**
Не критично — проверка быстрая (только `getLastSignedInAccount`), не делает сетевых запросов.

### 15. **BatteryOptimizationUtils.kt: `startActivityForResult` deprecated** — ✅ **ИСПРАВЛЕНО**
**Исправление:** Методы теперь возвращают `Intent?`, запуск через `ActivityResultLauncher` в `MainActivity`.

---

## 🟢 НИЗКИЙ ПРИОРИТЕТ (Чистота кода) — ✅ **ИСПРАВЛЕНО**

### 16. **AppModule.kt: Неиспользуемый импорт `GoogleAccountCredential`** — ✅ **ИСПРАВЛЕНО**
Удален вместе с `provideSheetsService()`.

### 17. **SheetsAuthManagerImpl.kt: Жестко заданное имя приложения** — ✅ **ИСПРАВЛЕНО**
Обновлено на `"Pocket Option Helper"`.

### 18. **CaptureWorker.kt: Неиспользуемый импорт `Result`** — ✅ **ИСПРАВЛЕНО**
Удален неиспользуемый импорт.

### 19. **ScreenCaptureService.kt: Магические числа** — ✅ **УЖЕ ВЫНЕСЕНЫ**
Все константы в `companion object`.

---

## 📋 ПЛАН ДЕЙСТВИЙ — СТАТУС ВЫПОЛНЕНИЯ

### Этап 1: Исправление критических блокеров запуска (День 1) — ✅ **ЗАВЕРШЁН**
| # | Файл | Действие | Статус |
|---|------|----------|--------|
| 1 | `AppModule.kt` | Удалить `provideSheetsService()` полностью | ✅ |
| 2 | `SheetsAuthManagerImpl.kt` | Переписать `getOrSignInAccount()` → возвращать `Result<GoogleSignInAccount>` | ✅ |
| 3 | `SheetsAuthManagerImpl.kt` | Убрать `signInInteractively()` или сделать его возвращающим `Result` | ✅ |
| 4 | `SheetsRepository.kt` | Обрабатывать `Result.failure(AuthException)` → триггерить вход через UI | ✅ |
| 5 | `PermissionUtils.kt` | Убрать `EXACT_ALARM_PERMISSION` из `requestAllPermissions()` | ✅ |
| 6 | `MainActivity.kt` | Использовать `batteryUtils.getRequestExactAlarmPermissionIntent()` | ✅ |

### Этап 2: Стабильность сервисов (День 2) — ✅ **ЗАВЕРШЁН**
| # | Файл | Действие | Статус |
|---|------|----------|--------|
| 7 | `OcrProcessor.kt` | Сделать `textRecognizer` nullable, обрабатывать ошибку инициализации | ✅ |
| 8 | `ScreenCaptureService.kt` | Убрать дублирование: оставить только `startCaptureLoop()` | ✅ |
| 9 | `ScreenCaptureService.kt` | Убрать `initSheetsRepository()` из `onCreate()` | ✅ |
| 10 | `CaptureScheduler.kt` | Заменить `Thread.sleep(5000)` на `delay(5000)` | ✅ |
| 11 | `CaptureScheduler.kt` | Убрать `ensureServiceRunning()` с `observeForever()` | ✅ |

### Этап 3: Надежность и ресурсы (День 3) — ✅ **ЗАВЕРШЁН**
| # | Файл | Действие | Статус |
|---|------|----------|--------|
| 12 | `ScreenCaptureService.kt` | Рефакторинг `stopCapture()` с `try-finally` для каждого ресурса | ✅ |
| 13 | `DateUtils.kt` | Заменить `SimpleDateFormat` на `DateTimeFormatter` (java.time) | ✅ |
| 14 | `BatteryOptimizationUtils.kt` | Мигрировать на возврат `Intent` + `ActivityResultLauncher` | ✅ |
| 15 | `SheetsAuthManagerImpl.kt` | Обновить `setApplicationName("Pocket Option Helper")` | ✅ |

### Этап 4: Тестирование и верификация (День 4) — 🔄 **ОЖИДАЕТ CI**
| # | Действие | Статус |
|---|----------|--------|
| 1 | Запустить unit-тесты: `./gradlew testDebugUnitTest` (через GitHub Actions) | 🔄 |
| 2 | Собрать APK: `gh workflow run build.yml` | 🔄 |
| 3 | Установить на тестовое устройство (API 26+) | ⏳ |
| 4 | Проверить: cold start, разрешения, вход в Google, захват экрана, запись в Sheets | ⏳ |
| 5 | Проверить перезагрузку устройства (BootReceiver) | ⏳ |
| 6 | Проверить Doze mode / battery optimization | ⏳ |
| 7 | Запустить lint: `gh workflow run android.yml` | 🔄 |

---

## 🛠 КОМАНДЫ ДЛЯ СБОРКИ И ТЕСТИРОВАНИЯ (на GitHub Actions)

```bash
# Сборка через GitHub Actions (обязательно на ARM64)
gh workflow run build.yml
gh run watch <run-id>

# Скачать APK
gh run download <run-id> -n app-debug

# Установка
adb install app-debug.apk

# Логи
adb logcat -s "ScreenCaptureService:V" "OcrProcessor:V" "TextParser:V" "SheetsRepository:V" "MainActivity:V" "Timber:V"

# Unit тесты (на CI)
gh workflow run android.yml
```

---

## 📝 ИЗМЕНЕННЫЕ ФАЙЛЫ (итоговый список)

### Основные исправления:
1. **`app/src/main/java/com/pocet/screenmonitor/di/AppModule.kt`** — удален `provideSheetsService()`
2. **`app/src/main/java/com/pocet/screenmonitor/service/SheetsAuthManager.kt`** — добавлен `getAccountOrNull()`
3. **`app/src/main/java/com/pocet/screenmonitor/service/SheetsAuthManagerImpl.kt`** — полная переработка auth flow
4. **`app/src/main/java/com/pocet/screenmonitor/service/SheetsRepository.kt`** — поддержка nullable Sheets service
5. **`app/src/main/java/com/pocet/screenmonitor/util/PermissionUtils.kt`** — убраны exact alarm permissions
6. **`app/src/main/java/com/pocet/screenmonitor/service/OcrProcessor.kt`** — nullable textRecognizer
7. **`app/src/main/java/com/pocet/screenmonitor/service/ScreenCaptureService.kt`** — убрано дублирование, lazy init repo, улучшен cleanup
8. **`app/src/main/java/com/pocet/screenmonitor/service/CaptureScheduler.kt`** — delay вместо sleep, удален observeForever
9. **`app/src/main/java/com/pocet/screenmonitor/util/DateUtils.kt`** — DateTimeFormatter вместо SimpleDateFormat
10. **`app/src/main/java/com/pocet/screenmonitor/util/BatteryOptimizationUtils.kt`** — возврат Intent вместо startActivityForResult
11. **`app/src/main/java/com/pocet/screenmonitor/MainActivity.kt`** — новый auth flow, новые launchers для permissions

### Ребрендинг:
12. **`app/src/main/res/values/strings.xml`** — название "Pocket Option Helper"
13. **`README.md`** — полное обновление под новое название

---

## ⚠️ ИЗВЕСТНЫЕ ОГРАНИЧЕНИЯ (без изменений)

1. **Нельзя авто-перезапустить сервис после убийства системой** — требуется пользовательское согласие на MediaProjection. `CaptureWorker` только логирует.
2. **ML Kit OCR** может не работать на устройствах без Google Play Services (например, некоторые кастомные ROM).
3. **Google Sheets API** требует валидный OAuth клиент с правильным SHA-1.
4. **Минимальный интервал записи** — 1 минута (защита от превышения квот API).

---

*Документ создан: 2026-10-30*
*Последнее обновление: 2026-10-30*
*Версия: 2.0 — все этапы 1-3 выполнены, ожидает CI верификацию*