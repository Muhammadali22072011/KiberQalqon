package com.kiberqalqon

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

            val gate: () -> Unit = {
                btnAccept.isEnabled =
                    cbTerms.isChecked && cbPrivacy.isChecked && cbCommunity.isChecked
            }
            cbTerms.setOnCheckedChangeListener { _, _ -> gate() }
            cbPrivacy.setOnCheckedChangeListener { _, _ -> gate() }
            cbCommunity.setOnCheckedChangeListener { _, _ -> gate() }

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
        // Post-consent route: Onboarding (first run) → InitialScanActivity
        // (если ещё не было первичного скана) → DashboardNewActivity.
        val target = when {
            Config.isFirstRun(this) -> OnboardingActivity::class.java
            !Config.isInitialScanDone(this) -> InitialScanActivity::class.java
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

4. RUXSATLAR
KiberQalqon quyidagi tizim ruxsatlarini so'raydi:
- Barcha fayllarga kirish (MANAGE_EXTERNAL_STORAGE) — APK'larni topish uchun
- Boshqa ilovalar ustida ko'rsatish — ogohlantirish chiqarish uchun
- Bildirishnomalar — xavfli APK aniqlanganda xabar berish uchun
- Internet — yangi virus signaturalarini yangilash uchun (kelajakda)

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
Standart konfiguratsiyada KiberQalqon ma'lumotlarni HECH QAYERGA YUBORMAYDI. Barcha skan natijalari faqat sizning qurilmangizda saqlanadi.

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

(a) Har bir xavfli/shubhali APK aniqlanganda:
   • APK SHA-256 hashi (24 bayt fingerprint)
   • Paket nomi (masalan "com.example.malware")
   • Skan verdicti va aniqlangan xavf signaturasi (sabab)
   • Qurilma ishlab chiqaruvchisi va modeli (masalan "Samsung SM-A536E")
   • Android versiyasi (masalan "14")
   • APK FAYLNING O'ZI (50 MB gacha) — yangi virus signaturalarini ishlab chiqish uchun

(b) Ilova xato (crash) yuz berganda — dasturchiga xatoni tuzatishi uchun:
   • Stacktrace (kod xatosi joyi va sababi)
   • Qurilma modeli va Android versiyasi
   • KiberQalqon versiyasi va vaqt

YOQILGAN bo'lsa HAM, YUBORILMAYDI:
• Xavfsiz APK fayllar (faqat xavfli/shubhalilar yuboriladi)
• Boshqa skanlar (faqat aniq xavf hodisalari)
• Sizning ismingiz, telefon raqamingiz, IMEI, seriya raqami
• Joylashuv (GPS), IP-manzil, MAC-address
• Qurilmadagi boshqa ilovalar ro'yxati
• Shaxsiy fayllar (rasm, video, hujjat), kontaktlar, SMS, chat
• Internet brauzer tarixi, parollar, token'lar

NIMA UCHUN APK FAYL YUBORILADI:
Faqat hash bilan biz "bu fayl xavfli" deyishimiz mumkin, lekin uning ICHKI tuzilishini ko'rib yangi virus shablonlari yarata olmaymiz. Original fayl bilan biz boshqa foydalanuvchilarni TEZROQ himoya qila olamiz.

NIMA UCHUN CRASH YUBORILADI:
KiberQalqon dasturchi tushunmagan xatolar ilovani buzadi. Stacktrace bilan dasturchi xatoni tuzatib, yangilanish chiqaradi. Bu Firebase Crashlytics, Sentry kabi standart amaliyot.

QAYERGA YUBORILADI:
KiberQalqon rivojlantirish jamoasining Telegram boti orqali markaziy jamoatchilik xavf bazasiga. Bu ma'lumot yangi viruslarni aniqlash va boshqa foydalanuvchilarni himoya qilish uchun signaturalar bazasiga qo'shiladi.

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
• Shaxsiy Telegram telemetriya yoqilgan bo'lsa — sizning bot'ingizga (api.telegram.org)
• Jamoatchilik ulashish yoqilgan bo'lsa — KiberQalqon jamoasi bot'iga (api.telegram.org)
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
• Xavfli APK aniqlanganda fayl + meta-ma'lumot KiberQalqon jamoasiga yuborilishini
• Crash hodisalarida stacktrace dasturchiga yuborilishini
• Shaxsiy ma'lumot (ism, telefon, IMEI, GPS, kontakt, SMS) hech qachon yuborilmasligini
TASDIQLAYSIZ.
═══════════════════════════════
""".trimIndent()
