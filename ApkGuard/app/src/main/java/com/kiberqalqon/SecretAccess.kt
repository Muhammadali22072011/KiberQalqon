package com.kiberqalqon

import android.content.Context
import android.content.SharedPreferences

/**
 * Maxfiy kirish (rollar tizimi) — "4 ta qulf" mantig'ining 1-bosqichi.
 *
 * Sozlamalar ekranida 4 ta rangli dumaloq tugma turadi (oddiy foydalanuvchiga
 * bezak kabi ko'rinadi). Faqat to'g'ri KETMA-KETLIKDA bossangina maxfiy kirish
 * ekrani (SecretAccessActivity) ochiladi. Noto'g'ri tegsa — jimgina nolga tushadi,
 * hech qanday belgi ko'rsatilmaydi (stealth).
 *
 * Keyingi qulflar (SecretAccessActivity ichida):
 *   2) soatlik kod (faqat egada — o'z veb-panelidan oladi va ishonchli xodimga beradi)
 *   3) login + parol
 *   4) server rolga mos panel/komponentlarni qaytaradi (RoleAccessClient)
 */
object SecretAccess {

    // ---- 4 tugma ranglari (chapdan o'ngga: indeks 0..3) --------------------
    // Diqqat: bu ranglar UI uchun. Ketma-ketlik INDEKS bo'yicha (rang nomi emas).
    val BUTTON_COLORS = intArrayOf(
        0xFF2D7FF9.toInt(), // 0 — ko'k (blue)
        0xFF25C26E.toInt(), // 1 — yashil (green)
        0xFFFF9F1C.toInt(), // 2 — to'q sariq (orange)
        0xFFFF4D5E.toInt(), // 3 — qizil (red)
    )

    /**
     * MAXFIY KETMA-KETLIK — egasi shuni biladi va ishonchli xodimga aytadi.
     * O'zgartirmoqchi bo'lsang shu ro'yxatni tahrirla (indekslar 0..3):
     *   0=ko'k, 1=yashil, 2=to'q sariq, 3=qizil
     * Hozirgi kombinatsiya:  ko'k → qizil → yashil → to'q sariq → ko'k
     */
    val SEQUENCE = intArrayOf(0, 3, 1, 2, 0)

    // Tugmalar orasidagi maksimal pauza (ms). Bundan ko'p kutilsa — kombinatsiya
    // nolga tushadi (tasodifiy tegishlar yig'ilib qolmasin).
    const val TAP_TIMEOUT_MS = 3_000L

    // ---- Kombinatsiya holati (state machine) ------------------------------
    private var progress = 0
    private var lastTapAt = 0L

    /**
     * Bitta tugma bosilganini qayd etadi.
     * @return true — to'liq ketma-ketlik to'g'ri yakunlandi (kirish ochilsin).
     */
    fun onTap(index: Int): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastTapAt > TAP_TIMEOUT_MS) progress = 0
        lastTapAt = now

        // To'g'ri keyingi tugma?
        if (index == SEQUENCE[progress]) {
            progress++
            if (progress >= SEQUENCE.size) {
                progress = 0
                return true
            }
            return false
        }

        // Noto'g'ri — nolga tushiramiz. Lekin agar shu tegish ketma-ketlikning
        // BOSHIni qaytasa (masalan birinchi element), uni 1-bosqich deb hisoblaymiz.
        progress = if (index == SEQUENCE[0]) 1 else 0
        return false
    }

    fun reset() {
        progress = 0
        lastTapAt = 0L
    }

    // ---- Sessiya (muvaffaqiyatli login natijasi) --------------------------
    // Server qaytargan rol/huquqlarni shu yerda saqlaymiz. Komponentlar/panel
    // shu ma'lumotga qarab chiziladi. Sodda KV — keyin kengaytiramiz.
    private const val PREFS = "kiberqalqon_secret"
    private const val KEY_ROLE = "role_name"
    private const val KEY_PERMS = "role_perms"
    private const val KEY_COMPONENTS = "role_components"
    private const val KEY_GRANTED_AT = "granted_at"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveSession(ctx: Context, role: String, perms: List<String>, components: List<String>) {
        prefs(ctx).edit()
            .putString(KEY_ROLE, role)
            .putStringSet(KEY_PERMS, perms.toSet())
            .putStringSet(KEY_COMPONENTS, components.toSet())
            .putLong(KEY_GRANTED_AT, System.currentTimeMillis())
            .apply()
    }

    fun roleName(ctx: Context): String? = prefs(ctx).getString(KEY_ROLE, null)
    fun permissions(ctx: Context): Set<String> = prefs(ctx).getStringSet(KEY_PERMS, emptySet()) ?: emptySet()
    fun components(ctx: Context): Set<String> = prefs(ctx).getStringSet(KEY_COMPONENTS, emptySet()) ?: emptySet()
    fun hasSession(ctx: Context): Boolean = roleName(ctx) != null

    fun clearSession(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
