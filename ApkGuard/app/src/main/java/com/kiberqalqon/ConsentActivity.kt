package com.kiberqalqon

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.card.MaterialCardView

/**
 * Mandatory consent screen — ToS + Privacy Policy. Shown before the first
 * MainActivity launch (and again after a ToS/Privacy version bump).
 *
 * Legal requirements (UZ №547-II, RU 152-FZ, GDPR-style):
 *  - Explicit opt-in (checkboxes, not pre-checked)
 *  - Full disclosure of what is collected and where it goes
 *  - Ability to decline
 *  - Ability to revoke consent later (from Settings)
 *
 * Renders activity_consent.xml so the screen matches the §3.7 redesign palette.
 */
class ConsentActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REVIEW_MODE = "review_mode"

        /** Open in review mode (from Settings) — no checkboxes, no exit-on-decline. */
        fun openForReview(ctx: Context) {
            ctx.startActivity(
                Intent(ctx, ConsentActivity::class.java)
                    .putExtra(EXTRA_REVIEW_MODE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applyAccent(this)
        setContentView(R.layout.activity_consent)

        // Ekran yengil paydo bo'ladi (Yorug' minimal kirish animatsiyasi).
        findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)?.let {
            AnimationHelper.fadeIn(it, duration = 380)
        }

        val reviewMode = intent.getBooleanExtra(EXTRA_REVIEW_MODE, false)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            if (reviewMode) finish() else onDecline()
        }

        findViewById<TextView>(R.id.tvScreenTitle).text = if (reviewMode)
            "Maxfiylik siyosati" else "Foydalanish shartlari"
        findViewById<TextView>(R.id.tvHeaderTitle).text = if (reviewMode)
            "Shartlar va maxfiylik siyosati" else "Foydalanishni boshlashdan oldin"

        findViewById<TextView>(R.id.tvTermsBody).text = TERMS_OF_SERVICE
        findViewById<TextView>(R.id.tvPrivacyBody).text = PRIVACY_POLICY

        val cardConsent = findViewById<MaterialCardView>(R.id.cardConsent)
        val btnClose = findViewById<Button>(R.id.btnClose)
        val cbTerms = findViewById<CheckBox>(R.id.cbTerms)
        val cbPrivacy = findViewById<CheckBox>(R.id.cbPrivacy)
        val cbCommunity = findViewById<CheckBox>(R.id.cbCommunity)
        val btnAccept = findViewById<Button>(R.id.btnAccept)
        val btnDecline = findViewById<Button>(R.id.btnDecline)

        if (reviewMode) {
            cardConsent.visibility = View.GONE
            btnClose.visibility = View.VISIBLE
            btnClose.setOnClickListener { finish() }
        } else {
            cardConsent.visibility = View.VISIBLE
            btnClose.visibility = View.GONE

            // UX-02: faqat ToS + Maxfiylik majburiy. "Jamoatchilik xavfsizligi uchun ma'lumot
            // ulashish" — OPT-IN (mahsulot hamma joyda shunday deydi); avval u ham majburiy edi
            // (qorong'i pattern: rozilik bermasdan antivirusdan foydalanib bo'lmasdi).
            val gate: () -> Unit = {
                btnAccept.isEnabled = cbTerms.isChecked && cbPrivacy.isChecked
            }
            cbTerms.setOnCheckedChangeListener { _, _ -> gate() }
            cbPrivacy.setOnCheckedChangeListener { _, _ -> gate() }

            btnAccept.setOnClickListener { onAccept(cbCommunity.isChecked) }
            btnDecline.setOnClickListener { onDecline() }

            onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    onDecline()
                }
            })
        }
    }

    private fun onAccept(communityConsent: Boolean) {
        Config.setUserConsent(this, true)
        Config.setCommunityShareConsent(this, communityConsent)
        // UX-02: joylashuv ruxsati BU YERDA SO'RALMAYDI. "just-in-time permissions" o'zgartishi uni
        // ProtectionStatus'dagi ixtiyoriy qatorga ko'chirgan edi — bu yerda yana so'rasak, geolokatsiya
        // ikki marta so'raladi (consent + checklist), ya'ni olib tashlangan "marafon" qaytib keladi.
        proceedAfterConsent()
    }

    // OLIB TASHLANDI: onRequestPermissionsResult + REQ_LOCATION (104). Bu ekran
    // ActivityCompat.requestPermissions ni HECH QACHON chaqirmaydi — joylashuv so'rovi
    // ProtectionStatusActivity ro'yxatiga ko'chirilgan, ya'ni 104 kodi bilan natija
    // hech qachon kelmasdi va butun tarmoq o'lik edi.

    // Post-consent route — mirrors SplashActivity.goToMainActivity():
    // Onboarding (first run) → InitialScanActivity (если ещё не было первичного скана)
    // → ProtectionStatusActivity (gate: !isProtectionAcked ИЛИ отозвано критичное
    // разрешение) → DashboardNewActivity.
    private fun proceedAfterConsent() {
        // Rozilik + joylashuv ruxsati AYNAN HOZIR hal bo'ldi — qurilmani DARHOL
        // ro'yxatdan o'tkazamiz, shunda u xaritada birinchi ishga tushirishdayoq
        // ko'rinadi (App.onCreate'dagi register ilk startda rozilikgача chaqirilgani
        // uchun o'tkazib yuborilgan edi). registerDevice tarmoq ishini IO oqimida
        // bajaradi va 12 soatlik throttle bilan takror yubormaydi.
        CloudTelemetry.registerDevice(this)

        // SplashActivity.goToMainActivity() bilan BIR XIL marshrut zinapoyasini takrorlaymiz
        // (rozilik allaqachon berilgan — shu sababli birinchi shart o'tkazib yuboriladi):
        // birinchi ishga tushish → Onboarding; ilk skan qilinmagan → InitialScan; "Himoya
        // holati" tasdiqlanmagan YOKI majburiy ruxsatlardan biri o'chirilgan → ProtectionStatus
        // shlagbaumi; aks holda Dashboard. Bu yerda ProtectionStatus gate'ini o'tkazib yuborish
        // foydalanuvchiga ruxsat o'chirilgan holatda ham Dashboard'ga kirish imkonini berardi.
        val target = when {
            Config.isFirstRun(this) -> OnboardingActivity::class.java
            !Config.isInitialScanDone(this) -> InitialScanActivity::class.java
            !Config.isProtectionAcked(this) -> ProtectionStatusActivity::class.java
            // SplashActivity.goToMainActivity() bilan BIR XIL tekshiruv: `criticalSystemPermissions…`,
            // `allCritical…` EMAS. Ikkinchisiga `Config.isBackgroundEnabled` tumbleri ham kiradi —
            // fon himoyasini ataylab o'chirgan foydalanuvchi (bu qo'llab-quvvatlanadigan tanlov)
            // ToS versiyasi yangilangach roziligini qayta bergandan keyin Dashboard o'rniga
            // shlagbaumga tushib qolardi, holbuki oddiy ishga tushirishda Splash uni o'tkazib
            // yuborardi — bitta holat uchun ikki xil marshrut.
            !ProtectionStatusActivity.criticalSystemPermissionsGranted(this) ->
                ProtectionStatusActivity::class.java
            else -> DashboardNewActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }

    private fun onDecline() {
        AlertDialog.Builder(this)
            .setTitle("Ishonchingiz komilmi?")
            .setMessage(
                "Rad etsangiz, KiberQalqon ishlay olmaydi va ilovadan chiqasiz.\n\n" +
                    "Telegram telemetriya MAJBURIY emas — uni Sozlamalarda alohida yoqishingiz mumkin. " +
                    "Asosiy himoya funksiyalari (skaner, ogohlantirish) Telegram'siz ham ishlaydi."
            )
            .setPositiveButton("Qaytib o'qish") { _, _ -> /* nothing */ }
            .setNegativeButton("Chiqish") { _, _ ->
                finishAndRemoveTask()
            }
            .show()
    }
}

