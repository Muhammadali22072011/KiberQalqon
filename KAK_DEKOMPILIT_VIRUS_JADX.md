# 🔬 КАК ДЕКОМПИЛИРОВАТЬ ВИРУС С ПОМОЩЬЮ JADX

## 📥 Шаг 1: Скачать JADX

### Вариант А: Скачать с GitHub
1. Открой браузер
2. Перейди: https://github.com/skylot/jadx/releases
3. Скачай последнюю версию: **jadx-1.5.0.zip** (или новее)
4. Распакуй в папку `jadx`

### Вариант Б: Скачать прямая ссылка
```
https://github.com/skylot/jadx/releases/download/v1.5.0/jadx-1.5.0.zip
```

---

## 🖥️ Шаг 2: Запустить JADX GUI

### Windows:
1. Распакуй `jadx-1.5.0.zip`
2. Зайди в папку `jadx\bin`
3. Запусти **`jadx-gui.bat`** (двойной клик)

### Или через командную строку:
```cmd
cd jadx\bin
jadx-gui.bat
```

---

## 📂 Шаг 3: Открыть вирусный APK в JADX

1. В JADX GUI нажми **File → Open**
2. Выбери файл: `Video_202_089_mp8ㅤㅤㅤㅤ.apk`
3. Подожди пока JADX декомпилирует (1-2 минуты)

---

## 🔍 Шаг 4: Что смотреть в JADX

### 1️⃣ AndroidManifest.xml
**Путь:** `Resources → AndroidManifest.xml`

**Что искать:**
- ✅ **Разрешения (permissions):**
  - `SEND_SMS` - отправка SMS
  - `READ_SMS` - чтение SMS
  - `READ_CONTACTS` - доступ к контактам
  - `CALL_PHONE` - звонки
  - `ACCESS_FINE_LOCATION` - местоположение
  - `CAMERA` - камера
  - `RECORD_AUDIO` - микрофон
  - `SYSTEM_ALERT_WINDOW` - окна поверх других приложений
  - `REQUEST_INSTALL_PACKAGES` - установка APK

- ✅ **Сервисы (services):**
  - Фоновые процессы
  - Автозапуск при загрузке

- ✅ **Receivers:**
  - `BOOT_COMPLETED` - запуск при включении телефона
  - `SMS_RECEIVED` - перехват SMS

### 2️⃣ Главный класс (MainActivity)
**Путь:** `Source code → com.xxx.xxx → MainActivity`

**Что искать:**
- Что делает приложение при запуске
- Куда отправляет данные
- Какие URL/IP адреса использует

### 3️⃣ Подозрительные классы
**Путь:** `Source code → Cvkiiobywakcmauentcrxnquxsgdxplglhzd`

**Обфусцированные имена классов:**
```
Cvkiiobywakcmauentcrxnquxsgdxplglhzd.Kgqsaqftbnylwlsfgoukmlbsiaiph
Gvomrituctwfbnloyvxphvtlclqxiswbb.Nthobvwbltqxzidwrhklomo
```

**Что искать:**
- Шифрование/дешифрование
- Отправка данных на сервер
- Кража SMS/контактов

### 4️⃣ Строки (Strings)
**Путь:** `Tools → Search → Text Search`

**Что искать:**
- URL адреса: `http://`, `https://`
- IP адреса: `192.168.x.x`, `xxx.xxx.xxx.xxx`
- Номера телефонов
- API ключи
- Команды: `su`, `root`, `shell`

### 5️⃣ Native библиотеки
**Путь:** `Resources → assets → libs → arm64-v8a → libnative-lib.so`

**Что делать:**
- Эти файлы на C/C++ - сложнее анализировать
- Можно открыть в IDA Pro или Ghidra (продвинутые инструменты)

---

## 📝 Шаг 5: Что записать в отчёт

### ✅ Разрешения из AndroidManifest.xml
Пример:
```xml
<uses-permission android:name="android.permission.SEND_SMS"/>
<uses-permission android:name="android.permission.READ_SMS"/>
<uses-permission android:name="android.permission.READ_CONTACTS"/>
```

### ✅ URL/IP адреса из кода
Пример:
```java
String serverUrl = "http://malware-server.com/api/upload";
String ip = "185.xxx.xxx.xxx";
```

