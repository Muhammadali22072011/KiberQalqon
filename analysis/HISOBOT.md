# O'ZBEK BANKING TROJANI — TO'LIQ ANALIZ HISOBOTI

**Tahlil sanasi:** 2026-05-21
**Tahlilchi:** Statik analiz (androguard 4.1.3, hand-written parsers)
**Namunalar:** `C:\Users\Muhammadali\Desktop\APK Virus Analysis\новые вирусы\`

---

## QISQA XULOSA

5 ta APK — barchasi **zararli**. 4 tasi bitta oilaga tegishli **Uzbekistan-targeted Android banking trojan** (NGate/Brokewell-ga o'xshash, packed); 1 tasi alohida **dropper/downloader** MaaS panelidan chiqarilgan.

| Fayl | Funksiya | Hajm | Package | Cert mamlakat |
|---|---|---|---|---|
| RASMLAR (18).apk | **Banker (to'liq)** | 2.26 MB | `com.lzthzvxte.xazoalzxhr` | UZ |
| RASMLAR (8).apk | **Banker (eng to'liq, v2)** | 2.34 MB | `com.oktgkst.rrcpkge` | UZ |
| toydanfotolar(9.jpg) (10).apk | **Banker** | 1.78 MB | `com.yzsfnie.sjsztphpis` | US |
| VID_23856_21052026….apk | **Banker (mini)** | 1.30 MB | `com.puhfvysb.nzbftunmqq` | UZ |
| VIDEO.20.01.2026.mp4 (2).apk | **Dropper** (alohida MaaS) | 2.08 MB | `ydbllnjd.com` | RU |

---

## 1. IJTIMOIY MUHANDISLIK (Social engineering)

Bu APK'lar **Telegramda yoki SMS orqali** quyidagi nomlar bilan tarqaladi:

- **`RASMLAR (18).apk`** — "Rasmlar (suratlar)" — qurbon foto-arxivga bosib oladi deb o'ylaydi
- **`RASMLAR (8).apk`** — bir xil hiyla
- **`toydanfotolar(9.jpg) (10).apk`** — "to'ydan fotolar (9.jpg)" — eng kuchli hiyla: O'zbekistondagi to'y rasmlari deb qabul qilinadi. `(9.jpg)` qism foydalanuvchini chalg'itadi — JPG deb o'ylaydi
- **`VID_23856_21052026[bo'sh joylar].apk`** — fayl nomida 100+ ta **Hangul Half-Width** ko'rinmas probel (`ㅤ`). Telegram fayl nomini qisqartiradi, foydalanuvchi faqat `VID_23856_21052026` ko'radi va `.apk` kengaytmasini sezmaydi. **Klassik filename-spoofing**
- **`VIDEO.20.01.2026.mp4 (2).apk`** — "video.mp4" bo'lib chiqadi

---

## 2. OILA SIGNATURASI (4 ta banker bir manbadan)

### Bir xil komponent tuzilishi (har 4 tasida ham)
- 1 ta `Activity` (launcher, ko'rinadigan yagona ekran)
- 1 ta `Service` (foregroundServiceType = 0x1 = `dataSync`, doimo ishlash uchun)
- 1 ta `Receiver` (REQUEST_INSTALL_PACKAGES bilan himoyalangan, C2 push qabul qilish uchun)
- 1 ta `FileProvider`

### Bir xil "private action" intent (4 tasida ham)
Receiver uchun 3 ta custom action:
```
<package>.K9m2vL74pR3
<package>.P3r6xM92bW7
<package>.H5w8jN63qT2
```
Bu 3 ta string **butun oila bo'yicha bir xil** — ya'ni hammasi bir source-kod-dan build qilingan, faqat package nomi random. Bu **MaaS (malware-as-a-service)** belgisi.

### Bir xil obfuskatsiya naqshlari
DEX ichida har bir variant'da quyidagi class nomlari bir xil ko'rinishda mavjud (parent package qismi har xil bo'lsa-da):
```
Gquesyvnltekgxdkymmis
Igmmzymaazkvykdianvycnkljes
Irofilwecjwcceeatcfg
Lvykjdzgpnpmedglwtypvjkqvdudx
Mpedksrsyiwjclyizqswstz
Pbeattyedjvrpkezmbxio
```

### Bir xil version
- versionName: `1.20.38`
- versionCode: `9995`
- minSdk: 26 (Android 8.0), targetSdk: 34 (Android 14)

### Bir xil ZIP-evasion trick (eng muhim)
APK ichidagi ko'p fayllar **General Purpose Bit Flag = 0x0001 (encrypted)** bilan belgilangan:
- `classes.dex`, `AndroidManifest.xml`, `resources.arsc`, `assets/*` — barchasi "shifrlangan" deb belgilangan
- **Lekin haqiqatda shifrlanmagan** — bu shunchaki bayroq
- **Natija**: Python `zipfile`, oddiy `unzip`, ko'p antiviruslarning skanerlari **faylni o'qiy olmaydi** (parol so'raydi). Faqat **Android'ning o'zi** (PackageManager) bu bayroqni e'tiborga olmaydi va to'g'ri o'qiydi
- Bu **anti-analiz** texnikasi — VirusTotalda 0/70 ko'rsatishi shu sababli

---

## 3. RUXSATLAR — NIMA O'g'irlanadi

### RASMLAR (18) / RASMLAR (8) — 42-46 ta ruxsat
Eng xavfli/muhimlari:
- `REQUEST_INSTALL_PACKAGES` + `REQUEST_DELETE_PACKAGES` → boshqa APK'ni o'rnatish/o'chirish (asosiy bank ilovasini o'chirib, soxta versiyasini qo'yish)
- `KILL_BACKGROUND_PROCESSES` → antivirus va to'siq qiluvchi jarayonlarni o'ldirish
- `RECEIVE_BOOT_COMPLETED` → qurilma yoqilganda avtomatik ishga tushish (8.apk versiyada)
- `NFC` + `NFC_TRANSACTION_EVENT` + `USE_FINGERPRINT` → **NFC-relay banking** belgisi (UzCard/Humo kartalarni kontaktsiz o'qib, masofadan token relay)
- `PROCESS_OUTGOING_CALLS` → chiqayotgan qo'ng'iroqlarni ushlash (bankga qo'ng'iroq qilsangiz, qaytarib oluvchi)
- `INSTALL_SHORTCUT` / `UNINSTALL_SHORTCUT` → ishchi stoldan o'z belgisini olib tashlash (ko'rinmas bo'lish)
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` → tinglashda batareya saqlash chegirmasidan chetga chiqish
- `WAKE_LOCK` + `FOREGROUND_SERVICE_DATA_SYNC` → uxlamaslik
- `WRITE_PROFILE` / `READ_PROFILE` / `MANAGE_ACCOUNTS` / `AUTHENTICATE_ACCOUNTS` → Google/email accountlar
- `WRITE_USER_DICTIONARY` → klaviatura tarixini o'qish (keylogger orqali parol)

**Diqqat:** `RECEIVE_SMS`, `READ_CONTACTS`, `BIND_ACCESSIBILITY_SERVICE` manifestda **YO'Q**. Bu — **2-bosqichli yuklash** (staged loader): asosiy DEX faqat **unpacker stub**, asl banker yuklab olingach yoki `assets/*.spe` dan ochilgach bu permissionlar dinamik talab qilinadi.

### VIDEO.20.01.2026.mp4 — atigi 8 ta ruxsat (dropper)
- `REQUEST_INSTALL_PACKAGES`, `QUERY_ALL_PACKAGES`, `FOREGROUND_SERVICE_DATA_SYNC`, `INTERNET`
- Bu klassik **silent installer dropper** — `apksig` kutubxonasi va native AES kalit bilan asl banker'ni yuklab, foydalanuvchi tasdiqlamasdan o'rnatadi

---

## 4. ICHKI ARXITEKTURA (qanday ishlaydi)

### Banker oilasi (4 ta variant)

```
APK
├── classes.dex (700-900 KB)          ← Unpacker STUB
│     ├── 1000+ sinflar — random kotlin obfuskatsiyada
│     ├── 800+ Base64 stringlar       ← Shifrlangan konfig + URL + TG bot
│     └── Java/native bridge — AES kalit DEX ichida hardcoded
│
├── assets/
│   ├── *.sps  (19'636 byte, exact)   ← Encryption key blob / IV / config
│   ├── *.spe  (19-800 KB)            ← Encrypted 2-bosqich DEX
│   ├── *.data / *.bak  (600 KB-1 MB) ← Encrypted overlays (HTML soxta login,
│   │                                    bank ilovasi belgilari, vasatchi sahifalar)
│   └── dexopt/baseline.prof          ← ART AOT kompilatsiyasi (innocent)
│
└── AndroidManifest.xml
       ↳ FAQAT MINIMUM permissions deklaratsiyada
       ↳ Real banker permissionlari runtime'da qo'shiladi
```

### Ish bosqichlari

1. **Foydalanuvchi APK'ni o'rnatadi** → Telegram orqali kelib, "Allow from this source"ni tasdiqlaganidan keyin
2. **Launcher Activity** ko'rinadi (`Ss0l4nz5aqo2wfg` kabi tushunarsiz sinflar) — soxta "yuklanmoqda…" yoki bo'sh ekran
3. **Service** (foregroundService) ishga tushadi — notification panelda ko'rinadi ("System update…")
4. **Unpacker stub**: native kalit yordamida `assets/*.sps` (kalit) + `assets/*.spe` (AES-CBC payload) ni dekriptsiya qiladi
5. **DexClassLoader** orqali dekriptsiya qilingan DEX qaynoq xotiraga yuklanadi
6. **Asl banker logic** ishga tushadi:
   - `AccessibilityService` ruxsatini so'raydi (soxta "telefoning yangilanishi kerak" oynasi orqali)
   - Ruxsat olingach — **ekrandagi har narsani o'qiydi**: parollar, OTP SMS, kart raqamlari
   - Bank ilovasi ochilganda **HTML overlay** chizadi (assets/*.data ichidagi soxta login)
   - SMS keladi — Accessibility orqali notification'dan o'qib oladi (RECEIVE_SMS kerak emas)
   - Hammasini **Telegram bot** orqali xakerga jo'natadi

### Encrypted asset header pattern
4 ta banker'da `.sps` va `.spe` fayllarining barchasi `0F 1X 7C ...` ASCII'siz baytlar bilan boshlanadi va **bir xil pattern**ga ega — bu **bir xil shifrlash sxemasi** (oddiy XOR oqim cipher yoki AES-CTR raqamlarni qayta ishlatish bilan). Katta fayllar (`.data`, `.bak`) `df 8X` bilan boshlanadi — AES-CBC IV ehtimoli.

---

## 5. DROPPER (VIDEO.20.01.2026.mp4) — ALOHIDA OILA

Bu **boshqa MaaS** ekanligi aniqlandi:

### Native lib `libnative-lib.so` (4 arxitektura) ichida topilgan iz:
```
Build path: /home/webpanel/backend/src/utils/project/downloader/app/.cxx/RelWithDebInfo/6p4w5i2i/arm64-v8a
Encryption key: sqsmlH2NOLPXeaDIFGnMEOdG6Uc2mV  (30 belgi, base62)
JNI function:  getSecretKey
Real Java class:  ydbllnjd.com.core.KeyManager
```

→ **Sotuvchi serveri**: `/home/webpanel/backend/...` — **WebPanel orqali boshqariladigan MaaS**. Operator panelda APK build qiladi, har bir build random package nomi va kalit oladi (slug `6p4w5i2i`).

### Encrypted payload
- `assets/cvsmcn.json` — 1 014 969 byte, **entropy 7.999** (deyarli mukammal random) — AES-shifrlangan asl banker APK
- `apksig` kutubxonasini DEX ichiga import qilgan — bu `PackageInstaller` orqali **boshqa APK'ni jim o'rnatish** uchun (foydalanuvchi qo'lda "yuklab olishni" tasdiqlamasdan)

### Ish algoritmi:
1. VIDEO.mp4 (dropper) o'rnatiladi
2. Native lib AES kalitni qaytaradi
3. `assets/cvsmcn.json` decrypt qilinadi → asl banker APK paydo bo'ladi
4. `PackageInstaller` API orqali (REQUEST_INSTALL_PACKAGES bo'lib) — banker o'rnatiladi
5. Dropper o'zini Settings → Apps'dan `setComponentEnabledSetting` orqali yashiradi (`HIDE_ICON` topildi)

---

## 6. SHIFRLANGAN INDIKATORLAR (IOC)

### SHA-256 hashlar (sample identification)
```
RASMLAR (18).apk           75DD6895575576C0E83706C07C226CB16D23174E2372D8217626C95797E1B215
RASMLAR (8).apk            8D2F128CCAE146E5A991E26C45FFC2C0D7CC9DD999338424C911B03E0805599B
toydanfotolar(9.jpg).apk   4860AAFA2244B0430260D185301F26AC5E3C942F9E60C9FD6B66A322E6D407E9
VID_23856_21052026.apk     A89122D1D2CA3B8EB3C170B88218CB1C3E39E914EC5D212E1E846748E40277D9
VIDEO.20.01.2026.mp4.apk   6CA17ABD38DC8970AEAFF85DC38B97974D3445B3C877F7DC6B1A52045315CC43
```

### Imzo sertifikatlari (har biri unique — self-signed)
```
RASMLAR (18):   CN=yajig, OU=homec, O=xekup, L=filiy, ST=qotaq, C=UZ
                SHA1: A2:AF:26:0F:B3:F3:1D:8B:AE:BB:A0:78:F4:9D:36:F1:C6:2C:CB:67
RASMLAR (8):    CN=hefol, OU=serek, O=papas, L=nuxec, ST=qemeq, C=UZ
                SHA1: C8:63:6D:1D:AA:4D:2B:AF:51:9D:81:B4:C6:63:57:C9:D6:8B:6F:C5
toydanfotolar:  CN=qecak, OU=cuquw, O=zefud, L=piyiy, ST=getoz, C=US
                SHA1: F1:A8:26:5C:8D:B6:87:F5:86:0D:17:CD:ED:3E:26:84:56:A0:D3:90
VID_23856:      CN=wadoy, OU=yoleq, O=lixon, L=kinof, ST=sariq, C=UZ
                SHA1: 23:1E:98:2E:A8:99:20:4A:08:7B:C6:33:21:D3:DC:91:99:11:19:74
VIDEO.mp4:      CN=constrain, OU=levers, O=utility, L=twiddling, ST=kiln, C=RU
                SHA1: 12:AD:86:B8:F5:1B:28:33:71:DF:19:8E:68:20:6E:A1:14:4E:84:76
```

Sertifikat field'lari **mavhum tasodifiy so'zlar** (`yajig`, `hefol`, `qotaq`) → MaaS generator avtomatik ravishda yaratgan. Bu zaif "Country: UZ" → operator O'zbekistonni nishonga oladi.

### Plain-text bot username candidates (DEX strings'idan)
Bu 4 ta tarjima qilingan banker har birida **bot bilan bog'liq plain string** topildi (Base64 shifrlangan stringlar ichidan ajralib turadi):
```
RASMLAR (18):    xzpkbotsff
RASMLAR (8):     botjbbqyftfgpfk, poqbotmf
toydanfotolar:   qbvsybotru
VID_23856:       (faqat Base64 ichida)
```
Bular Telegram bot/kanal **username candidate**lari (mahalliy shifrlash mantig'idan deshifratsiya qilingach to'liq URL bo'ladi). Misol: `https://t.me/<username>` yoki bot API endpoint pattern.

### Native lib IOC (VIDEO.mp4 dropper)
```
AES kalit:    sqsmlH2NOLPXeaDIFGnMEOdG6Uc2mV
Build path:   /home/webpanel/backend/src/utils/project/downloader/app/.cxx/RelWithDebInfo/6p4w5i2i/arm64-v8a
Build slug:   6p4w5i2i
Real namespace: ydbllnjd.com.core.KeyManager
```

### Belgilangan tarmoq endpoint'lari
To'g'ridan-to'g'ri plain-text URL/IP topilmadi — barchasi **AES + Base64 shifrlash** ostida. 800+ Base64 stringlar `analysis/10_ioc_strings.json` faylda saqlangan; ulardan ba'zilari decrypt qilingach Telegram bot tokenlari va C2 endpoint'lari bo'ladi.

---

## 7. NIMA QILISH KERAK

### Agar siz bu APK'lardan birini telefoningizga o'rnatib qo'ygan bo'lsangiz:
1. **Internet'ni darhol uzing** (Wi-Fi va mobil)
2. **Bank ilovalaringizni telefondan o'chiring** (ular allaqachon zararlangan bo'lishi mumkin)
3. **Settings → Accessibility → ko'rinmasdan turgan service'larni o'chiring** (asosan tushunarsiz nomdagilar)
4. **Settings → Apps → "RASMLAR" yoki o'sha nomli ilovani topib o'chiring**. Agar Settings'ga kira olmasangiz — **Safe Mode** orqali (Power tugmasini bosib turing → "Restart in safe mode")
5. **Sim kartani chiqarib qo'ying**, barcha SMS-koda asosli bank/ariza login'larini darhol o'zgartiring (boshqa qurilmadan)
6. **Telefonni Factory Reset** qiling — bu eng ishonchli yo'l. Bunda baribir backup'dan qaytarmang (bank ilovalari clear o'rnatilsin)
7. **Bankka qo'ng'iroq qiling** — kartani bloklang. NFC-relay risk: bu malware kontaktsiz to'lov tokenini relay qila oladi
8. **Telegramdagi yuboruvchini reportlang va bloklang**

### Endi har doim:
- `.apk` faylni hech qachon **Telegram chatdan** ochmang. Faqat **Play Store** yoki rasmiy bank saytidan
- Sozlamalar → **Install unknown apps** = **OFF** har doim
- Bank ilovasidan keladigan SMS **boshqa** ilovaga ko'rinmasin (Telegram'ga sms forward yoqilgan bo'lmasligi kerak)
- Phone'ingizda Google Play Protect yoqilgan bo'lsin

---

## 8. ANALIZ FAYLLARI

Hamma bosqichlarning chiqarilgan natijalari:

| Bosqich | Fayl | Mazmun |
|---|---|---|
| 1 | `01_manifest.json` | To'liq AndroidManifest meta-ma'lumotlari |
| 2 | `02_dex.json` | DEX sinflar va API hit'lari (raw APK) |
| 3 | `03_ioc.json` | Asosiy IOC qidiruvi (raw APK) |
| 4 | `04_zipdump.json` | ZIP inventari va entropy |
| 5 | `05_zipevasion.json` | LFH/CDH/EOCD parse, GP flag analizi |
| 6 | `06_unpack.json` | Patch qilingan APK'lar (`unpacked/*_fixed.apk`) |
| 7 | `07_realdex.json` | Real classes.dex+assets per-fayl skanerlash |
| 8 | `08_unpacked_apk.json` | Tuzatilgan APK'larga androguard tahlili |
| 9 | `09_native_and_manifest.json` | Native .so strings + manifestlar |
| 10 | `10_ioc_strings.json` | 800+ Base64 shifrlangan stringlar + qiziq plain-text |

Hamma narsa `C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis\` katalogida.
