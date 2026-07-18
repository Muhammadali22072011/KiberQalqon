# 🔍 ПРОБЛЕМА: Android приложение не может найти APK файлы

## 📱 Контекст:
Разрабатываю Android антивирус **UzGuard** на Kotlin. Приложение должно находить все APK файлы на устройстве и показывать их в списке.

## ❌ ПРОБЛЕМА:
1. При включении "Фоновая защита" показывается индикатор "Поиск APK файлов..."
2. Поиск зависает и не завершается
3. Список остаётся пустым: "Topilgan APK: 0"
4. Хотя на устройстве есть APK файлы (например, в Downloads, Telegram)

## 📋 КОД ПОИСКА APK:

### ApkScanner.kt - функция поиска:
```kotlin
object ApkScanner {
    fun findApkFiles(context: Context): List<ApkItem> {
        val result = mutableListOf<ApkItem>()
        val seenPaths = mutableSetOf<String>()
        
        try {
            val storage = Environment.getExternalStorageDirectory()
            
            // Основные папки для поиска
            val foldersToScan = listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                File(storage, "Telegram/Telegram Documents"),
                File(storage, "WhatsApp/Media"),
                File(storage, "Bluetooth")
            )
            
            for (folder in foldersToScan) {
                if (folder?.exists() == true) {
                    scanFolder(folder, result, seenPaths)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ApkScanner", "Error", e)
        }

        return result.sortedByDescending { it.file.lastModified() }
    }
    
    private fun scanFolder(folder: File, result: MutableList<ApkItem>, seenPaths: MutableSet<String>) {
        try {
            val files = folder.listFiles() ?: return
            
            for (file in files) {
                try {
                    if (file.isFile && file.name.endsWith(".apk", ignoreCase = true)) {
                        val path = file.absolutePath
                        if (path !in seenPaths) {
                            seenPaths.add(path)
                            result.add(
                                ApkItem(
                                    file = file,
                                    name = file.name,
                                    path = path,
                                    sizeBytes = file.length()
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    // Игнорируем ошибки отдельных файлов
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ApkScanner", "Error scanning folder", e)
        }
    }
}
```

### MainActivity.kt - вызов поиска:
```kotlin
private fun startAutoProtection() {
    scope.launch(Dispatchers.IO) {
        try {
            // Показываем индикатор загрузки
            withContext(Dispatchers.Main) {
                binding.loadingLayout.visibility = View.VISIBLE
                binding.tvCount.visibility = View.GONE
                binding.btnRefresh.isEnabled = false
            }
            
            // Поиск APK с таймаутом 15 секунд
            val apks = withTimeout(15000) {
                try {
                    ApkScanner.findApkFiles(applicationContext)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Error finding APK", e)
                    emptyList()
                }
            }
            
            android.util.Log.d("MainActivity", "✅ Найдено APK: ${apks.size}")
            
            withContext(Dispatchers.Main) {
                // Обновляем список
                adapter = ApkAdapter(apks) { item -> /* ... */ }
                binding.recycler.adapter = adapter
                binding.tvCount.text = getString(R.string.apk_count, apks.size)
                
                // Скрываем индикатор
                binding.loadingLayout.visibility = View.GONE
                binding.tvCount.visibility = View.VISIBLE
                binding.btnRefresh.isEnabled = true
                
                if (apks.isNotEmpty()) {
                    Toast.makeText(this@MainActivity, "✅ Найдено ${apks.size} APK файлов", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "APK файлы не найдены", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Таймаут
            withContext(Dispatchers.Main) {
                binding.loadingLayout.visibility = View.GONE
                binding.tvCount.text = getString(R.string.apk_count, 0)
                binding.tvCount.visibility = View.VISIBLE
                binding.btnRefresh.isEnabled = true
                Toast.makeText(this@MainActivity, "Поиск занял слишком много времени", Toast.LENGTH_LONG).show()
            }
        }
    }
}
```

### AndroidManifest.xml - разрешения:
```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
```

## ✅ ЧТО УЖЕ СДЕЛАНО:
1. ✅ Разрешения запрашиваются при первом запуске
2. ✅ Пользователь разрешает "Доступ ко всем файлам" в настройках Android
3. ✅ Добавлен таймаут 15 секунд
4. ✅ Обработка ошибок
5. ✅ Логи в консоль

## 🔍 ЧТО ПОКАЗЫВАЮТ ЛОГИ:
```
D/MainActivity: 🔍 Начинаю поиск APK...
(долгая пауза или ничего)
```

## ❓ ВОПРОСЫ:

1. **Почему `findApkFiles()` может зависать или не находить файлы?**
   - Проблема с разрешениями?
   - Неправильные пути к папкам?
   - Проблема с Android 11+ (Scoped Storage)?

2. **Как правильно искать APK файлы на Android 11+ (API 30+)?**
   - Нужно ли использовать MediaStore API?
   - Как получить доступ ко всем папкам?

3. **Почему `Environment.getExternalStoragePublicDirectory()` может не работать?**
   - Устарел?
   - Нужны другие методы?

4. **Как правильно сканировать папки рекурсивно без зависаний?**
   - Нужна ли оптимизация?
   - Как избежать блокировки потока?

5. **Есть ли альтернативные способы найти все APK на устройстве?**
   - ContentResolver?
   - MediaStore?
   - DocumentsProvider?

## 🎯 ЦЕЛЬ:
Нужно чтобы приложение **НАДЁЖНО** находило все APK файлы на устройстве:
- В папке Downloads
- В папке Telegram
- В папке WhatsApp
- В любых других папках
- На Android 11+ (API 30+)
- Без зависаний
- Быстро (до 10 секунд)

## 📱 УСТРОЙСТВО:
- Android 11+ (API 30+)
- Разрешение MANAGE_EXTERNAL_STORAGE получено
- Scoped Storage включён

## 💡 ЧТО НУЖНО:
1. Исправленный код `findApkFiles()` который **ГАРАНТИРОВАННО** найдёт APK
2. Правильный способ доступа к файлам на Android 11+
3. Оптимизация для быстрого поиска
4. Обработка всех возможных ошибок

---

**Пожалуйста, помоги исправить код поиска APK файлов!** 🙏
