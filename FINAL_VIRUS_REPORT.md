# 🦠 VIRUS TAHLILI — TO‘LIQ HISOBOT

## 📱 VIRUS HAQIDA MA’LUMOT

**Fayl nomi:** `Video_202_089_mp8ㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤㅤ.apk`
**Package:** `pyw.kzxwc`
**Hajmi:** 1502.35 KB (1.5 MB)
**Sana:** 10.02.2026 22:14:42

---

## 🚨 XAVF DARAJASI: 🔴 KRITIK

Bu **troyan-yuklovchi (Dropper)** — boshqa zararli ilovalarni o‘rnatadi!

---

## 🔐 XAVFLI RUXSATLAR

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"/>
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"/>
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE"/>
```

**Bu nimani anglatadi:**
- `INTERNET` — ma’lumotlarni internetga yuboradi
- `QUERY_ALL_PACKAGES` — barcha o‘rnatilgan ilovalarni ko‘radi
- `REQUEST_INSTALL_PACKAGES` — **XABARINGIZ BO‘LMAGAN HOLDA APK O‘RNATADI!**
- `FOREGROUND_SERVICE` — fonda doimo ishlaydi
- `WAKE_LOCK` — telefonni uxlatmaydi (batareya tez tugaydi)

---

## 🎯 VIRUS NIMA QILADI

### 1️⃣ ASOSIY ACTIVITY (G9e3owjh.java)

**Ishga tushganda:**
1. **WebView** ochadi va masofadagi URL ni yuklaydi (tashqi boshqaruv!)
2. Hakerlar serveridan buyruqlarni yuklab oladi
3. Zararli amallar uchun **fon Thread** ishga tushiradi
4. Buyruqlarni qabul qilish uchun **BroadcastReceiver** ro‘yxatdan o‘tkazadi
5. APK o‘rnatish ruxsatini tekshiradi

**Kod:**
```java
webView.loadUrl(Xsskcobnapsuhssngpfnlndant); // Serverdan URL yuklaydi
registerReceiver(receiver, intentFilter); // Buyruqlarni tinglaydi
```

### 2️⃣ RECEIVER (Receiver.java) — ENG XAVFLISI!

**BroadcastReceiver orqali buyruqlarni qabul qiladi:**

**Buyruq -1:** Intent ochadi yoki APK o‘rnatadi
```java
Intent intent3 = (Intent) intent.getParcelableExtra("android.intent.extra.INTENT");
context.startActivity(intent3); // Xohlagan narsani ochadi!
```

**Buyruq 0:** APK o‘rnatadi, ishga tushiradi, O‘ZINI O‘LDIRADI
```java
// APK o‘rnatadi
Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(str);
context.startActivity(launchIntent);

