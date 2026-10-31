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

1. Откройте вашу Google Таблицу (файл с листом **Plan_30_days**)
2. **Расширения → Apps Script**
3. Удалите весь код и вставьте:

```javascript
function doPost(e) {
  try {
    const data = JSON.parse(e.postData.contents);
    // data: { date: "DD.MM.YYYY", amount: "1234.56", accountType: "REAL" }
    
    const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName('Plan_30_days');
    if (!sheet) {
      return ContentService.createTextOutput('Error: Sheet "Plan_30_days" not found');
    }
    
    const dates = sheet.getRange('B:B').getValues().flat();
    // Ищем сегодняшнюю дату в формате DD.MM.YYYY
    const today = data.date; // Приходит из приложения как "06.11.2026"
    
    const row = dates.findIndex(d => {
      if (!d) return false;
      const cellDate = new Date(d);
      if (isNaN(cellDate.getTime())) return false;
      // Нормализуем дату из ячейки к DD.MM.YYYY
      const day = String(cellDate.getDate()).padStart(2, '0');
      const month = String(cellDate.getMonth() + 1).padStart(2, '0');
      const year = cellDate.getFullYear();
      return `${day}.${month}.${year}` === today;
    }) + 1;
    
    if (row > 0) {
      sheet.getRange(`E${row}`).setValue(data.amount);
      return ContentService.createTextOutput('OK');
    } else {
      return ContentService.createTextOutput('Date not found: ' + today);
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

### 2. Подготовка таблицы (лист Plan_30_days)

| Колонка | Назначение | Формат |
|---------|------------|--------|
| **B** (строки 6–35) | Даты | Любой, понятный Google Sheets: `06.11.2026`, `2026-11-06`, `06/11/2026` |
| **E** | Сумма баланса | Записывается автоматически |

**Пример заполнения колонки B:**
```
B6:  01.11.2026
B7:  02.11.2026
B8:  03.11.2026
...
B35: 30.11.2026
```

> ⚠️ **Важно**: Лист должен называться точно **`Plan_30_days`** (регистр важен). Если лист называется иначе — обновите `getSheetByName('Plan_30_days')` в коде Apps Script.

### 3. Ввод URL в приложении

1. Откройте приложение
2. Нажмите **"URL Apps Script не задан"**
3. Вставьте скопированный URL → **Сохранить**
4. Нажмите **"Проверить соединение"** — должно появиться "Соединение успешно!"

### 4. Сборка и установка (только GitHub Actions)

```bash
# Запуск сборки на GitHub Actions (ARM64 не поддерживает локальную сборку)
gh workflow run build.yml

# Скачивание APK после успешной сборки
gh run download <run-id> -n app-debug

# Установка на устройство
adb install app-debug.apk
```

Или скачайте готовый APK из релиза:
```
https://github.com/Lumus-0x/PocetOption_fix/releases/latest
```

### 5. Запуск на устройстве

1. **Откройте приложение** → проверьте, что URL задан (кнопка показывает "URL Apps Script задан ✓")
2. **Нажмите "Запросить все разрешения"**:
   - Исключение из оптимизации батареи (критично для фона)
   - Точные будильники (Android 14+)
   - Уведомления (Android 13+)
3. **Нажмите "Начать захват"** → разрешите запись экрана в системном диалоге
4. Приложение работает в фоне, проверяет экран каждые 10 секунд

## Как это работает

1. Приложение каждые 10 сек захватывает экран через `MediaProjection`
2. ML Kit распознаёт текст на кадре
3. Парсер ищет ключевые слова: `qt real usd`, `qt demo usd`, `real usd`, `demo usd`
4. Найдя ключевое слово, ищет сумму **ниже** (в следующих 4 строках) или на той же строке
5. Формирует JSON: `{ "date": "06.11.2026", "amount": "1234.56", "accountType": "REAL" }`
6. POST на ваш Apps Script Web App URL
7. Apps Script находит в листе `Plan_30_days` строку с сегодняшней датой в колонке B
8. Записывает сумму в колонку E этой строки

## Формат данных

**Отправляемый JSON (Apps Script):**
```json
{
  "date": "06.11.2026",
  "amount": "1234.56",
  "accountType": "REAL"
}
```

**Поддерживаемые форматы сумм на экране:**
- `$1,234.56` | `1,234.56` | `1234.56` | `1.234,56` (EU)

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

### Логика отправки в Apps Script
1. Формирует JSON с датой в формате `DD.MM.YYYY`
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
| Ошибка отправки | Проверьте URL Apps Script, права доступа таблицы, имя листа `Plan_30_days` |
| "Date not found" | Проверьте формат дат в колонке B — должны парситься в Date |
| MediaProjection отклонён | Пользователь должен разрешить в системном диалоге |

### Отладка Apps Script

В Apps Script: **Выполнения** (Executions) — увидите логи каждого POST:
- `OK` — успех
- `Date not found: 06.11.2026` — нет сегодняшней даты в колонке B
- `Error: Sheet "Plan_30_days" not found` — неверное имя листа
- `Error: ...` — другая ошибка (проверьте код)

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