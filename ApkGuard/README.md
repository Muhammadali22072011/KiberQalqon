# KiberQalqon — Himoya zararli APK fayllardan

**KiberQalqon** — Android uchun zamonaviy xavfsizlik ilovasi. Avtomatik ravishda zararli APK fayllarni topadi, bloklaydi va o'chiradi. Telegram va boshqa manbalardan yuklab olingan xavfli fayllardan himoya qiladi.

![Version](https://img.shields.io/badge/version-7.3-blue)
![Platform](https://img.shields.io/badge/platform-Android-green)
![Language](https://img.shields.io/badge/language-Kotlin-purple)
![Min SDK](https://img.shields.io/badge/minSdk-24-orange)

## 🎉 Версия 7.3 - Splash Screen и разрешения!

- ✅ **Splash Screen** - красивый экран загрузки с логотипом
- ✅ **Запрос разрешений** - автоматический запрос при первом запуске
- ✅ **Исправлено окно предупреждения** - теперь показывается ВСЕГДА поверх всех окон
- ✅ **Поиск APK везде** - рекурсивный поиск по всему телефону

## 🎨 Версия 7.2 - Неделя 2 завершена на 100%

- ✅ **Профессиональные анимации** - плавные переходы и эффекты
- ✅ **Тёмная тема** - три режима (системная/светлая/тёмная)
- ✅ **Ripple эффекты** - на всех кнопках
- ✅ **Плавные переходы** - между экранами
- ✅ **Адаптивный дизайн** - для любого освещения

## 🎨 Версия 7.1 - Dashboard с графиками

- ✅ **Новый экран Dashboard** - профессиональный вид
- ✅ **Спидометр защиты** - уровень защиты 0-100%
- ✅ **График статистики** - проверки за неделю
- ✅ **Счётчики** - проверено/заблокировано/безопасно
- ✅ **Анимации** - плавные переходы и эффекты

## 🛡️ Версия 7.0 - Критичные исправления

- ✅ **WorkManager** - надёжная периодическая проверка каждые 15 минут
- ✅ **Диалог первого запуска** - помощь в настройке защиты
- ✅ **Исправлены все ошибки** компиляции
- ✅ **Оптимизирована** работа фоновой защиты

## 🎨 Zamonaviy Dizayn

- **Golubо-yashil ranglar** (#00BCD4 + #4CAF50)
- **Gradient sarlavha** va zamonaviy kartochkalar
- **To'liq ekranli avtomatik skanerlash oynasi**
- **Qorong'u va yorug' tema** qo'llab-quvvatlash
- **O'zbek tili** standart til sifatida

## ✨ Asosiy Imkoniyatlar

### 🛡️ Fonda Himoya
- Har 15 daqiqada avtomatik tekshirish
- Yuklab olishlar, Telegram va boshqa papkalarni skanerlash
- Xavfli APK fayllarni avtomatik o'chirish
- Serverga yuklash (ixtiyoriy)

### 🚨 Avtomatik To'liq Ekranli Oyna (YANGI! ⚡)
- **APK yuklab olganda AVTOMATIK ochiladi**
- FileObserver Downloads papkasini kuzatadi
- Yangi APK topilganda darhol skanerlash boshlanadi
- 2 soniyalik animatsiyali skanerlash
- Ranglar bilan natijalar:
  - 🔴 **XAVFLI** (qizil)
  - 🟠 **SHUBHALI** (to'q sariq)
  - 🟢 **XAVFSIZ** (yashil)
- Xavfli fayllar 5 soniyadan keyin avtomatik o'chiriladi
- Xavfsiz fayllar 3 soniyadan keyin avtomatik yopiladi

### 📱 Qo'lda Tekshirish
- Barcha APK fayllar ro'yxati
- Har bir fayl uchun "Tekshirish" tugmasi
- Batafsil natijalar va o'chirish imkoniyati

### 📡 Serverga Yuklash
- Xavfli APK fayllarni serverga avtomatik yuklash
- Sozlamalarda server URL ni belgilash
- Serverda fayllarni ko'rish va yuklab olish

### 🚫 Fishing Bildirishnomalarini Bloklash
- Shubhali bildirishnomalarni avtomatik yashirish
- Kalit so'zlar: kod, bank, havola, Payme va boshqalar
- Bildirishnomalarga kirish kerak (sozlamalarda yoqish)

### 🌐 Ikki Til
- **O'zbek tili** (Oʻzbekcha) - standart
- **Rus tili** (Русский)
- Sarlavhada til almashtirish tugmasi

### 📊 Statistika
- Tekshirilgan fayllar soni
- Bloklangan fayllar soni
- Bildirishnomalarda ko'rsatiladi

## 🚀 Tezkor Boshlash

### Windows uchun (Android Studio siz)

#### 1. Java 21 ni o'rnating
```bash
# ZIP fayldan o'rnatish
USTANOVIT_JAVA21_IZ_ZIP.bat
```

#### 2. APK ni yig'ing
```bash
# Yangi dizayn bilan qayta yig'ish
PERESOBIRAT_PROEKT.bat

# Yoki oddiy yig'ish
ZAPUSK_S_JAVA21.bat
```

#### 3. Telefonга o'rnating
```bash
# USB orqali avtomatik o'rnatish
ZAPUSK_NA_TELEFONE.bat

# Yoki APK ni qo'lda nusxalang
# Fayl: app\build\outputs\apk\debug\app-debug.apk
```

### Android Studio bilan

1. Android Studio ni oching
2. `File → Open` → `KiberQalqon` papkasini tanlang
3. Gradle sinxronizatsiyasini kuting
4. `Run` tugmasini bosing (▶️)

## 🧪 Sinovdan O'tkazish

### Test Server
Emulyatorda sinash uchun test APK fayllar bilan server:

```bash
# Test serverni ishga tushiring
ZAPUSK_TEST_SERVERA.bat
```

Emulyator brauzerida oching:
```
http://10.0.2.2:8000
```

Test fayllar:
- `dangerous_malware.apk` - xavfli virus
- `suspicious_app.apk` - shubhali dastur
- `fake_bank.apk` - soxta bank ilovasi
- `spyware_test.apk` - josuslik dasturi
- `safe_app.apk` - xavfsiz dastur

### APK Qabul Qilish Serveri

```bash
# Serverni ishga tushiring
ZAPUSK_SERVERA.bat
```

Server manzili: `http://localhost:5000`

Ilovada sozlamalarda server URL ni kiriting:
- Bir xil tarmoqda: `http://SIZNING_IP:5000`
- Yoki domen: `https://sizning-domen.com`

## ⚙️ Sozlamalar

### Fonda Himoya
- Avtomatik tekshirish va o'chirishni yoqish/o'chirish
- Har 15 daqiqada ishlaydi

### Serverga Yuklash
- Xavfli APK fayllarni serverga yuklashni yoqish/o'chirish
- Server URL manzilini belgilash

### Fishing Bildirishnomalarini Bloklash
- Shubhali bildirishnomalarni yashirishni yoqish/o'chirish
- Android sozlamalarida "Bildirishnomalarga kirish" ni yoqish kerak

### Til
- O'zbek tili (standart)
- Rus tili
- Sarlavhada 🌐 tugmasi orqali almashtirish

## 📁 Loyiha Tuzilishi

```
KiberQalqon/
├── app/                          # Android ilovasi
│   ├── src/main/
│   │   ├── java/com/kiberqalqon/
│   │   │   ├── MainActivity.kt           # Asosiy ekran
│   │   │   ├── AutoScanActivity.kt       # Avtomatik skanerlash oynasi
│   │   │   ├── ScanResultActivity.kt     # Natijalar ekrani
│   │   │   ├── SettingsActivity.kt       # Sozlamalar
│   │   │   ├── ApkScanner.kt             # Skanerlash mexanizmi
│   │   │   ├── GuardWorker.kt            # Fon xizmati
│   │   │   ├── ServerUpload.kt           # Serverga yuklash
│   │   │   └── PhishingNotificationService.kt  # Fishing bloklash
│   │   ├── res/
│   │   │   ├── layout/                   # Ekran dizaynlari
│   │   │   ├── values/                   # Ranglar, matnlar
│   │   │   ├── values-uz/                # O'zbek tili
│   │   │   └── drawable/                 # Rasmlar, gradientlar
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── server/                       # Python server (APK qabul qilish)
│   ├── app.py                    # Flask server
│   └── requirements.txt
├── test_server/                  # Test APK server
│   ├── test_apk_server.py        # Test server
│   ├── create_test_apk.py        # Test fayllar yaratish
│   └── requirements.txt
├── ZAPUSK_S_JAVA21.bat          # Yig'ish skripti
├── ZAPUSK_NA_TELEFONE.bat       # Telefonga o'rnatish
├── ZAPUSK_TEST_SERVERA.bat      # Test server
├── ZAPUSK_SERVERA.bat           # APK qabul server
└── README.md                     # Bu fayl
```

## 🔒 Xavfsizlik

### Nima Tekshiriladi?

KiberQalqon quyidagilarni tekshiradi:
- Xavfli ruxsatlar (SMS, qo'ng'iroqlar, kontaktlar)
- Ma'lum zararli paketlar
- Shubhali fayl nomlari
- Fishing belgilari

### Nima Yuborilmaydi?

- SMS xabarlar
- Qo'ng'iroqlar tarixi
- Telegram suhbatlari
- Shaxsiy ma'lumotlar

**Faqat xavfli APK fayllar** serverga yuboriladi (agar yoqilgan bo'lsa).

## 🛠️ Texnologiyalar

- **Kotlin** - asosiy dasturlash tili
- **Android Jetpack** - zamonaviy komponentlar
- **WorkManager** - fon ishlari
- **Material Design 3** - zamonaviy dizayn
- **ViewBinding** - xavfsiz view bog'lash
- **OkHttp** - tarmoq so'rovlari
- **Flask** - Python server
- **ProGuard** - kod himoyasi

## 📋 Talablar

### Android Ilova
- Android 7.0 (API 24) va yuqori
- 10 MB bo'sh joy
- Internet (serverga yuklash uchun, ixtiyoriy)

### Ishlab Chiqish
- JDK 17 yoki 21
- Android SDK 34
- Gradle 8.4
- Kotlin 1.9.20

### Server
- Python 3.7+
- Flask 2.0+

## 🔧 Muammolarni Hal Qilish

### Java Versiya Xatosi
```
Error: Dependency requires at least JVM runtime version 11
```
**Yechim:** Java 17 yoki 21 ni o'rnating
```bash
USTANOVIT_JAVA21_IZ_ZIP.bat
```

### Kirill Yo'l Xatosi
```
Error: Your project path contains non-ASCII characters
```
**Yechim:** `gradle.properties` da avtomatik hal qilingan:
```properties
android.overridePathCheck=true
```

### APK Topilmadi
**Yechim:** Ruxsatlarni tekshiring:
- Sozlamalar → Ilovalar → KiberQalqon → Ruxsatlar
- "Fayllar va media" ruxsatini bering

### Bildirishnomalar Ishlamayapti
**Yechim:** Bildirishnomalarga kirishni yoqing:
- Sozlamalar → Bildirishnomalar → Bildirishnomalarga kirish
- KiberQalqon ni toping va yoqing

## 📝 Litsenziya

Bu loyiha ochiq kodli va o'quv maqsadlarida ishlatilishi mumkin.

## 👨‍💻 Muallif

Muhammadali

## 🤝 Hissa Qo'shish

1. Loyihani fork qiling
2. Yangi branch yarating (`git checkout -b feature/yangi-funksiya`)
3. O'zgarishlarni commit qiling (`git commit -am 'Yangi funksiya qo'shildi'`)
4. Branch ga push qiling (`git push origin feature/yangi-funksiya`)
5. Pull Request yarating

## 📞 Aloqa

Savollar yoki takliflar bo'lsa, issue oching yoki pull request yuboring.

---

**⚠️ Ogohlantirish:** Bu ilova faqat ma'lum xavflarni aniqlaydi. 100% himoya kafolati bermaydi. Noma'lum manbalardan APK o'rnatishdan ehtiyot bo'ling!

**🛡️ KiberQalqon - Telefoningizni himoya qiling!**