// Yashirinish uchun o‘zini o‘ldiradi
Process.killProcess(Process.myPid());
```

**Buyruqlar 1-7:** MainActivity ni qayta ishga tushiradi, servisni to‘xtatadi

### 3️⃣ SERVICE (Qh9hbw5vb8.java)

**Fon servisi:**
1. **Foreground** rejimida ishlaydi (tizim uni o‘ldira olmaydi)
2. **WakeLock** ushlab turadi — telefon 10 daqiqa uxlamaydi!
3. Yashirinish uchun **notification** yaratadi

**Kod:**
```java
PowerManager.WakeLock newWakeLock = powerManager.newWakeLock(1, "y2k4m8nP5qL9::w7k4L1ck");
newWakeLock.acquire(600000L); // 10 daqiqa!
startForeground(1, notification); // Foreground service
```

---

## 🔍 OBFUSKATSIYA TEXNIKASI

### 1. Tasodifiy klass nomlari
```
Cvkiiobywakcmauentcrxnquxsgdxplglhzd
Gvomrituctwfbnloyvxphvtlclqxiswbb
Bwyoaflgzytvvimajsqyysuqmoivyzvpi
```

### 2. Shifrlangan satrlar
```java
// "intentPackageLaunched"
new String(new char[]{(char) 105, c, c2, c3, c, c2, (char) 80, c4, c5, ...})
```

### 3. Shifrlangan konfiguratsiya fayli
`assets/vudgi.json` (540 KB) — to‘liq shifrlangan

### 4. Native kutubxonalar
```
assets/libs/arm64-v8a/libnative-lib.so (45.95 KB)
assets/libs/armeabi-v7a/libnative-lib.so (43.47 KB)
assets/libs/x86/libnative-lib.so (42.86 KB)
assets/libs/x86_64/libnative-lib.so (44.95 KB)
```

---

## 🎭 HUJUM STSENARIYSI

### 1-qadam: O‘rnatish
1. Foydalanuvchi “Video_202_089_mp8.apk” ni yuklab oladi
2. Buni video deb o‘ylaydi (nomida ko‘rinmas belgilar bor!)
3. Ilovani o‘rnatadi

### 2-qadam: Ishga tushirish
1. Ilova ishga tushadi
2. Masofadagi URL bilan WebView ochiladi
3. Hakerlar serveridan buyruqlarni yuklab oladi
4. Foreground service ishga tushadi

### 3-qadam: Buyruqlarni qabul qilish
1. Server BroadcastReceiver orqali buyruq yuboradi
2. Buyruqda boshqa APK ni o‘rnatish uchun Intent bo‘ladi
3. Virus foydalanuvchi xabari bo‘lmagan holda APK ni o‘rnatadi

### 4-qadam: Zararli dasturni o‘rnatish
1. Asl virus o‘rnatiladi (bank troyani, josus, va h.k.)
2. Asl virus ishga tushadi
3. Yuklovchi o‘zini o‘ldiradi (`Process.killProcess`)
4. Foydalanuvchi nima sodir bo‘lganini ko‘rmaydi!

### 5-qadam: Izlarni yashirish
1. Yuklovchi o‘chgan
2. Faqat asl virus qoladi
3. Foydalanuvchi u qayerdan kelganini bilmaydi

---

## 🌐 TARMOQ FAOLLIGI

**WebView shifrlangan satrdan URL yuklaydi:**
```java
String string = getString(R.string.abc_squirrel_stork_swan_toucan_vulture_dingo);
String url = Kgqsaqftbnylwlsfgoukmlbsiaiph.Xsskcobnapsuhssngpfnlndant(string);
webView.loadUrl(url);
```

**Mumkin bo‘lgan amallar:**
- C&C serverdan buyruqlarni yuklash
- O‘rnatilgan ilovalar ro‘yxatini yuborish
- Zararli APK larni yuklab olish URL larini olish
- Qurilma haqida ma’lumot yuborish

---

## 📊 VIRUS KOMPONENTLARI

### Activities (21 ta!)
```xml
<activity android:name="vtkuvurph.com.G9e3owjh" android:exported="true"/> <!-- Asosiy -->
<activity android:name="vtkuvurph.com.Lca3rtzib73" android:exported="false"/>
<activity android:name="vtkuvurph.com.K8z250hvj" android:exported="false"/>
<activity android:name="vtkuvurph.com.Vzypwrnd8oc6k1s" android:exported="false"/>
<!-- ... yana 17 ta yashirin Activity -->
```

### Receiver (buyruqlarni oladi)
```xml
<receiver android:name="vtkuvurph.com.Receiver" android:exported="true">
    <intent-filter>
        <action android:name="vtkuvurph.com.w8q92mK47"/>
        <action android:name="vtkuvurph.com.p3n67vR29"/>
        <action android:name="vtkuvurph.com.d7k58bL36"/>
    </intent-filter>
</receiver>
```

### Service (fon jarayoni)
```xml
<service android:name="vtkuvurph.com.Qh9hbw5vb8"
         android:foregroundServiceType="dataSync"/>
