<div align="center">

# 🛡️ KiberQalqon

**O'zbek foydalanuvchilarini bank troyanlaridan himoya qiluvchi Android antivirus**

![Platforma](https://img.shields.io/badge/platforma-Android%208%2B%20(API%2024)-3ddc84)
![Til](https://img.shields.io/badge/Kotlin-50.8%25-7f52ff)
![Versiya](https://img.shields.io/badge/versiya-8.0%20(80)-f0506e)
![Bulut](https://img.shields.io/badge/bulut-Vercel%20%2B%20Supabase-000000)

</div>

---

## Bu nima?

**KiberQalqon** — ikki bir-biriga chambarchas bog'liq qismdan iborat shaxsiy himoya-xavfsizlik loyihasi:

1. **Antivirus** — zararli APK'larni **topadigan, baholaydigan, karantinga oladigan va o'chiradigan** mahalliy (on-device) Android ilova (`com.kiberqalqon`, Kotlin). U aynan O'zbekistonda Telegram orqali — rasm, video va taklifnoma ko'rinishida — tarqaladigan bank troyanlari va dropperlarini tutish uchun yaratilgan.

2. **Zararli dastur tahlili (case study)** — haqiqiy o'zbek bank troyanlari (`Ajina.Banker`, `RoundRift`, soxta `TAKLIFNOMA` dropperlari) teskari muhandislik qilingan, va **aynan shu tahlil ilovadagi har bir detektorning asosi** bo'lgan. Skaner haqiqiy namunalardan topilgan aniq hash'lar, sertifikatlar, paket nomlari va chetlab o'tish hiylalarini ichiga jo'natadi.

> [`analysis/`](analysis/) papkasidagi zararli dastur — bu aynan antivirus to'sib qoladigan narsa.

---

## ✨ Asosiy imkoniyatlar

- **Qurilmada ishlaydi, skanlash uchun internet shart emas** — hukm chiqarish mantig'i mahalliy ishlaydi; yagona tashqi kutubxona — OkHttp.
- **Hech qachon yolg'on "xavfsiz"** — o'qib bo'lmaydigan fayllar va skan xatolari *shubhali* deb baholanadi, hech qachon *xavfsiz* emas. Noto'g'ri "xavfsiz" hukmi — mumkin bo'lgan eng yomon xato hisoblanadi.
- **ZIP-shifrlash hiylasini yengadi** — ZIP'ning ham lokal, ham markaziy katalog sarlavhalarini to'g'ridan-to'g'ri o'qib, shifrlash bayrog'ini topadi. Android bunday APK'ni o'rnatadi, lekin oddiy `ZipFile` o'qiy olmaydi (`Ajina.Banker` / `TAKLIFNOMA` klassik hiylasi).
- **24/7 real vaqt himoyasi** — fon xizmati Downloads / Telegram / WhatsApp / Bluetooth papkalarini kuzatadi va yangi APK'ni ~1 soniyada skanlaydi, hatto ekran qulflangan bo'lsa ham.
- **Fishing-ikona aniqlash** — perceptual aHash yordamida ilova ikonasini haqiqiy bank/messenjer ikonalari (Click, Payme, Uzcard, Telegram…) bilan solishtirib, soxtalashtirishni tutadi.
- **O'z-o'zini himoya va karantin** — buzilish/root/Frida/emulyator tekshiruvlari (release build'da) hamda zararli APK'lar o'rnatib bo'lmaydigan ichki karantinga olinadi (7 kun davomida tiklash mumkin).
- **Ikkita ixtiyoriy backend** — shaxsiy Telegram buyruq paneli va jonli Vercel + Supabase bulut (monitoring xaritasi, statistika, tahdid oqimi).
- **Uzbekcha interfeys** — har bir foydalanuvchiga ko'rinadigan matn, bildirishnoma va ogohlantirish o'zbek tilida.

---

## 🔬 Aniqlash dvigateli (detection engine)

`ApkScanner.scan()` qatlamli quvur (pipeline) sifatida ishlaydi: qattiq signal topilsa darhol to'xtaydi, aks holda xavf ballini to'playdi. Har bir analizator alohida himoyalangan — bittasining xatosi butun skanni to'xtatib qo'yolmaydi.

| Qatlam | Analizator | Nimani tutadi |
|---|---|---|
| **Qattiq signallar** (darhol hukm) | `MaliciousHashes` · `MaliciousCerts` · `MaliciousPackages` · `ThreatDb` | Ma'lum namuna / sertifikat / paket qora ro'yxati |
| | `ZipEncryptionDetector` | ZIP-shifrlash orqali antivirusni chetlab o'tish |
| | `IconImpersonationDetector` | Brendlar ikonasini soxtalashtirish (fishing) |
| | `DropperDetector` | Yashirilgan APK/DEX/ELF + yuqori entropiyali yuklamalar |
| **Ball to'plash** | `ManifestAnalyzer` | Accessibility / device-admin / himoyasiz eksport komponentlar |
| | `PermissionCombos` | Xavfli ruxsat **kombinatsiyalari** (OTP-grabber, overlay banker, botnet…) |
| | `DexPatternAnalyzer` | DexClassLoader, refleksiya, SMS API'lari, packerlar |
| | `ObfuscatedSignatures` | XOR+Base64 ichida yashirilgan C2 / IOC markerlari |
| | `NativeLibAnalyzer` | Shubhali `.so` importlar + entropiya |
| | `FilenameHeuristic` | Aldov nomlar, qo'sh kengaytma, typosquat, homoglif |
| **Noto'g'ri ishlashdan himoya** | `AppReputation` | Tasdiqlangan ishlab chiqaruvchi (paket **+** mos sertifikat) faqat *yumshoq* signallarni bo'g'adi — qattiq signallar baribir ishlaydi |

---

## 🏗️ Arxitektura

```
                          ┌──────────────────────────┐
   APK keladi   ───────▶  │   ProtectionService      │  24/7 fon kuzatuvchisi
 (Telegram/WhatsApp)      │   + MultiPathFileObserver │  + 1s tezkor skan tsikli
                          └────────────┬─────────────┘
                                       ▼
                          ┌──────────────────────────┐
                          │      ApkScanner.scan()    │  qatlamli analizatorlar → hukm
                          └────────────┬─────────────┘
                  XAVFSIZ / SHUBHALI / XAVFLI
                                       ▼
        ┌──────────────┬──────────────┴───────────────┬──────────────┐
        ▼              ▼                               ▼              ▼
   Karantin      AutoScanActivity                Telegram        ☁️ Bulut
   (.quar, 7k)   (jonli natija oynasi)          (shaxsiy bot)   (Vercel + Supabase)
                                                                  xarita · stat · oqim
```

### Backendlar (ikkalasi ham ixtiyoriy, fork'larda o'chiq)
- **Telegram buyruq paneli** — telefon Bot API'ni o'zi long-poll qiladi (`TelegramBot` + `CommandRouter`); ya'ni qurilmaning o'zi bot serveri. Egasining `chat_id`'si **va** raqamli user-id'si oq ro'yxatda.
- **Bulut** ([`ApkGuard/cloud/`](ApkGuard/cloud/)) — Vercel serverless funksiyalari + Supabase ustidagi Vite + React SPA; `kiberqalqon-cloud.vercel.app` manzilida joylashtirilgan. Ilova anonim, tuzilgan telemetriya yuboradi (`CloudTelemetry`), shunda markaziy panel xaritasi va statistikasi haqiqiy qurilma ma'lumotlari bilan to'ladi. Kirish modeli: **egasi** (master kalit + ixtiyoriy TOTP) + **bitta cheklangan admin** (ko'rish / eksport / e'lon) — rollar yo'q.

---

## 🚀 Yig'ish va ishga tushirish

> **Asboblar:** JDK 17, Android SDK 34, Kotlin. `ApkGuard/` papkasidan yig'iladi.

```powershell
cd ApkGuard
.\gradlew.bat assembleDebug
# natija: app\build\outputs\apk\debug\kiberqalqon-<epoch-millis>-debug.apk
```

Yoki ildizdagi `build-apk.bat` / `build-apk.ps1` — yig'adi va APK'ni vaqt tamg'asi bilan
`builds/` papkasiga nusxalaydi.

- `minSdk 24`, `target/compile 34`, ViewBinding + BuildConfig yoqilgan.
- APK fayl nomida `System.currentTimeMillis()` ataylab turadi (Windows Defender yangi yig'ilgan APK'ni bir muddat bloklaydi — har yig'ish unikal nom oladi).
- **Release imzo** gitignore qilingan `keystore.properties`'dan o'qiladi; bo'lmasa build imzosiz bo'ladi (o'z-o'zini himoya faqat release'da ishlaydi va debug'da o'tkazib yuboriladi).
- Bulut / community / qurilma sirlari gitignore qilingan `local.properties` → `BuildConfig` orqali keladi. Standart holatda bo'sh, shuning uchun fork'lar bu funksiyalar o'chgan holda yig'iladi.

### Unit testlarni ishga tushirish
```powershell
.\gradlew.bat testDebugUnitTest    # sof funksiyalar uchun JUnit4 testlari (Robolectric'siz)
```

---

## 🧱 Texnologiyalar

| | |
|---|---|
| **Ilova** | Kotlin · AndroidX · WorkManager · OkHttp 4.12 · ViewBinding |
| **Bulut** | TypeScript · Vite + React 18 · Vercel serverless · Supabase (Postgres + Storage) · Leaflet |
| **Asboblar** | python-telegram-bot · `jadx` · `apktool` (tahlil uchun ichiga qo'shilgan) |

---

## 📁 Repozitoriy tuzilishi

```
KiberQalqon/
├── ApkGuard/              # antivirus ilova (paket com.kiberqalqon)
│   ├── app/src/main/...   # ~89 Kotlin fayl: skaner, analizatorlar, workerlar, UI
│   ├── brand/             # logotip, mockup, Play Store ikonasi
│   ├── cloud/             # Vercel + Supabase backend (TS) + React SPA panel
│   └── gradlew(.bat)      # shu yerdan yig'iladi
├── analysis/              # zararli dastur tahlili: deshifrlash skriptlari, IOC'lar, hisobotlar
├── telegram_bot/          # yordamchi Python namuna-qabul boti
├── threat_intel/          # hash / sertifikat bazalari
├── pitch/                 # taqdimot sahifalari (HTML / PDF)
├── scripts/               # yordamchi build skriptlari
├── tools/                 # tahlil asboblari: apk_analyzer.py, unpack_apk.py, apktool, jadx, jdk
├── docs/
│   ├── audit/             # xavfsizlik auditlari
│   ├── build/             # yig'ish va asbob o'rnatish yo'riqnomalari
│   └── virus/             # virus tahlil hisobotlari (RU / UZ)
├── builds/                # yig'ilgan debug APK'lar          ← git'ga tushmaydi
├── malware/               # KARANTIN                          ← git'ga tushmaydi
│   ├── samples/           # jonli zararli APK namunalari
│   ├── unpacked/          # ochilgan / dekodlangan chiqishlar
│   └── competitor/        # raqobatchi ilova tahlili
└── reports/               # skan va ochish hisobotlari
```

> **Karantin qoidasi:** `malware/` va `builds/` butunlay `.gitignore`'da — jonli zararli
> dastur GitHub qoidalarini buzadi. Namunalarni faqat `malware/samples/` ichida saqlang.

---

## ⚖️ Ko'lam va axloq

Bu — **ruxsat etilgan, shaxsiy himoya-xavfsizlik loyihasi**. Egasi o'zbek foydalanuvchilariga qaratilgan haqiqiy zararli dasturlarni **aynan ularga qarshi himoya yaratish maqsadida** tahlil qiladi. Repozitoriyda aniqlash mantig'i, teskari muhandislik yozuvlari va topilgan IOC'lar **faqat himoya maqsadida** turibdi — bu hujum quroli emas va bo'lmasligi kerak.

---

<div align="center">

Muallif: **Muhammadali** · Navoiy, O'zbekiston 🇺🇿

</div>
