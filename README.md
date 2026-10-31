# Pocket Option Helper

Android-приложение (API 26+), работающее в фоне, мониторящее экран на предмет баланса торгового аккаунта Pocket Option ("qt real usd" или "qt demo usd") и отправляющее его в Google Таблицы через **Google Apps Script Web App**.

## Особенности

- **Фоновый захват экрана**: MediaProjection API + Foreground Service для непрерывного мониторинга
- **OCR на устройстве**: ML Kit Text Recognition обрабатывает контент экрана локально (без облака)
- **Умное определение баланса**: Ищет "qt real usd" / "qt demo usd" и извлекает ближайшие суммы
- **Интеграция через Apps Script**: Простой HTTP POST на Web App URL — **нет OAuth / Google Sign-In**
- **Оптимизация батареи**: Учёт Doze mode, запрос исключения из оптимизации, WorkManager для планирования
- **Android 14+ совместимость**: Foreground service types, точные будильники, разрешения уведомлений

## Архитектура

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  MediaProjection │────▶│  VirtualDisplay  │────▶│   ImageReader   │
└─────────────────┘     └──────────────────┘     └────────┬────────┘
                                                          │
                                                          ▼
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  Apps Script    │◀────│  AppsScriptClient │◀────│   TextParser    │
│  Web App (POST) │     │   (OkHttp)       │     │  (Regex Match)  │
└─────────────────┘     └──────────────────┘     └────────┬────────┘
                                                          │
                                                          ▼
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  Foreground     │────▶│   OcrProcessor   │────▶│  ML Kit Text    │
│  Service        │     │  (Bitmap Convert)│     │  Recognition    │
└─────────────────┘     └──────────────────┘     └─────────────────┘
```

## Структура проекта

```
app_pocet/
├── app/
│   ├── src/main/
│   │   ├── java/com/pocet/screenmonitor/
│   │   │   ├── PocetApplication.kt          # Hilt Application
│   │   │   ├── MainActivity.kt              # Main UI & permission handling
│   │   │   ├── di/
│   │   │   │   ├── AppModule.kt             # Hilt DI module
│   │   │   │   └── ServiceEntryPoint.kt     # EntryPoint для Services
│   │   │   ├── model/
│   │   │   │   └── BalanceResult.kt         # Модель распознанного баланса
│   │   │   ├── service/
│   │   │   │   ├── ScreenCaptureService.kt  # Foreground service (core)
│   │   │   │   ├── OcrProcessor.kt          # ML Kit OCR pipeline
│   │   │   │   ├── TextParser.kt            # Логика извлечения баланса
│   │   │   │   ├── AppsScriptClient.kt      # HTTP клиент для Apps Script
│   │   │   │   └── CaptureScheduler.kt      # WorkManager планирование
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

## Требования

- Android 8.0+ (API 26) — Min SDK
- Android 14+ (API 34) — Target SDK (поддержка foreground service type)
- Google Play Services (для ML Kit)
- Интернет для отправки данных в Apps Script

## Настройка (5 минут)

### 1. Создание Apps Script в Google Таблице

1. Откройте вашу Google Таблицу
2. **Расширения → Apps Script**
3. Удалите весь код и вставьте:

```javascript
function doPost(e) {
  try {
    const data = JSON.parse(e.postData.contents);
    // data: { date: "MM/dd/yyyy", amount: "1234.56", accountType: "REAL" }
    
    const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName('Лист1');
    const dates = sheet.getRange('B:B').getValues().flat();
    const row = dates.findIndex(d => d === data.date) + 1;
    
    if (row > 0) {
      sheet.getRange(`E${row}`).setValue(data.amount);
      return ContentService.createTextOutput('OK');
    } else {
      return ContentService.createTextOutput('Date not found: ' + data.date);
    }
  } catch (err) {
    return ContentService.createTextOutput('Error: ' + err.message);
  }
}
```

4. **Сохранить** (Ctrl+S) → проект назовите "Pocket Option Helper"
5. **Развернуть → Новое развёртывание → Тип: Веб-приложение**
   - **Выполнять от моего имени**
   - **У кого есть доступ: Все** (или "Все, у кого есть ссылка")
6. **Развернуть** → скопируйте **URL веб-приложения** (формат: `https://script.google.com/macros/s/.../exec`)

### 2. Настройка таблицы