```

---

## 🔬 KOD TAHLILI

### SharedPreferences (ma’lumotni saqlash)
```java
// Holatni saqlash uchun kalitlar
"w5m8k3n7p2q9" - asosiy sozlamalar
"m6k3n8j4p7q2" - paket o‘rnatish bayrog‘i
"h7j4k9m2p5r8" - birinchi ishga tushirish bayrog‘i
"v8k4n2p9q7m5" - urinishlar hisoblagichi
"k7j4h2n9p5m8" - o‘rnatishlar tarixi
```

### BroadcastReceiver Actions
```java
"vtkuvurph.com.w8q92mK47" - boshqaruv buyrug‘i
"vtkuvurph.com.p3n67vR29" - o‘rnatish buyrug‘i
"vtkuvurph.com.d7k58bL36" - yangilash buyrug‘i
```

### Obfuskatsiya qilingan satrlar
```java
// "intentPackage"
System.currentTimeMillis();
char c = (char) 110; // 'n'
char c2 = (char) 116; // 't'
char c3 = (char) 101; // 'e'
String str = new String(new char[]{(char) 105, c, c2, c3, ...});
```

---

## 🛡️ HIMOYA VA O‘CHIRISH

### ❌ O‘RNATMANG!

### ✅ Agar allaqachon o‘rnatilgan bo‘lsa:

1. **Zudlik bilan o‘chiring:**
   - Sozlamalar → Ilovalar → Shubhali ilovani toping
   - O‘chirish

2. **Boshqa ilovalarni tekshiring:**
   - Virus boshqa APK larni o‘rnatgan bo‘lishi mumkin!
   - Barcha shubhali ilovalarni o‘chiring

3. **Parollarni almashtiring:**
   - Bank ilovalari
   - Ijtimoiy tarmoqlar
   - Pochta
   - Barcha muhim akkauntlar

4. **SMS larni tekshiring:**
   - Pullik obunalar
   - Yuborilgan SMS lar

5. **Antivirus bilan skanerlash:**
   - Antivirus o‘rnating (masalan, KiberQalqon!)
   - To‘liq skanerlash

6. **Zavod sozlamalariga qaytarish (tavsiya etiladi):**
   - Sozlamalar → Tizim → Reset
   - Reset oldidan muhim ma’lumotlarni saqlang!

---

## 🔍 KOMPROMETATSIYA KO‘RSATKICHLARI (IOC)

### Package Name
```
pyw.kzxwc
```

### Sertifikat
```
OPYWSKZX.RSA
```

### Fayllar
```
assets/vudgi.json (540 KB, shifrlangan)
assets/libs/*/libnative-lib.so
```

### Ruxsatlar
```
REQUEST_INSTALL_PACKAGES + INTERNET + FOREGROUND_SERVICE
```

### Xatti-harakat
- Masofadagi URL bilan WebView ochadi
- BroadcastReceiver ro‘yxatdan o‘tkazadi
- Foreground service ishga tushiradi
- WakeLock ushlab turadi
- Boshqa APK larni o‘rnatadi
- O‘rnatgandan keyin o‘zini o‘ldiradi

---

## 📝 XULOSALAR

### Virus turi
**Troyan-yuklovchi (Dropper/Downloader)**

### Maqsadi
Boshqa zararli ilovalarni o‘rnatish:
- Bank troyanlari
- Josuslik dasturi
- Reklama dasturi
- Ransomware

### Xavfi
🔴 **KRITIK**

### Texnik murakkabligi
⭐⭐⭐⭐⭐ (5/5)
- Kod obfuskatsiyasi
- Shifrlangan satrlar
- Native kutubxonalar
- Foreground service
- BroadcastReceiver
- C&C uchun WebView

### Tarqalish
- Telegram
- WhatsApp
- Fayl almashish servislari
- Fishing havolalari

---

## 🚨 TAVSIYALAR

### Foydalanuvchilar uchun:
1. ❌ **Noma’lum manbalardan APK O‘RNATMANG!**
2. ✅ Ilovalarni faqat Google Play dan yuklab oling
3. ✅ O‘rnatishdan oldin ruxsatlarni tekshiring
4. ✅ Antivirus ishlating
5. ✅ Android ni so‘nggi versiyaga yangilang

### Antivirus ishlab chiquvchilari uchun:
1. Package name bo‘yicha aniqlash: `pyw.kzxwc`
2. Sertifikat bo‘yicha aniqlash: `OPYWSKZX`
3. Xatti-harakat bo‘yicha aniqlash: `REQUEST_INSTALL_PACKAGES` + `INTERNET`
4. Fayllar bo‘yicha aniqlash: `assets/vudgi.json`
5. BroadcastReceiver actions bo‘yicha aniqlash

---

## 📚 QO‘SHIMCHA MA’LUMOT

### Dekompilatsiya qilingan kod
To‘liq dekompilatsiya qilingan kod quyidagi papkada:
```
VIRUS_DECOMPILED/
├── sources/ (Java/Kotlin kodi)
│   ├── vtkuvurph/com/ (asosiy paket)
│   ├── Cvkiiobywakcmauentcrxnquxsgdxplglhzd/ (obfuskatsiya qilingan klasslar)
│   └── ... (yuzlab boshqa klasslar)
└── resources/ (resurslar, manifest)
```

### Tahlil uchun asosiy fayllar
```
VIRUS_DECOMPILED/sources/vtkuvurph/com/G9e3owjh.java - MainActivity
VIRUS_DECOMPILED/sources/vtkuvurph/com/Receiver.java - BroadcastReceiver
VIRUS_DECOMPILED/sources/vtkuvurph/com/Qh9hbw5vb8.java - Service
VIRUS_DECOMPILED/resources/AndroidManifest.xml - Manifest
```

---

## ⚠️ OGOHLANTIRISH

**BU APK XAVFLI ZARARLI DASTUR!**

- Haqiqiy qurilmada O‘RNATMANG!
- Faqat virtual mashinada tahlil qiling!
- Tahlildan keyin o‘chiring!

---

*Hisobot yaratildi: 10.02.2026*
*Tahlilchi: JADX + qo‘lda kod tahlili*
*Xavf darajasi: 🔴 KRITIK*