// ============================================================
//   TEXTS — kept in source (not strings.xml) so the screen still renders if
//   resources fail to load, per the legal-resilience pattern that has shipped
//   in v1+ of the app.
// ============================================================

private val TERMS_OF_SERVICE = """
KiberQalqon — Android qurilmangizdagi APK fayllarni xavfsizlik nuqtai nazaridan tekshiruvchi vositadir. Ushbu shartlarni o'qib chiqing, ular ilovadan foydalanish qoidalarini belgilaydi.

1. ILOVA HAQIDA
KiberQalqon APK fayllarini evristik tahlil yordamida skan qiladi (imzo, ruxsatlar, malware-signaturalar). U 100% aniqlik kafolatlamaydi: ba'zi xavfli APK'lar "xavfsiz" deb belgilanishi yoki aksincha.

2. YOSH CHEGARASI
Ilovadan foydalanish uchun siz kamida 13 yoshda bo'lishingiz kerak. Agar 13-18 yosh oralig'ida bo'lsangiz, ota-onangiz roziligi tavsiya etiladi.

3. MAS'ULIYAT
- Skan natijasiga qaramay, qaysi ilovani o'rnatishga qaror qilish — sizning mas'uliyatingizdir.
- KiberQalqon xavfli deb topgan APK'larni avtomatik o'chirib yoki karantinga qo'yishi mumkin (sozlamalardan boshqaring).
- Dasturchi noto'g'ri natija sababli yo'qotgan ma'lumot yoki zarar uchun javobgar emas.

4. RUXSATLAR (nima uchun so'raladi)
KiberQalqon faqat o'z vazifasi uchun zarur tizim ruxsatlarini so'raydi. Hech bir ruxsat reklama yoki kuzatuv uchun ishlatilmaydi.

Fayllarni tekshirish:
- Barcha fayllarga kirish (MANAGE_EXTERNAL_STORAGE) — qurilmadagi APK fayllarni topish va xavflilarini o'chirish uchun
- Rasm, video, audio fayllar (READ_MEDIA_IMAGES/VIDEO/AUDIO) — Android 13+ da "rasm" yoki "video" niqobidagi APK'larni tekshirish uchun
- Xotiradan o'qish/yozish (READ/WRITE_EXTERNAL_STORAGE) — eski Android (12 va undan past) versiyalarda xuddi shu maqsadda

Himoya va ogohlantirish:
- Bildirishnomalar (POST_NOTIFICATIONS) — xavfli APK aniqlanganda darhol xabar berish uchun
- Boshqa oynalar ustida ko'rsatish va to'liq ekran ogohlantirish (SYSTEM_ALERT_WINDOW, USE_FULL_SCREEN_INTENT) — xavf topilganda ekranni bloklab ogohlantirish chiqarish uchun
- Doimiy himoya xizmati (FOREGROUND_SERVICE, Android 14+ uchun "specialUse" turi) — fonda real vaqtli himoyani saqlash uchun
- Qayta yuklashdan keyin ishga tushish (RECEIVE_BOOT_COMPLETED) — telefon o'chib-yongach himoyani avtomatik tiklash uchun
- Batareya optimizatsiyasidan ozod qilish (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) — Xiaomi, Samsung kabi qurilmalarda davriy skan to'xtab qolmasligi uchun

O'rnatish va internet:
- Ilova o'rnatish (REQUEST_INSTALL_PACKAGES) — tekshiruvdan o'tgan APK'ni xavfsiz o'rnatishga uzatish uchun
- Internet (INTERNET, ACCESS_NETWORK_STATE) — faqat IXTIYORIY Telegram telemetriya yoki jamoatchilik xavf bazasi yoqilgan bo'lsa ishlatiladi; aks holda ilova internetga chiqmaydi

5. TAQIQLAR
Quyidagilarni qilmang:
- KiberQalqon'ni reverse-engineer qilish, dekompilatsiya
- Skanerni aldash uchun fayllarni o'zgartirish
- Ilovadan boshqalarning qurilmasiga ruxsatsiz kirish uchun foydalanish

6. KAFOLATLAR YO'Q
Ilova "qanday bo'lsa shunday" (as-is) taqdim etiladi. Hech qanday aniqlik, foydalilik yoki muayyan maqsadga moslik kafolati berilmaydi.

7. SHARTLAR O'ZGARISHI
Ushbu shartlar yangilanishi mumkin. Yangi versiyadagi muhim o'zgarishlar bo'lsa, ilovani ochishda rozilik qayta so'raladi.

8. ALOQA
Savol bo'lsa: Sozlamalar → Yordam bo'limidan murojaat qiling.

Qabul qilish — ushbu shartlarni o'qiganingizni va tushunganingizni anglatadi.
""".trimIndent()