- Колонка **B (строки 6–35)**: даты в формате `MM/dd/yyyy` (например: `01/15/2024`)
- Колонка **E**: сюда будет записываться баланс
- Поддерживаемые форматы дат: `MM/dd/yyyy`, `yyyy-MM-dd`, `dd/MM/yyyy`, `MM-dd-yyyy`

### 3. Ввод URL в приложении

1. Откройте приложение
2. Нажмите **"URL Apps Script не задан"**
3. Вставьте скопированный URL → **Сохранить**
3. Нажмите **"Проверить соединение"** — должно появиться "Соединение успешно!"

### 4. Сборка и установка (только GitHub Actions)

```bash
# Запуск сборки на GitHub Actions (ARM64 не поддерживает локальную сборку)
gh workflow run build.yml

# Скачивание APK после успешной сборки
gh run download <run-id> -n app-debug

# Установка на устройство
adb install app-debug.apk
```

### 5. Запуск на устройстве

1. **Откройте приложение** → проверьте, что URL задан
2. **Нажмите "Запросить все разрешения"**:
   - Исключение из оптимизации батареи (критично для фона)
   - Точные будильники (Android 14+)
   - Уведомления (Android 13+)
3. **Нажмите "Начать захват"** → разрешите запись экрана в системном диалоге
4. Приложение работает в фоне, проверяет экран каждые 10 секунд

## Формат Google Таблицы

| Строка | Колонка B (Дата) | Колонка E (Сумма) |
|------|------------------|-------------------|
| 6    | 01/15/2024       | *авто*            |
| 7    | 01/16/2024       | *авто*            |
| ...  | ...              | ...               |
| 35   | 02/13/2024       | *авто*            |

## Ключевые детали реализации

### Foreground Service (Android 14+)
```xml
<service
    android:name=".service.ScreenCaptureService"
    android:foregroundServiceType="mediaProjection"
    android:exported="false"
    android:stopWithTask="false" />
```

### Жизненный цикл MediaProjection
1. Пользователь даёт разрешение через системный диалог
2. Сервис создаёт `VirtualDisplay` → `ImageReader`
3. Захватывает кадры каждые 10 секунд
4. Обрабатывает через ML Kit в фоновом потоке

### OCR обработка
- Конвертирует YUV_420_888 Image → Bitmap
- Даунсемплинг до макс. 1920px для производительности
- Запускает ML Kit Text Recognition
- Парсит текстовые блоки на ключевые слова + суммы

### Паттерны парсинга
```kotlin
// Ключевые слова
"qt real usd", "qt demo usd", "real usd", "demo usd"

// Форматы сумм
$1,234.56 | 1,234.56 | 1234.56 | 1.234,56 (EU)
```

### Логика отправки в Apps Script
1. Формирует JSON: `{ "date": "MM/dd/yyyy", "amount": "1234.56", "accountType": "REAL" }`
2. POST на Web App URL с таймаутом 30 сек
3. Минимум 1 минута между отправками (защита от спама)
4. Логирует успех/ошибку в уведомление

### Оптимизация батареи
- Запрашивает `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- WorkManager с ограничениями: сеть + батарея не низкая
- Пауза в Doze mode
- Минимум 1 минута между записями

## Устранение неполадок

| Проблема | Решение |
|----------|---------|
| Сервис убивается в фоне | Отключите оптимизацию батареи, дайте разрешение точных будильников |
| OCR не находит текст | Проверьте яркость экрана, убедитесь что "qt real usd" видно |
| Ошибка отправки | Проверьте URL Apps Script, права доступа таблицы, формат даты в колонке B |
| MediaProjection отклонён | Пользователь должен разрешить в системном диалоге |

## Разрешения

| Разрешение | Назначение | Запрос |
|------------|------------|--------|
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Фоновый захват экрана | Только манифест |
| `MEDIA_PROJECTION` | Интент захвата экрана | Системный диалог |
| `INTERNET` | HTTP POST в Apps Script | Только манифест |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Защита от Doze | Settings intent |
| `SCHEDULE_EXACT_ALARM` (Android 14+) | Точное планирование | Settings intent |
| `POST_NOTIFICATIONS` (Android 13+) | Уведомление сервиса | Runtime диалог |

## Полезные ссылки

- [Apps Script Web App руководство](https://developers.google.com/apps-script/guides/web)
- [ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition)
- [MediaProjection API](https://developer.android.com/media/media-projection)
- [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)

## Лицензия

MIT License — модифицируйте под свои торговые нужды.