### ✅ Подозрительные действия
Пример:
```java
// Отправка SMS
SmsManager.sendTextMessage(phoneNumber, null, message, null, null);

// Кража контактов
Cursor cursor = getContentResolver().query(ContactsContract.Contacts.CONTENT_URI, ...);

// Запись звонков
MediaRecorder recorder = new MediaRecorder();
```

---

## 🎯 Альтернатива: Командная строка JADX

Если не хочешь GUI, можно через командную строку:

```cmd
cd jadx\bin
jadx.bat -d output "Video_202_089_mp8.apk"
```

Это создаст папку `output` с декомпилированным кодом.

Потом можно открыть в любом текстовом редакторе:
```
output\sources\com\xxx\xxx\MainActivity.java
output\resources\AndroidManifest.xml
```

---

## 🔍 Что искать в декомпилированном коде

### 🚨 Признаки вредоносного ПО:

#### 1. Кража SMS
```java
SmsManager.getDefault().sendTextMessage(...)
ContentResolver.query(Uri.parse("content://sms/inbox"), ...)
```

#### 2. Кража контактов
```java
getContentResolver().query(ContactsContract.Contacts.CONTENT_URI, ...)
```

#### 3. Отправка данных на сервер
```java
HttpURLConnection connection = (HttpURLConnection) url.openConnection();
connection.setRequestMethod("POST");
// Отправка украденных данных
```

#### 4. Запись звонков/микрофона
```java
MediaRecorder recorder = new MediaRecorder();
recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
```

#### 5. Отслеживание местоположения
```java
LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
locationManager.requestLocationUpdates(...)
```

#### 6. Установка других APK
```java
Intent intent = new Intent(Intent.ACTION_VIEW);
intent.setDataAndType(Uri.fromFile(new File(apkPath)), "application/vnd.android.package-archive");
startActivity(intent);
```

#### 7. Root эксплойты
```java
Runtime.getRuntime().exec("su");
Runtime.getRuntime().exec("chmod 777 /system/...");
```

---

## 📊 Пример отчёта после анализа

```
🦠 РЕЗУЛЬТАТЫ ДЕКОМПИЛЯЦИИ

📱 Package Name: com.malware.example

🔐 Опасные разрешения:
- SEND_SMS ✅
- READ_SMS ✅
- READ_CONTACTS ✅
- CALL_PHONE ✅
- ACCESS_FINE_LOCATION ✅
- CAMERA ✅
- RECORD_AUDIO ✅

🌐 Найденные URL:
- http://malware-server.com/api/upload
- http://185.xxx.xxx.xxx:8080/data

📝 Вредоносные действия:
1. Отправка SMS на платные номера
2. Кража контактов и отправка на сервер
3. Запись звонков
4. Отслеживание местоположения
5. Установка дополнительных APK

🎯 Вердикт: ОПАСНЫЙ ВИРУС
```

---

## 🛠️ Дополнительные инструменты

### APKTool (для распаковки ресурсов)
```cmd
apktool d Video_202_089_mp8.apk -o output
```

### dex2jar (конвертация DEX в JAR)
```cmd
d2j-dex2jar.bat classes.dex
```

Потом открыть JAR в JD-GUI:
```cmd
jd-gui.exe classes-dex2jar.jar
```

### VirusTotal (онлайн проверка)
1. Открой: https://www.virustotal.com
2. Загрузи APK файл
3. Посмотри результаты от 70+ антивирусов

---

## ⚠️ ВАЖНО!

1. **НЕ УСТАНАВЛИВАЙ** вирусный APK на свой телефон!
2. Анализируй только на компьютере
3. Используй виртуальную машину если хочешь запустить
4. После анализа **удали** APK файл

---

## 📚 Полезные ссылки

- **JADX:** https://github.com/skylot/jadx
- **APKTool:** https://apktool.org
- **dex2jar:** https://github.com/pxb1988/dex2jar
- **JD-GUI:** http://java-decompiler.github.io
- **VirusTotal:** https://www.virustotal.com

---

*Инструкция создана для анализа вирусного APK*
*Дата: 10.02.2026*
