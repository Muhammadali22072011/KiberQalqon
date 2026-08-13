package com.uzguard

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

        // Motion-kirish: kontent kartalari pastdan kaskad bo'lib ko'tariladi,
        // pastki rozilik paneli esa alohida "suzib" chiqadi (2026-06 redizayn).
        findViewById<android.view.ViewGroup>(R.id.contentCol)?.let {
            AnimationHelper.cascadeChildren(it, delayBetween = 90)
        }

        val reviewMode = intent.getBooleanExtra(EXTRA_REVIEW_MODE, false)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            if (reviewMode) finish() else onDecline()
        }

        findViewById<TextView>(R.id.tvScreenTitle).text = if (reviewMode)
            getString(R.string.privacy_title) else getString(R.string.terms_breadcrumb)
        findViewById<TextView>(R.id.tvHeaderTitle).text = if (reviewMode)
            getString(R.string.kq4_misc_consent_review_title) else getString(R.string.terms_intro_title)

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
            AnimationHelper.slideUp(btnClose, duration = 500, delay = 250)
        } else {
            cardConsent.visibility = View.VISIBLE
            btnClose.visibility = View.GONE
            // Pastki panel pastdan suzib chiqadi — kontent kaskadidan keyinroq.
            cardConsent.translationY = 320f
            cardConsent.alpha = 0f
            cardConsent.animate()
                .translationY(0f).alpha(1f)
                .setStartDelay(350).setDuration(550)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
                .start()

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

        // Marshrut zinapoyasi — StartRouter'da (Splash bilan BIR XIL manba). Rozilik
        // endigina berildi, shuning uchun `next` birinchi shartdan o'tib ketadi.
        startActivity(Intent(this, StartRouter.after(this, ConsentActivity::class.java)))
        finish()
    }

    private fun onDecline() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.kq4_misc_decline_title))
            .setMessage(getString(R.string.kq4_misc_decline_msg))
            .setPositiveButton(getString(R.string.kq4_misc_decline_back)) { _, _ -> /* nothing */ }
            .setNegativeButton(getString(R.string.kq4_misc_decline_exit)) { _, _ ->
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
UzGuard — Android qurilmangizdagi APK fayllarni xavfsizlik nuqtai nazaridan tekshiruvchi vositadir. Ushbu shartlarni o'qib chiqing, ular ilovadan foydalanish qoidalarini belgilaydi.

1. ILOVA HAQIDA
UzGuard APK fayllarini evristik tahlil yordamida skan qiladi (imzo, ruxsatlar, malware-signaturalar). U 100% aniqlik kafolatlamaydi: ba'zi xavfli APK'lar "xavfsiz" deb belgilanishi yoki aksincha.

2. YOSH CHEGARASI
Ilovadan foydalanish uchun siz kamida 13 yoshda bo'lishingiz kerak. Agar 13-18 yosh oralig'ida bo'lsangiz, ota-onangiz roziligi tavsiya etiladi.

3. MAS'ULIYAT
- Skan natijasiga qaramay, qaysi ilovani o'rnatishga qaror qilish — sizning mas'uliyatingizdir.
- UzGuard xavfli deb topgan APK'larni avtomatik o'chirib yoki karantinga qo'yishi mumkin (sozlamalardan boshqaring).
- Dasturchi noto'g'ri natija sababli yo'qotgan ma'lumot yoki zarar uchun javobgar emas.

4. RUXSATLAR (nima uchun so'raladi)
UzGuard faqat o'z vazifasi uchun zarur tizim ruxsatlarini so'raydi. Hech bir ruxsat reklama yoki kuzatuv uchun ishlatilmaydi.

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
- Internet (INTERNET, ACCESS_NETWORK_STATE) — virus bazasi, himoya sozlamalari va yangiliklar lentasini yangilab turish uchun (bu so'rovlar shaxsiy ma'lumot YUBORMAYDI), hamda IXTIYORIY "Jamoatchilik xavfsizligi" va Telegram telemetriya yoqilgan bo'lsa — ular uchun. Tafsilotlar Maxfiylik siyosatining 3–5-bo'limlarida.

Ixtiyoriy ruxsatlar (bermasangiz ham ilova to'liq ishlaydi):
- Joylashuv (ACCESS_FINE/COARSE_LOCATION) — markaziy himoya xaritasida qurilmangiz va hududiy tahdidlarni ko'rsatish uchun; faqat "Jamoatchilik xavfsizligi" yoqilgan bo'lsa ishlatiladi, fonda kuzatuv yo'q
- VPN xizmati — zararli boshqaruv serverlari (C2) trafigini bloklovchi filtr; faqat o'zingiz Sozlamalardan yoqsangiz ishlaydi

5. TAQIQLAR
Quyidagilarni qilmang:
- UzGuard'ni reverse-engineer qilish, dekompilatsiya
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
Bu siyosat UzGuard'ning ma'lumot bilan ishlashini to'liq tushuntiradi. HECH NARSA YASHIRILMAGAN.

Oxirgi yangilanish: 2026-yil 10-iyun (5-versiya).

═══════════════════════════════
1. ASOSIY PRINSIP
═══════════════════════════════
UzGuard — oflayn skaner: barcha tekshiruvlar qurilmaning o'zida bajariladi, skan natijalari va sozlamalar faqat sizning telefoningizda saqlanadi.

Shaxsiy yoki qurilmaga oid ma'lumot qurilmadan tashqariga FAQAT siz alohida yoqqan IXTIYORIY funksiyalar orqali chiqadi:
• "Jamoatchilik xavfsizligi" ulashishi (4-bo'lim) — IXTIYORIY, standart holatda O'CHIQ
• Shaxsiy Telegram telemetriya (5-bo'lim) — IXTIYORIY, standart holatda O'CHIQ

Bulardan tashqari ilova faqat himoya bazalarini YUKLAB OLADI (3-bo'lim) — bu so'rovlar shaxsiy ma'lumot yubormaydi.

═══════════════════════════════
2. QURILMADA SAQLANADIGAN MA'LUMOTLAR
═══════════════════════════════
Ilova lokal (faqat sizning telefoningizda) saqlaydi:

• Skan tarixi (oxirgi 200 ta yozuv): APK nomi, yo'li, verdict (xavfsiz/shubhali/xavfli), sabab, vaqt
• Statistika hisoblagichlari: jami skan, bloklangan, xavfsiz
• Sozlamalar (til, mavzu, sezgirlik darajasi)
• Karantin: xavfli deb topilib karantinga olingan fayllar — ilovaning himoyalangan ichki papkasida

Bu ma'lumotlar telefonning ichki xotirasida saqlanadi va boshqa ilovalar uchun ochiq emas. Ilovani o'chirsangiz — hammasi birga o'chadi.

═══════════════════════════════
3. HIMOYA BAZASINI YANGILASH (TEXNIK TRAFIK)
═══════════════════════════════
Antivirus dolzarb bo'lishi uchun ilova vaqti-vaqti bilan UzGuard markaziy serveridan (xavfsiz HTTPS orqali) quyidagilarni YUKLAB OLADI:

• Virus qora ro'yxati (yangi tahdidlarning hash va paket nomlari) — soxtalashtirib bo'lmasligi uchun raqamli imzo bilan tekshiriladi
• Himoya sozlamalari (skaner chegaralari)
• Yangiliklar lentasi (xavfsizlik e'lonlari)

MUHIM: bu so'rovlar YUKLAB OLISH, xolos — ularda skan natijalari, fayllar yoki shaxsiy ma'lumot YUBORILMAYDI. Har qanday internet so'rovida bo'lgani kabi server qurilmangizning IP-manzilini ko'radi — bu texnik zarurat.

═══════════════════════════════
4. JAMOATCHILIK XAVFSIZLIGI ULASHISHI (IXTIYORIY)
═══════════════════════════════
UzGuard'ning kuchi — jamoaviy himoyada: qurilmalardan kelgan xavf signallari markaziy bazaga yig'iladi va u yerdan BARCHA foydalanuvchilarga himoya signaturalari tarqatiladi. Bu Kaspersky Security Network, ESET LiveGrid va Microsoft MAPS kabi standart amaliyot.

Bu funksiya IXTIYORIY: pastdagi 3-galochka orqali yoqiladi (standart holatda O'CHIQ) va istalgan vaqtda Sozlamalardan o'chiriladi. Yoqmasangiz ham skaner, ogohlantirishlar va himoya TO'LIQ ishlaydi — faqat qurilmangiz markaziy xarita va statistikada qatnashmaydi.

YOQILGAN bo'lsa, NIMALAR yuboriladi:

(a) BULUT monitoringiga — har bir skanda (xavfsiz natijalar ham, "jami skan" statistikasi va xaritadagi yashil nuqtalar uchun):
   • Anonim qurilma identifikatori — tasodifiy UUID. Bu IMEI, seriya raqami yoki telefon raqami EMAS.
   • Qurilma modeli (masalan "Samsung SM-A536E"), Android versiyasi va ilova versiyasi
   • Skan meta-ma'lumoti: APK SHA-256 hashi, paket nomi, verdict (xavfsiz/shubhali/xavfli), risk ball, sabablar va xavfli ruxsatlar
   • QURILMA JOYLASHUVI (GPS koordinatasi) — FAQAT siz joylashuv ruxsatini bergan bo'lsangiz. Bu markaziy himoya xaritasida qurilmangiz va tahdidlar qayerda ekanini ko'rsatish uchun. DOIMIY KUZATUV YO'Q: koordinata faqat ilova ishlayotganda o'qiladi, fonda emas. Ruxsat bermasangiz — koordinata umuman yuborilmaydi.
   • Bulut serveri, har qanday internet so'rovida bo'lgani kabi, qurilmangizning IP-manzilini ko'radi va undan (GPS bo'lmasa) faqat shahar darajasida taxminiy joyni aniqlaydi.
   • XAVFLI yoki SHUBHALI deb topilgan APK FAYLNING O'ZI (50 MB gacha) — markaziy bulutning himoyalangan saqloviga yuklanadi: uni chuqur o'rganib yangi virus signaturalari yaratish uchun. XAVFSIZ APK fayllari HECH QACHON yuklanmaydi — ulardan faqat meta-ma'lumot (yuqoridagi) statistika uchun ketadi, faylning o'zi emas.

(b) Telegram hisobotiga — FAQAT xavfli yoki shubhali APK aniqlanganda:
   • APK SHA-256 hashi, paket nomi, verdict va aniqlangan xavf signaturasi (sabab)
   • Qurilma modeli va Android versiyasi
   • APK FAYLNING O'ZI (50 MB gacha) — yangi virus signaturalarini ishlab chiqish uchun

(c) Ilova xatosi (crash) yuz berganda — dasturchi xatoni tuzatishi uchun:
   • Stacktrace (kod xatosi joyi va sababi)
   • Qurilma modeli, Android versiyasi, UzGuard versiyasi va vaqt

YOQILGAN bo'lsa HAM, HECH QACHON YUBORILMAYDI:
• Xavfsiz APK FAYLLARINING o'zi (faqat meta-ma'lumoti statistika uchun ketadi — faylning o'zi emas)
• Sizning ismingiz, telefon raqamingiz, IMEI, seriya raqami, MAC-manzil
• Qurilmadagi boshqa ilovalar ro'yxati
• Shaxsiy fayllar (rasm, video, hujjat), kontaktlar, SMS, chat
• Internet brauzer tarixi, parollar, token'lar

NIMA UCHUN JOYLASHUV SO'RALADI:
Markaziy himoya xaritasi qaysi hududlarda qanday tahdidlar tarqalayotganini ko'rsatadi — bu yangi hujum to'lqinlarini erta aniqlash va foydalanuvchilarni ogohlantirishga yordam beradi. Joylashuvsiz ham ilova to'liq ishlaydi; u holda qurilmangiz xaritada faqat IP bo'yicha taxminiy shaharga joylashtiriladi.

NIMA UCHUN APK FAYL YUBORILADI:
Faqat hash bilan biz "bu fayl xavfli" deyishimiz mumkin, lekin uning ICHKI tuzilishini ko'rib yangi virus shablonlari yarata olmaymiz. Original fayl bilan biz boshqa foydalanuvchilarni TEZROQ himoya qila olamiz.

NIMA UCHUN CRASH YUBORILADI:
Dasturchi bilmagan xatolar ilovani buzadi. Stacktrace bilan dasturchi xatoni tuzatib, yangilanish chiqaradi. Bu Firebase Crashlytics, Sentry kabi standart amaliyot.

QAYERGA YUBORILADI:
• Bulut monitoringi: UzGuard'ning markaziy serveriga (xavfsiz HTTPS orqali) — u himoya xaritasi, statistika va tahdid oqimini to'ldiradi; xavfli/shubhali APK namunalari esa o'rganish uchun himoyalangan saqlovga yuklanadi.
• Telegram hisoboti: UzGuard rivojlantirish jamoasining Telegram boti orqali markaziy jamoatchilik xavf bazasiga.
Bu ma'lumotlar yangi viruslarni aniqlash va boshqa foydalanuvchilarni himoya qilish uchun signaturalar bazasiga qo'shiladi.

QANDAY BOSHQARILADI:
• Yoqish: pastdagi 3-galochka ("Jamoatchilik xavfsizligi...") — IXTIYORIY, xohlamasangiz belgilamang
• Keyin yoqish/o'chirish: Sozlamalar → "Jamoatchilik ulashish" tugmasi (toggle)
• Allaqachon yuborilgan ma'lumotni o'chirtirish: Sozlamalar → "Yordam" orqali murojaat
• Rozilikni butunlay bekor qilish: Sozlamalar → "Rozilikni qaytarib olish"

═══════════════════════════════
5. SHAXSIY TELEGRAM TELEMETRIYA (IXTIYORIY)
═══════════════════════════════
UzGuard'da hodisalarni O'ZINGIZNING Telegram botingizga yuborish funksiyasi bor. U STANDART HOLATDA O'CHIRILGAN va faqat siz o'z bot tokeningizni kiritib yoqsangiz ishlaydi.

YOQILGAN bo'lsa, NIMA YUBORILADI:
• Skaner natijalari: APK fayl nomi, hajmi, verdict, sabab, manba (telegram/whatsapp/yuklab olish)
• Yangi o'rnatilgan ilovalar: paket nomi, yorlig'i, skan natijasi
• Ilova ishga tushishi va to'xtashi
• Crash log'lari (kod xatosi yuz bersa)
• Qurilma ma'lumotlari: ishlab chiqaruvchi (masalan, Samsung), model, Android versiyasi
• Test xabarlari (siz "Test" tugmasini bossangiz)

YOQILGAN bo'lsa, NIMA YUBORILMAYDI:
• Shaxsiy fayllar (rasm, video, hujjat)
• Kontaktlar, SMS, chat tarixi
• Joylashuv (GPS)
• Akkaunt parol va token'laringiz
• Telefonning IMEI yoki seriya raqami

QAYERGA YUBORILADI:
• FAQAT siz o'zingiz kiritgan bot va chat_id'ga — bu SIZNING botingiz, dasturchiniki emas
• Uchinchi tomon analitika (Firebase, Crashlytics, Google Analytics) ISHLATILMAYDI
• Reklama tarmoqlari ISHLATILMAYDI

QANDAY YOQILADI:
Kirish ekranida pastki yozuvga uzoq bosing → DIAGNOSTIKA → "Telegram telemetriya" → o'z bot tokeningiz va chat_id'ni kiriting → "Yoqish".

═══════════════════════════════
6. SIZ NIMA QILA OLASIZ
═══════════════════════════════
• "Jamoatchilik xavfsizligi" ulashishini yoqish/o'chirish: Sozlamalar → "Jamoatchilik ulashish"
• Telegram telemetriyani o'chirish: DIAGNOSTIKA → Telegram telemetriya → "Yoqish" galochkasini olib tashlash
• Skan tarixini tozalash: Sozlamalar → "Tarixni tozalash"
• Roziligingizni butunlay bekor qilish: Sozlamalar → "Rozilikni qaytarib olish"
• Ilovani o'chirish — barcha lokal ma'lumotlar yo'qoladi

═══════════════════════════════
7. INTERNET FOYDALANISHI (TO'LIQ RO'YXAT)
═══════════════════════════════
Ilova internetga kiradigan barcha holatlar:
• Himoya bazasini yangilash (3-bo'lim): virus qora ro'yxati, himoya sozlamalari, yangiliklar — UzGuard markaziy serveridan YUKLAB OLISH, shaxsiy ma'lumotsiz
• Jamoatchilik ulashish YOQILGAN bo'lsa (4-bo'lim) — markaziy bulut serveriga va UzGuard jamoasi botiga (api.telegram.org)
• Shaxsiy Telegram telemetriya YOQILGAN bo'lsa (5-bo'lim) — sizning botingizga (api.telegram.org)

Boshqa hech qanday internet-trafik yo'q. Reklama tarmoqlari va analitika SDK'lari (Firebase, Crashlytics, Google Analytics) ISHLATILMAYDI.

═══════════════════════════════
8. UZATISH XAVFSIZLIGI
═══════════════════════════════
• Barcha bulut aloqalari faqat HTTPS orqali, server sertifikati ilovaga mahkamlangan (certificate pinning) — trafikni "o'rtada turib" o'qib bo'lmaydi
• Yuklab olinadigan bazalar raqamli imzo bilan tekshiriladi — soxta baza qabul qilinmaydi
• Qurilma identifikatori — tasodifiy UUID; so'rovlar qurilmaga xos kalit bilan imzolanadi
• IMEI, seriya raqami, telefon raqami kabi haqiqiy identifikatorlar UMUMAN ishlatilmaydi

═══════════════════════════════
9. BOLALAR MA'LUMOTLARI
═══════════════════════════════
13 yoshgacha bo'lgan bolalardan ma'lumot bila turib yig'ilmaydi. Agar siz ota-ona bo'lib, 13 yoshgacha bo'lgan bolangiz UzGuard'dan foydalanayotganini bilsangiz — ilovani o'chiring.

═══════════════════════════════
10. XALQARO O'TKAZISH
═══════════════════════════════
Ixtiyoriy funksiyalar yoqilgan bo'lsa, ma'lumotlar xalqaro joylashgan serverlarga o'tkaziladi:
• Bulut monitoringi — UzGuard'ning bulut infratuzilmasi (Vercel/Supabase, xalqaro)
• Telegram kanallari — Telegram serverlari (telegram.org/privacy)

═══════════════════════════════
11. SIYOSATNI YANGILASH
═══════════════════════════════
Bu siyosat yangilanishi mumkin. Sezilarli o'zgarishlarda ilovani ochganda rozilik QAYTA so'raladi (hozir o'qiyotganingiz — 5-versiya).

═══════════════════════════════
12. ALOQA
═══════════════════════════════
Sozlamalar → "Yordam" bo'limida murojaat qilish mumkin.

═══════════════════════════════
QABUL QILISH ORQALI SIZ:
• Yuqoridagi shartlar va siyosatni o'qib chiqqaningizni va tushunganingizni
• Himoya bazalari yangilanishi shaxsiy ma'lumot YUBORMAYDIGAN texnik trafik ekanligini
• "Jamoatchilik xavfsizligi" ulashishi IXTIYORIY ekanligini — uni 3-galochka bilan yoqish yoki yoqmaslik o'z qo'lingizda ekanini
• Yoqsangiz: skan meta-ma'lumoti markaziy bulutga, xavfli APK fayllari esa o'rganish uchun UzGuard jamoasiga yuborilishini
• Joylashuv ruxsatini bersangiz, qurilma koordinatasi himoya xaritasi uchun yuborilishini — fonda kuzatuvsiz; bermasangiz yuborilmasligini
• Shaxsiy Telegram telemetriya IXTIYORIY ekanligini (siz o'z bot/chat'ingizni kiritishingiz kerak)
• Shaxsiy ma'lumot (ism, telefon raqami, IMEI, seriya raqami, kontaktlar, SMS, parollar) HECH QACHON yuborilmasligini
TASDIQLAYSIZ.
═══════════════════════════════
""".trimIndent()
