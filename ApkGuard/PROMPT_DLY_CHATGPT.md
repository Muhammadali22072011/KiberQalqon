# 🤖 ПРОМПТ ДЛЯ CHATGPT

Скопируй этот текст и отправь в ChatGPT:

---

# KiberQalqon - Android Antivirus Project

## 📱 Описание проекта

KiberQalqon - это Android приложение-антивирус для защиты от вредоносных APK файлов. Приложение автоматически сканирует скачанные APK файлы, определяет опасные и удаляет их.

## 🛠️ Технологии

- **Язык:** Kotlin
- **Min SDK:** 24 (Android 7.0)
- **Target SDK:** 34
- **Build System:** Gradle 8.4
- **JDK:** 21
- **Архитектура:** MVVM
- **UI:** Material Design 3, ViewBinding
- **Async:** Coroutines, Flow
- **Background:** WorkManager
- **Network:** OkHttp

## 📂 Структура проекта

```
KiberQalqon/
├── app/
│   ├── src/main/
│   │   ├── java/com/kiberqalqon/
│   │   │   ├── MainActivity.kt          # Главный экран
│   │   │   ├── AutoScanActivity.kt      # Полноэкранное сканирование
│   │   │   ├── ApkScanner.kt            # Детектор вирусов
│   │   │   ├── ApkFileObserver.kt       # Следит за новыми APK
│   │   │   ├── GuardWorker.kt           # Фоновая защита
│   │   │   ├── App.kt                   # Application класс
│   │   │   └── Config.kt                # Настройки
│   │   ├── res/
│   │   │   ├── layout/                  # XML layouts
│   │   │   ├── values/                  # Strings, colors
│   │   │   ├── values-uz/               # Узбекский язык
│   │   │   └── drawable/                # Иконки, градиенты
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── server/                              # Python Flask сервер
└── test_server/                         # Тестовый сервер
```

## ✨ Основные функции

1. **Автоматическое сканирование**
   - FileObserver следит за папками Downloads, Telegram, WhatsApp
   - При появлении нового APK автоматически запускается сканирование
   - Полноэкранное окно с результатами

2. **Детектор вирусов**
   - 40+ сигнатур известных вирусов
   - Анализ разрешений (SMS, контакты, звонки)
   - Проверка содержимого APK (ZIP файл)
   - Эвристический анализ

3. **Фоновая защита**
   - WorkManager запускается каждые 15 минут
   - Автоматическое удаление опасных файлов
   - Уведомления со звуком

4. **Два языка**
   - Узбекский (по умолчанию)
   - Русский

## 🔧 Ключевые компоненты

### ApkScanner.kt
```kotlin
object ApkScanner {
    // Поиск APK файлов
    fun findApkFiles(context: Context): List<ApkItem>
    
    // Сканирование APK
    fun scan(context: Context, apkPath: String): ScanResult
}
```

### ApkFileObserver.kt
```kotlin
class ApkFileObserver(context: Context, path: File) : FileObserver() {
    // Следит за новыми APK файлами
    override fun onEvent(event: Int, path: String?)
}
```

### AutoScanActivity.kt
```kotlin
class AutoScanActivity : AppCompatActivity() {
    // Полноэкранное окно сканирования
    // Анимация 2 секунды
    // Автоудаление опасных через 5 сек
}
```

## 🎨 Дизайн

- **Цвета:** Голубой (#00BCD4) + Зелёный (#4CAF50)
- **Градиенты:** Голубой → Зелёный
- **Карточки:** Закруглённые (12-16dp radius)
- **Результаты:**
  - 🔴 Опасно: #F44336 на #FFEBEE фоне
  - 🟠 Подозрительно: #FF9800 на #FFF3E0 фоне
  - 🟢 Безопасно: #4CAF50 на #E8F5E9 фоне

## ⚠️ ТЕКУЩИЕ ПРОБЛЕМЫ

### 1. FileObserver не всегда срабатывает
**Проблема:** При скачивании APK через браузер полноэкранное окно не всегда появляется.

**Код:**
```kotlin
class ApkFileObserver(private val context: Context, path: File) 
    : FileObserver(path, CREATE or MOVED_TO or CLOSE_WRITE) {
    
    override fun onEvent(event: Int, path: String?) {
        if (path?.endsWith(".apk") == true) {
            // Запускаем AutoScanActivity
        }
    }
}
```

**Что нужно:**
- Надёжный способ перехвата установки APK
- Возможно использовать BroadcastReceiver?

### 2. Нужен современный дизайн
**Что хочу:**
- Спидометр анализа (как в антивирусах)
- Графики и диаграммы
- Анимации сканирования
- Волны и эффекты

**Пример дизайна:**
```
┌─────────────────────────────────┐
│  🛡️ KiberQalqon                   │
│                                 │
│     ╭─────────────╮             │
│     │  СПИДОМЕТР  │             │
│     │   0-100%    │             │
│     ╰─────────────╯             │
│                                 │
│  Анализируется...               │
│  dangerous_malware.apk          │
│                                 │
│  ▓▓▓▓▓▓▓▓▓▓░░░░░░ 65%          │
│                                 │
│  📊 Графики в реальном времени  │
└─────────────────────────────────┘
```

### 3. Улучшить детектор вирусов
**Текущий подход:**
- Проверка разрешений
- Поиск строк в APK (ZIP)
- 40 сигнатур

**Что нужно:**
- Анализ DEX файлов
- Проверка сертификатов
- Машинное обучение?
- База данных известных вирусов

### 4. Производительность
**Проблема:** При поиске APK по всему телефону приложение лагает.

**Текущий код:**
```kotlin
fun findApkFiles(context: Context): List<ApkItem> {
    // Сканирует Downloads, Telegram, WhatsApp, Bluetooth
    // Без рекурсии, максимум 20 файлов
}
```

**Что нужно:**
- Оптимизировать поиск
- Кэширование результатов?
- Индексация файлов?

## 🎯 ЧТО НУЖНО СДЕЛАТЬ

1. **Исправить FileObserver** - чтобы всегда срабатывал
2. **Добавить современный дизайн** - спидометр, графики, анимации
3. **Улучшить детектор** - больше методов анализа
4. **Оптимизировать** - убрать лаги

## 📝 ВОПРОСЫ К CHATGPT

1. Как надёжно перехватывать установку APK в Android?
2. Как создать спидометр и графики без тяжёлых библиотек?
3. Какие ещё методы анализа APK можно использовать?
4. Как оптимизировать поиск файлов по всему телефону?
5. Есть ли готовые решения для детектора вирусов?

## 📦 Сборка проекта

```bash
# Windows
ZAPUSK_S_JAVA21.bat

# Или вручную
gradlew.bat assembleDebug
```

APK находится в: `app/build/outputs/apk/debug/app-debug.apk`

---

**Помоги улучшить этот проект! Дай конкретные решения с кодом.**

