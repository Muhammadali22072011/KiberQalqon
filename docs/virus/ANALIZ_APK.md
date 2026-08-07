# APK tahlili: u qanday ishlaydi va nimani izlash kerak

## Ushbu fayl haqida

**APK nomi:** `⬅️TAKLIFNOMA TOY AVGUST 1325478967.apk`

Nomning o‘zidan **ijtimoiy injeneriya** ko‘rinib turibdi: ilova to‘y taklifnomasi qiyofasiga kiradi («Taklifnoma to‘y avgust»). Viruslar va troyanlar shunday tarqatiladi — foydalanuvchi u taklifnoma deb o‘ylaydi, lekin aslida bu qo‘ng‘iroq, SMS, kontakt va h.k. larga kira oladigan dastur.

---

## 1-qadam: Tezkor tahlil (o‘rnatmasdan)

### 1.1 Skriptni ishga tushirish

APK papkasida quyidagini bajaring:

```bash
python apk_analyzer.py
```

Skript:

- APK ni `apk_extracted` papkasiga ochadi;
- Tarkibni (fayllar, hajmlar) ko‘rsatadi;
- DEX fayllar ro‘yxatini chiqaradi (ilova kodi shu yerda);
- `AndroidManifest.xml` dan xavfli ruxsatlarni topishga harakat qiladi.

### 1.2 Tarkibda nimani ko‘rish kerak

- **`AndroidManifest.xml`** — binar, unda ruxsatlar, komponentlar (Activity, Service, Receiver), tizim ishga tushganida ishga tushish bor.
- **`classes.dex`** (va mavjud bo‘lsa `classes2.dex`, …) — kompilyatsiya qilingan kod (Dalvik/ART). Dekompilyatsiyasiz o‘qish qiyin.
- **`res/`** — resurslar (rasmlar, satrlar, layout).
- **`META-INF/`** — imzolar va sertifikatlar.

---

## 2-qadam: Manifestni to‘liq tahlil qilish (apktool)

APK ichidagi binar `AndroidManifest.xml` to‘g‘ridan-to‘g‘ri o‘qilmaydi. Dekod qilish kerak.

### Apktool o‘rnatish

- Sayt: https://apktool.org/
- Yoki Chocolatey orqali: `choco install apktool`

### Buyruq

```bash
apktool d "⬅️TAKLIFNOMA TOY AVGUST 1325478967.apk" -o apk_decoded
```

`apk_decoded` papkasida **o‘qiladigan** `AndroidManifest.xml` paydo bo‘ladi.

### Manifestda nimani ko‘rish kerak

1. **`<uses-permission>`**
   «To‘y taklifnomasi» uchun shubhali:
   - `SEND_SMS`, `READ_SMS`, `RECEIVE_SMS` — SMS yuborish/o‘qish;
   - `CALL_PHONE`, `READ_CALL_LOG` — qo‘ng‘iroq va loglar;
   - `READ_CONTACTS`, `WRITE_CONTACTS` — kontaktlar;
   - `RECORD_AUDIO`, `CAMERA` — mikrofon/kamera;
   - `RECEIVE_BOOT_COMPLETED` — qayta yuklashdan keyin avtoishga tushish;
   - `REQUEST_INSTALL_PACKAGES` — boshqa ilovalarni o‘rnatish;
   - `BIND_ACCESSIBILITY_SERVICE` — ekran va kiritishga juda kuchli kirish (ko‘pincha bank troyanlarida bo‘ladi);
   - `SYSTEM_ALERT_WINDOW` — boshqa oynalar ustida (overlay);
   - `INTERNET` — tarmoq (serverga ma’lumot yuborish).

2. **Komponentlar**
   - **`<receiver android:name="..." android:exported="true">`** `BOOT_COMPLETED` bilan — telefon yoqilganda ishga tushish.
   - **`<service>`** — fon vazifalari (ma’lumot yuborishi, overlay ko‘rsatishi mumkin).
   - Oddiy «taklifnoma» uchun Activity/Service juda ko‘p — bu ortiqcha funksionallik belgisi.

3. **Satrlar va nomlar**
   - `res/values/strings.xml` da — URL, paket nomlari, shubhali satrlar.
   - Manifestdagi klass nomlari (masalan, `SmsSender`, `KeyLogger` kabi shubhali nomlar).

---

## 3-qadam: Kodni ko‘rish (JADX)

JADX DEX ni o‘qiladigan Java koduga aylantiradi.

- Sayt: https://github.com/skylot/jadx
- GUI: `jadx-gui "⬅️TAKLIFNOMA TOY AVGUST 1325478967.apk"`

### Kodda nimani izlash kerak

- SMS yuborish, HTTP so‘rovlar (URL, API);
- Kontakt, qo‘ng‘iroq, SMS o‘qish;
- Fayllarga yozish, `SharedPreferences` ga kirish;
- `BOOT_COMPLETED` da receiverlarni ro‘yxatdan o‘tkazish, servislar yaratish;
- Accessibility Service, overlay ishlatish;
- Obfuskatsiya (klass/metod nomlari ma’nosiz) — ko‘pincha malware da.

---

## Bunday virus qanday «ishlashi» mumkin

«Taklifnoma» qiyofasidagi malware uchun odatiy stsenariy:

1. **O‘rnatish** — foydalanuvchi APK ni qo‘yadi, ruxsatlarni beradi (SMS, kontakt, qo‘ng‘iroq va h.k.).
2. **Avtoishga tushish** — telefon yoqilganida `BOOT_COMPLETED` ishlaydi, servis/receiver ishga tushadi.
3. **Ma’lumotlarni o‘g‘irlash** — kontaktlar, SMS lar, qo‘ng‘iroq ro‘yxati o‘qiladi va niyati buzuq odamning serveriga yuboriladi (`INTERNET` orqali).
4. **SMS** — pullik raqamlarga yoki tasdiqlash kodlari niyati buzuq raqamlariga SMS yuborish.
5. **Qo‘shimcha o‘rnatishlar** — `REQUEST_INSTALL_PACKAGES` mavjud bo‘lsa, yana bir APK (masalan, bank troyani) o‘rnatishni taklif qilish mumkin.
6. **Overlay / Accessibility** — tegishli ruxsatlar bo‘lsa, soxta kirish oynalari (bank, ijtimoiy tarmoq) va kiritishni ushlab olish mumkin.

---

## Xavfsizlik

- Tahlilni **alohida muhitda** (virtual mashina yoki muhim ma’lumotlari bo‘lmagan test telefon) bajaring.
- Shubhali APK ni haqiqiy kontakt va bank ilovalari bo‘lgan asosiy telefonga **o‘rnatmang**.
- Tahlildan keyin `apk_extracted` va `apk_decoded` papkalarini o‘chirish mumkin; APK ning o‘zini faqat tahlil uchun saqlang va boshqalarga bermang.

---

## Qisqacha tekshirish ro‘yxati

| Amal | Buyruq/asbob |
|----------|---------------------|
| Ochish va ruxsatlar ro‘yxati | `python apk_analyzer.py` |
| O‘qiladigan manifest | `apktool d file.apk -o apk_decoded` |
| Kodni ko‘rish | `jadx-gui file.apk` |
| Satrlar/URL bo‘yicha qidirish | `apk_decoded/res/values/` da, JADX da — loyiha bo‘yicha qidirish |

Keyin hisobotni to‘ldirish mumkin: qaysi ruxsatlar topildi, qaysi klass/servislar bor, kodda qaysi URL yoki raqamlar uchraydi — shu asosida ushbu APK **aniq qanday ishlashini** ta’riflash mumkin.
