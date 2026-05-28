# 🎯 ЯКУНИЙ ТАХЛИЛ — ЎЗБЕК БАНК ТРОЯНИ (To'liq IOC)

**Тахлил санаси**: 2026-05-21
**Тахлил тури**: Тўлиқ статик reverse engineering
**Намуналар**: 5 та APK файл
**Топилмалар**: 100% статик ишда оширилди — sandbox ишлатмасдан

---

## 🏷️ МAЛВАR ОИЛАСИ — ТАСДИҚЛАНГАН (public threat intel билан)

### Group-IB (Октябр 2025) рапорти бўйича солиштириш:

| Менинг намунам | Public family | Маьно |
|---|---|---|
| **VIDEO.20.01.2026.mp4.apk** | **RoundRift** (Group-IB) | AES-based dropper; native lib билан; PackageInstaller silent installation; ассет фойл — `cvsmcn.json` (RoundRift'да `[name].key` шаклида) |
| **RASMLAR/toydanfotolar/VID_23856** (4 та) | **Ajina.Banker** (новейшая variantи) | SMS/banking stealer; Telegram orqali tarqalади (1,400+ варианты); МCC/MNC/SIM data, OTP intercept |

**Group-IB ҳисоботидан**: 
> "RoundRift relies on a straightforward AES routine with a static 16-byte value key, embedded directly as SecretKeySpec, not derived or transformed."  
✅ Менинг VIDEO.mp4'да аниқ шу: native lib'да `_ZL6encKey` + `getSecretKey` + AES Cipher.

> "Automated build pipeline that generates new samples with unique IoCs"  
✅ Менинг 5 та намуна бир хил структура, лекин ҳар бирида ўзига хос калит ва пакет номи.

> "Ajina.Banker collects SIM data (MCC, MNC, SPN), installed financial apps, and SMS content, transmitting to C2 servers using AES/GCM/NoPadding encryption over raw TCP"  
⚠ Менинг 4 банкер: XOR+Base64 ишлатади (Cipher emas) — bu **2026 yangi avlod** (Group-IB ҳисоботидан кейинги).

---

---

## 🔴 ЭНГ МУҲИМ — ТОПИЛГАН C2 СЕРВЕРЛАР

### VIDEO.20.01.2026.mp4 (асосий dropper) ичида плейн топилди:

| IOC | Қиймат |
|---|---|
| **C2 домен (асосий)** | `elrxzx.com` |
| **Биринчи юклаб олиш URL'и** | `https://ilovekkksfm.com/video/dropper.html` |
| **Real internal namespace** | `ydbllnjd.com.core.KeyManager` |
| **MaaS оператор build path** | `/home/webpanel/backend/src/utils/project/downloader/app/.cxx/RelWithDebInfo/6p4w5i2i/arm64-v8a` |

**Қандай топилди:** VIDEO.mp4'нинг `resources.arsc` (string pool) ичида **41 та Base64-кодлами стринг** яширилган эди. Уларнинг **15 таси оддий Base64 декод қилинди**, **26 таси XOR(`sqsmlH2NOLPXeaDIFGnMEOdG6Uc2mVin`)** билан очилди.

---

## 🔑 КАЛИТЛАР (статик'да олинди — биринчи марта!)

### 4 та банкер учун Java-side XOR калитлар

| Банкер | Base XOR key (UTF-8) | Top XOR key (UTF-8) |
|---|---|---|
| **RASMLAR (18)** | `JYTAs0m31lxvwkQE42Y10Ktm` (24б) | `3183701586F97GhYNSURErMMwPeAS33H` (32б) |
| **RASMLAR (8)** | `UmmYqVRrK46tVxAG5PDVUI5rSF72a6p1` (32б) | `551712693drkGqsgO05tb31vbvZGkA0P` (32б) |
| **toydanfotolar(9.jpg)** | `AVbmxP9CNlQZRrzvnJhFw92n` (24б) | `347692886QFqc74H0w07DWz51sMHjqB4` (32б) |
| **VID_23856_21052026** | `C49yA8hXYvs5cz7wGeEX7cQLTzYn` (28б) | `358200732kyGoz80aYtUropyXbvrXv9h` (32б) |

### VIDEO.mp4 dropper калити (native libдан)

```
Калит: sqsmlH2NOLPXeaDIFGnMEOdG6Uc2mVin   (32 байт UTF-8)
Турли алгоритм: XOR (Base64-encoded strings'да)
Native символ: _ZL6encKey + _ZL12getSecretKey
Real Java class: ydbllnjd.com.core.KeyManager.getSecretKey()
```

### Декрипт алгоритм (4-та банкер учун)

```python
import base64
def decrypt(b64_string, xor_key_bytes):
    raw = base64.b64decode(b64_string)
    return bytes(b ^ xor_key_bytes[i % len(xor_key_bytes)] 
                 for i, b in enumerate(raw)).decode("utf-8")

# Top key олинади:
top_key = decrypt("eWhsckQAXAYJWj5PQCw5HHphDGN1OTkgPQkxACADXns=", 
                  base_key).encode("utf-8")
# Стринглар очилади:
plaintext = decrypt(encrypted_b64_string, top_key)
```

---

## 📋 VIDEO.mp4 DROPPER'нинг 41 та плейн стринги (тўлиқ)

### Жуъаk:

**Уч тилда сохта "Yangilanish/Update" хабарлари:**

🇺🇿 **Ўзбек:**
- "Yangilanish mavjud"
- "Ilovadan foydalanish uchun yangilanishni o'rnatishingiz kerak"
- "Yangilangan sana: 31-dekabr, 2025-yil" / "17-sentabr, 2025-yil"
- "Hajmi: 1.6 MB"
- "O'rnatish" / "Yangilash" / "Batafsil" / "Nima yangi"
- "Qayta urinish" / "Ilovani yuklash"
- "O'rnatish uchun ruxsat kerak"
- "Internet ulanishini tekshiring, agar VPN yoqilgan bo'lsa, uni o'chirib ko'ring"

🇷🇺 **Русский:**
- "Доступно обновление"
- "Чтобы использовать приложение, необходимо установить обновление"
- "Дата обновления: 31 декабря 2025 г."
- "Размер: 1.6 МБ" / "Установить" / "Обновить" / "Подробнее"
- "Что нового" / "Повторить" / "Загрузка приложения"
- "Требуется разрешение на установку"
- "Проверьте подключение к интернету, попробуйте выключить ВПН если он включен"

🇫🇷 **Français:**
- "Mise à jour disponible"
- "Pour utiliser l'application, vous devez installer la mise à jour"
- "Date de mise à jour : 31 décembre 2025"
- "Taille : 1.6 Mo" / "Installer" / "Mettre à jour"
- "Plus d'infos" / "Nouveautés" / "Réessayer"
- "Téléchargement de l'application"
- "Autorisation d'installation requise"
- "Vérifiez votre connexion Internet, essayez de désactiver le VPN s'il est activé"

**Operational strings:**
- `cvsmcn.json` (асосий шифрланган payload фойли)
- `gallery` (категория)
- `elrxzx.com` 🔴
- `https://ilovekkksfm.com/video/dropper.html` 🔴

---

## 🚫 АНТИ-АНАЛИЗ ва АНТИ-ТЕБА топилди

Барча 4 та банкер ичида:

### Anti-Frida (DEX'дан XOR декрипт билан очилди):
- `/data/local/tmp/frida-server`
- `/sdcard/frida-server`
- `/system/bin/frida-server`
- `/system/xbin/frida-server`
- `/system/lib/frida-gadget.so`
- `/system/lib64/frida-gadget.so`
- Stack frame `/proc/self/maps`'да `frida`, `gadget`, `substrate`, `xposed` сўзларни қидиради

### Anti-Magisk / Anti-Root:
- `com.topjohnwu.magisk` (Magisk пакет аниқлайди)

### Anti-VPN:
- `android.net.VpnService` — VPN ишлатилишини текширади
- HTML overlay'да "Disable VPN to continue" хабари

### ZIP-evasion:
- APK ичидаги ҳар бир file'нинг **GP Bit Flag = 0x01 (encrypted)** қилинган
- Лекин ҳақиқатан шифрланган emas — fақат флаг
- Натижада: Python `zipfile`, oddiy unzip, **антивируслар очиб ўҳқай олмайди**
- Android'нинг PackageManager бу флагни эътиборсиз қолдиради ва нормал ўрнатади

---

## 🎭 HTML OVERLAY ШАБЛОНЛАРИ (4 банкерда ҳам ўхшаш)

Ҳар банкер `assets/`да 2 та шифрланган HTML бор:
- `*.sps` (19,636 байт) — "Установка" / "O'rnatish" дераэа
- `*.spe` (23,706 байт) — "Доступно обновление" / "Yangilanish mavjud" дераэа

Шифр: **XOR with top_key** (банкер DEX'дан олинди)

JavaScript ичида:
```javascript
window.applyInstallScreenData(data)
window.applyUpdateDialogData(data)
window.AndroidBridge.onInfoClick()
window.AndroidBridge.onActionClick()
```

Бу WebView ↔ Java bridge орқали `onActionClick()` → банкер логикаси.

---

## 🧬 ОИЛА СИГНАТУРАСИ — 4 банкер бир MaaS'дан

### Бир хил структура:
- 1 та Activity (launcher), 1 та Service (foreground), 1 та Receiver
- Receiver actions ҳаммасида **бир хил**:
  - `<package>.K9m2vL74pR3`
  - `<package>.P3r6xM92bW7`
  - `<package>.H5w8jN63qT2`
- Version `1.20.38` / versionCode `9995`

### Бир хил обфускатсия методлари:
- `Gquesyvnltekgxdkymmis`
- `Igmmzymaazkvykdianvycnkljes`
- `Irofilwecjwcceeatcfg`
- `Lvykjdzgpnpmedglwtypvjkqvdudx`
- `Mpedksrsyiwjclyizqswstz`
- `Pbeattyedjvrpkezmbxio`
- `Cwirfqyahbjtyclklnanwcl` (асосий run-методи)

### Decrypt алгоритм: 2 даражали XOR+Base64

```
Lizbhposedczokv/ffpmuejysxtpa;->bxwmmwxospk(s)     ← base method
Lcvzyd/nnuynj;->ejpwqplfj(s)                       ← RASMLAR_8 variant
Lzsfniessjsztphp/sytchwgjrpro;->trtobhtalyz(s)     ← toydanfotolar variant
Luhfvys/fnzbf/unmqqniflp/tfrlkmqqxcn;->uzrkycnrf(s)← VID_23856 variant
```

---

## 🛡️ Имзо сертификатлари (Self-signed, MaaS-генератор)

```
RASMLAR (18):  CN=yajig, OU=homec, O=xekup, L=filiy, ST=qotaq, C=UZ
               SHA1: A2:AF:26:0F:B3:F3:1D:8B:AE:BB:A0:78:F4:9D:36:F1:C6:2C:CB:67

RASMLAR (8):   CN=hefol, OU=serek, O=papas, L=nuxec, ST=qemeq, C=UZ
               SHA1: C8:63:6D:1D:AA:4D:2B:AF:51:9D:81:B4:C6:63:57:C9:D6:8B:6F:C5

toydanfotolar: CN=qecak, OU=cuquw, O=zefud, L=piyiy, ST=getoz, C=US
               SHA1: F1:A8:26:5C:8D:B6:87:F5:86:0D:17:CD:ED:3E:26:84:56:A0:D3:90

VID_23856:     CN=wadoy, OU=yoleq, O=lixon, L=kinof, ST=sariq, C=UZ
               SHA1: 23:1E:98:2E:A8:99:20:4A:08:7B:C6:33:21:D3:DC:91:99:11:19:74

VIDEO.mp4:     CN=constrain, OU=levers, O=utility, L=twiddling, ST=kiln, C=RU
               SHA1: 12:AD:86:B8:F5:1B:28:33:71:DF:19:8E:68:20:6E:A1:14:4E:84:76
```

---

## 📂 ҲАМЯ ARTИФАКТЛАР

`C:\Users\Muhammadali\Desktop\APK Virus Analysis\analysis\` папкасида:

| Файл | Мазмун |
|---|---|
| `HISOBOT_FINAL.md` | Бу файл (тўлиқ натижа) |
| `01-32_*.py` + `*_run.txt` | 32 та таҳлил скрипти + натижалари |
| `27_pts_RASMLAR_18_fixed.txt` | 1310 та декриптланган банкер стринги |
| `27_pts_RASMLAR_8_fixed.txt` | 1538 та |
| `27_pts_toydanfotolar9jpg_10_fixed.txt` | 1565 та |
| `27_pts_VID_23856_*_fixed.txt` | 1563 та |
| `DECRYPTED_*_*.bin` | HTML overlay'лар (8 та сохта дераэа) |
| `unpacked/*_fixed.apk` | ZIP-evasion'дан тозаланган APK'лар |
| `unpacked/*/` | Барча extract қилинган файллар |

---

## 🎯 ЯКУНИЙ ИОC ХУЛОСА — ДАРХОЛ БЛОКЛАШ УЧУН

### Тармоқ IOC (URL/Host):
```
elrxzx.com
ilovekkksfm.com
ilovekkksfm.com/video/dropper.html
```

### Пакет номлари (блок қилиш учун):
```
com.lzthzvxte.xazoalzxhr        ← RASMLAR (18)
com.oktgkst.rrcpkge             ← RASMLAR (8)
com.yzsfnie.sjsztphpis          ← toydanfotolar
com.puhfvysb.nzbftunmqq         ← VID_23856
ydbllnjd.com                    ← VIDEO.20.01.2026.mp4 dropper
```

### SHA-256 файл хешлари:
```
75DD6895575576C0E83706C07C226CB16D23174E2372D8217626C95797E1B215  RASMLAR (18).apk
8D2F128CCAE146E5A991E26C45FFC2C0D7CC9DD999338424C911B03E0805599B  RASMLAR (8).apk
4860AAFA2244B0430260D185301F26AC5E3C942F9E60C9FD6B66A322E6D407E9  toydanfotolar(9.jpg).apk
A89122D1D2CA3B8EB3C170B88218CB1C3E39E914EC5D212E1E846748E40277D9  VID_23856_21052026.apk
6CA17ABD38DC8970AEAFF85DC38B97974D3445B3C877F7DC6B1A52045315CC43  VIDEO.20.01.2026.mp4.apk
```

### Cert SHA-1 (имзо хешлари):
```
A2AF260FB3F31D8BAEBBA078F49D36F1C62CCB67
C8636D1DAA4D2BAF519D81B4C66357C9D68B6FC5
F1A8265C8DB687F5860D17CDED3E268456A0D390
231E982EA899204A087BC63321D3DC9199111974
12AD86B8F51B283371DF198E68206EA1144E8476
```

---

## 🟢 НИМА ТОПИЛДИ vs НИМА ҚОЛДИ

### ✅ ТОПИЛДИ (статикда):
- **Реал C2 манзил**: `elrxzx.com`, `ilovekkksfm.com`
- **Биринчи URL**: `ilovekkksfm.com/video/dropper.html`
- **4 та банкер калитлари** (Base + Top XOR)
- **VIDEO.mp4 калити** (`sqsmlH2NOLPXeaDIFGnMEOdG6Uc2mVin`)
- **Шифрлаш алгоритми тўлиқ ажратилди** (XOR + Base64)
- **HTML overlay шаблонлари** (3 тилда)
- **MaaS оператор сервер йўли** (`/home/webpanel/backend/...`)
- **41 та плейн стринг** (VIDEO.mp4'дан)
- **4,600+ та декриптланган стринг** (4 банкер'дан)
- **Anti-Frida, Anti-Magisk, Anti-VPN, ZIP-evasion** аниқланди

### ⚠️ ҲАЛИ ОЧИЛМАГАН (динамик керак):
- Banker'ларнинг катта `.spe`/`.data`/`.bak` (700KB-1MB) — **AES native key** билан (`cvsmcn.json` ҳам)
- Telegram bot token (агар мавжуд бўлса) — балки `elrxzx.com` орқали HTTPни ишлатади, Telegram эмас
- `$C2/` placeholder'нинг runtime substitution қиймати

### Чека (catta encrypted assets'и):
- Бу файллар **АES** билан, *raw `encKey` symbol* native lib'дан керак
- Pyelftools symbol topa olmadi — embedded бутун ҳолатдаги бўлиши керак
- Group-IB ҳам шу нарсани айтган: "static 16-byte SecretKeySpec, embedded in Java" — лекин менинг банкерлар *Java*'да Cipher йўқ, demak бу версияда **encryption йўқ катта файлларга**
- Эҳтимолан бу файллар — **decoy/filler** (антивирус ўлчам-асосли қарор қилишини алдаш ва ҳар билд noyob хеш қилиш учун, 1,400+ варианты учун хесҳ кўрсаткичи)

---

## 📚 PUBLIC THREAT INTEL РЕФЕРЕНС

Менинг тахлилимни тасдиқлайдиган манбалар:

1. **Group-IB Blog** (Октябр 2025): "Choose Your Fighter: A New Stage in the Evolution of Android SMS Stealers in Uzbekistan" — https://www.group-ib.com/blog/mobile-malware-uzbekistan/
2. **Group-IB (May 2024)**: "Ajina attacks Central Asia" — https://www.group-ib.com/blog/ajina-malware/
3. **Rewterz IOC report**: Ajina.Banker 10 та C2 IP манзил (ushbu билдан фарқли)
4. **The Hacker News (December 2025)**: "Android Malware Operations Merge Droppers, SMS Theft, and RAT Capabilities at Scale"
5. **DarkReading**: "Uzbek Users Under Attack by Android SMS Stealers"

### Public Ajina.Banker C2 IPlar (eski) — менда yo'q, лекин менинг банкерлар янги avlod:
```
79.137.205.212    46.226.160.19     109.120.135.42    77.105.166.215
5.42.77.147       147.45.42.85      79.137.202.32     77.221.136.21
46.226.167.24     45.15.157.38
```

**Менинг найден C2 (янги, public ҳолатида YOK):**
```
elrxzx.com                                           ← C2 (qisqa)
ilovekkksfm.com                                      ← Dropper distribution
https://ilovekkksfm.com/video/dropper.html           ← Real entry point
```

Бу 3 та URL'и **public threat intel'да ҳозиргинага рапорт қилинмаган** — балки янги (2026 май oраtы).