private val PRIVACY_POLICY = """
Bu siyosat KiberQalqon'ning ma'lumot bilan ishlashini to'liq tushuntiradi. HECH NARSA YASHIRILMAGAN.

═══════════════════════════════
1. ASOSIY PRINSIP
═══════════════════════════════
Skan natijalari va sozlamalar faqat sizning qurilmangizda saqlanadi. Ma'lumot qurilmadan tashqariga FAQAT quyida (3 va 4-bo'limlar) ochiq tushuntirilgan funksiyalar orqali chiqadi. Bulardan "Jamoatchilik xavfsizligi" (4-bo'lim) ilovadan foydalanish uchun MAJBURIY; shaxsiy Telegram telemetriya (3-bo'lim) esa IXTIYORIY.

═══════════════════════════════
2. QURILMADA SAQLANADIGAN MA'LUMOTLAR
═══════════════════════════════
Ilova lokal (faqat sizning telefoningizda) saqlaydi:

• Skan tarixi (oxirgi 200 ta yozuv): APK nomi, yo'li, verdict (xavfsiz/shubhali/xavfli), sabab, vaqt
• Statistika hisoblagichlari: jami skan, bloklangan, xavfsiz
• Sozlamalar (til, mavzu, sezgirlik darajasi)

Bu ma'lumotlar telefoningizning ichki xotirasida (SharedPreferences) saqlanadi va boshqa ilovalar uchun ochiq emas.

═══════════════════════════════
3. TELEGRAM TELEMETRIYA (IXTIYORIY)
═══════════════════════════════
KiberQalqon'da Telegram bot orqali xabarlar yuborish funksiyasi bor. U STANDARTBOQ O'CHIRILGAN. Yoqilgan taqdirdagina ishlaydi.

YOQILGAN bo'lsa, NIMA YUBORILADI:
• Skaner natijalari: APK fayl nomi, hajmi, verdict, sabab, manba (telegram/whatsapp/download)
• Yangi o'rnatilgan ilovalar: paket nomi, label, skan natijasi
• Ilova ishga tushishi va to'xtashi
• Crash log'lari (kod xatosi yuz bersa)
• Qurilma ma'lumotlari: ishlab chiqaruvchi (masalan, Samsung), model, Android versiyasi
• Test xabarlari (siz "Test" tugmasini bossangiz)

YOQILGAN bo'lsa, NIMA YUBORILMAYDI:
• Shaxsiy faylllar (rasm, video, hujjat)
• Kontaktlar, SMS, chat tarixi
• Joylashuv (GPS)
• Akkaunt parol va token'laringiz
• Telefonning IMEI yoki seriya raqami

QAYERGA YUBORILADI:
• FAQAT siz Sozlamalar → DIAGNOSTIKA → Telegram telemetriya bo'limida kiritgan bot va chat_id'ga
• Dasturchining serveriga HECH NARSA YUBORILMAYDI
• Uchinchi tomon analitika (Firebase, Crashlytics, Google Analytics) ISHLATILMAYDI
• Reklama tarmoqlari ISHLATILMAYDI

QANDAY YOQILADI:
SplashActivity → Versiya raqamiga uzoq bosing → DIAGNOSTIKA → "🤖 Telegram telemetriya" → o'z bot tokeningiz va chat_id'ni kiriting → "Yoqish" tugmasini bosing.

═══════════════════════════════
4. JAMOATCHILIK XAVFI ULASHISH (ASOSIY ROZILIK QISMI)
═══════════════════════════════
KiberQalqon'ning ishlash printsipi: yangi malware'larni butun jamiyat uchun tezroq aniqlash. Buning uchun har bir qurilmadan xavf signallari markaziy bazaga yuboriladi va u erdan barcha foydalanuvchilarga himoya signaturalari tarqatiladi. Bu Kaspersky Security Network, ESET LiveGrid va Microsoft MAPS kabi standart amaliyot.

SHU SABABLI ushbu funksiya KiberQalqon'ning ASOSIY ROZILIK QISMI hisoblanadi — pastdagi 3-galochka MAJBURIY. Agar siz bu funksiyaga rozi bo'lmasangiz, ilovani ishlatib bo'lmaydi (rad eting va chiqing).

NIMALAR KiberQalqon jamoasiga yuboriladi:

KiberQalqon ikki kanaldan foydalanadi: (1) markaziy BULUT monitoringi — himoya statistikasi va xaritasi uchun, (2) xavfli namunalar uchun Telegram hisoboti. Quyida har biri aniq ko'rsatilgan.

(a) BULUT monitoringiga — HAR BIR skanda (xavfsiz natijalar HAM, "jami skan" statistikasi va xaritadagi yashil nuqtalar uchun):
   • Anonim qurilma identifikatori — tasodifiy UUID. Bu IMEI, seriya raqami yoki telefon raqami EMAS.
   • Qurilma modeli (masalan "Samsung SM-A536E"), Android versiyasi va ilova versiyasi
   • Skan meta-ma'lumoti: APK SHA-256 hashi, paket nomi, verdict (xavfsiz/shubhali/xavfli), risk ball, sabablar va xavfli ruxsatlar
   • QURILMA JOYLASHUVI (GPS koordinatasi) — FAQAT siz joylashuv ruxsatini bergan bo'lsangiz. Bu markaziy himoya xaritasida qurilmangiz va tahdidlar qayerda ekanini ko'rsatish uchun. DOIMIY KUZATUV YO'Q: koordinata faqat ilova ishlayotganda (skan yoki ro'yxatdan o'tish paytida) o'qiladi, fonda emas, va ~0.1 metrgacha yumaloqlanadi. Ruxsat bermasangiz — koordinata umuman yuborilmaydi.
   • Bulut serveri, har qanday internet so'rovida bo'lgani kabi, qurilmangizning IP-manzilini ko'radi va undan (GPS bo'lmasa) faqat shahar darajasida taxminiy joyni aniqlaydi.
   • XAVFLI yoki SHUBHALI deb topilgan APK FAYLNING O'ZI (50 MB gacha) — markaziy bulut serveriga (xavfsiz HTTPS) yuklanadi: uni chuqur o'rganib yangi virus signaturalari yaratish uchun. XAVFSIZ APK fayllari HECH QACHON yuklanmaydi — ulardan faqat meta-ma'lumot (yuqoridagi) statistika uchun ketadi, faylning o'zi emas.

(b) Telegram hisobotiga — FAQAT xavfli yoki shubhali APK aniqlanganda:
   • APK SHA-256 hashi, paket nomi, verdict va aniqlangan xavf signaturasi (sabab)
   • Qurilma modeli va Android versiyasi
   • APK FAYLNING O'ZI (50 MB gacha) — yangi virus signaturalarini ishlab chiqish uchun

(c) Ilova xato (crash) yuz berganda — dasturchiga xatoni tuzatishi uchun:
   • Stacktrace (kod xatosi joyi va sababi)
   • Qurilma modeli va Android versiyasi
   • KiberQalqon versiyasi va vaqt

YOQILGAN bo'lsa HAM, HECH QACHON YUBORILMAYDI:
• Xavfsiz APK FAYLLARINING o'zi (faqat meta-ma'lumoti statistika uchun ketadi — faylning o'zi emas)
• Sizning ismingiz, telefon raqamingiz, IMEI, seriya raqami, MAC-address
• Qurilmadagi boshqa ilovalar ro'yxati
• Shaxsiy fayllar (rasm, video, hujjat), kontaktlar, SMS, chat
• Internet brauzer tarixi, parollar, token'lar

NIMA UCHUN JOYLASHUV SO'RALADI:
Markaziy himoya xaritasi qaysi hududlarda qanday tahdidlar tarqalayotganini ko'rsatadi — bu yangi hujum to'lqinlarini erta aniqlash va foydalanuvchilarni ogohlantirishga yordam beradi. Joylashuvsiz ham ilova to'liq ishlaydi; u holda qurilmangiz xaritada faqat IP bo'yicha taxminiy shaharga joylashtiriladi.

NIMA UCHUN APK FAYL YUBORILADI:
Faqat hash bilan biz "bu fayl xavfli" deyishimiz mumkin, lekin uning ICHKI tuzilishini ko'rib yangi virus shablonlari yarata olmaymiz. Original fayl bilan biz boshqa foydalanuvchilarni TEZROQ himoya qila olamiz.

NIMA UCHUN CRASH YUBORILADI:
KiberQalqon dasturchi tushunmagan xatolar ilovani buzadi. Stacktrace bilan dasturchi xatoni tuzatib, yangilanish chiqaradi. Bu Firebase Crashlytics, Sentry kabi standart amaliyot.

QAYERGA YUBORILADI:
• Bulut monitoringi: KiberQalqon'ning markaziy serveriga (xavfsiz HTTPS orqali) — u himoya xaritasi, statistika va tahdid oqimini to'ldiradi; xavfli/shubhali APK namunalari esa o'rganish uchun himoyalangan saqlovga (Storage) yuklanadi.
• Telegram hisoboti: KiberQalqon rivojlantirish jamoasining Telegram boti orqali markaziy jamoatchilik xavf bazasiga.
Bu ma'lumotlar yangi viruslarni aniqlash va boshqa foydalanuvchilarni himoya qilish uchun signaturalar bazasiga qo'shiladi.

QANDAY BOSHQARILADI:
• Yoqish: pastdagi 3-galochka ("Jamoatchilik xavfsizligi...") orqali — bu MAJBURIY
• Vaqtincha pauza: Sozlamalar → "Jamoatchilik ulashish" toggle (ammo bu KiberQalqon'ning to'liq ishlashini cheklaydi)
• Allaqachon yuborilgan hash'larni o'chirish: Sozlamalar → "Yordam" orqali murojaat
• Butunlay bekor qilish: Sozlamalar → "Rozilikni qaytarib olish" → ilovadan chiqish

═══════════════════════════════
5. SIZ NIMA QILA OLASIZ
═══════════════════════════════
• Telegram telemetriyani istalgan vaqtda o'chirish: DIAGNOSTIKA → Telegram telemetriya → "Yoqish" galochkasini olib tashlash
• Skan tarixini tozalash: Sozlamalar → "Tarixni tozalash"
• Roziligingizni butunlay bekor qilish: Sozlamalar → "Rozilikni qaytarib olish"
• Ilovani o'chirish — barcha lokal ma'lumotlar yo'qoladi

═══════════════════════════════
6. INTERNET FOYDALANISHI
═══════════════════════════════
Hozir ilova internetga kiradigan vaqtlar:
• Jamoatchilik ulashish yoqilgan bo'lsa — KiberQalqon markaziy bulut serveriga (xavfsiz HTTPS): qurilma ro'yxati, skan statistikasi, himoya xaritasi va (ruxsat bergan bo'lsangiz) joylashuv
• Jamoatchilik ulashish yoqilgan bo'lsa — KiberQalqon jamoasi bot'iga (api.telegram.org): xavfli APK namunalari
• Shaxsiy Telegram telemetriya yoqilgan bo'lsa — sizning bot'ingizga (api.telegram.org)
• Kelajakda — virus signaturalarini yangilash uchun (haqida alohida ogohlantirish bo'ladi)

Boshqa hech qanday internet-trafik yo'q. Hech qanday reklama tarmoqlari, analitika SDK (Firebase, Crashlytics, Google Analytics) ishlatilmaydi.

═══════════════════════════════
7. BOLA MA'LUMOTLARI
═══════════════════════════════
13 yoshgacha bo'lgan bolalardan ma'lumot bila turib yig'ilmaydi. Agar siz ota-ona bo'lib, bolangiz KiberQalqon'dan foydalanayotganligini bilsangiz va u 13 yoshgacha bo'lsa — ilovani o'chiring.

═══════════════════════════════
8. XALQARO O'TKAZISH
═══════════════════════════════
Agar Telegram telemetriya yoki Jamoatchilik ulashish yoqilgan bo'lsa, ma'lumotlaringiz Telegram serverlariga (xalqaro joylashtirilgan) o'tkaziladi. Telegram o'zining maxfiylik siyosatiga ega — telegram.org/privacy

═══════════════════════════════
9. SIYOSATNI YANGILASH
═══════════════════════════════
Bu siyosat yangilanishi mumkin. Sezilarli o'zgarishlarda ilovani ochganda rozilik qayta so'raladi.

═══════════════════════════════
10. ALOQA
═══════════════════════════════
Sozlamalar → "Yordam" bo'limida murojaat qilish mumkin.

═══════════════════════════════
QABUL QILISH ORQALI SIZ:
• Yuqoridagi shartlar va siyosatni o'qib chiqqaningizni va tushunganingizni
• Shaxsiy Telegram telemetriya IXTIYORIY ekanligini (siz o'z bot/chat'ni kiritishingiz kerak)
• Jamoatchilik ulashish KiberQalqon'ning ASOSIY ISHLASH QISMI ekanligini va siz unga rozi ekanligingizni
• Har bir skan meta-ma'lumoti markaziy bulutga (statistika va himoya xaritasi uchun) yuborilishini
• Joylashuv ruxsatini bersangiz, qurilma GPS koordinatasi xarita uchun yuborilishini — fonda kuzatuvsiz; ruxsat bermasangiz yuborilmasligini
• Xavfli APK aniqlanganda fayl + meta-ma'lumot KiberQalqon jamoasiga yuborilishini
• Crash hodisalarida stacktrace dasturchiga yuborilishini
• Shaxsiy ma'lumot (ism, telefon raqami, IMEI, seriya raqami, kontakt, SMS, parollar) hech qachon yuborilmasligini
TASDIQLAYSIZ.
═══════════════════════════════
""".trimIndent()
