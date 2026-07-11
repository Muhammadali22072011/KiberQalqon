package com.uzguard

import android.content.Context
import android.util.Log
import org.json.JSONArray

/**
 * YARA-lite bulut qoida paketlari ([RuleEngine] uchun) va bulut "known-good" (oq)
 * ro'yxatini SharedPreferences'ga saqlaydigan do'kon.
 *
 * Bu — [CloudBlacklist] qatoridagi ikkinchi manba: signed `/api/threats?feed=1` konverti
 * endi `rv`/`gv` (epoch versiyalar) + `rules`/`good` massivlarini ham olib keladi. Imzo
 * ([CloudBlacklist.verifyAndDecode]) allaqachon tekshirilgan — bu klass faqat DEKODLANGAN
 * (ishonchli) JSON'ni oladi va saqlaydi.
 *
 * ====== OLTIN QOIDALAR ======
 *  • Rollback-guard'lar MUSTAQIL: `rv` (rules) va `gv` (good) alohida baholanadi —
 *    aynan [CloudBlacklist]'ning KEY_V/KEY_DV idiomasi kabi (remote >= stored → qo'llanadi,
 *    keyin stored oshiriladi; pastroq versiya = rollback → rad etiladi).
 *  • Xom JSON massivlari ham saqlanadi → [loadCached] sovuq startda (tarmoqsiz) tiklaydi.
 *  • Hech qachon throw QILMAYDI (fail-soft) — skan issiq yo'lini buzmaydi.
 *
 * Rules faqat DETEKTSIYANI KUCHAYTIRADI (qo'shadi). `good` esa DOWNGRADE-ONLY: u faqat
 * yumshoq (soft) signal verdiktini SUSPICIOUS→SAFE tushira oladi, hech qachon qat'iy (hard)
 * DANGER'ni bosa olmaydi — bu mantiq [ApkScanner] tomonida (qalqon reputatsiyadan keyin) amalga oshadi.
 */
object RuleStore {

    private const val TAG = "RuleStore"
    private const val PREFS = "uzguard_rules"
    private const val KEY_RV = "rs_rv"             // rules versiyasi (rollback-guard) — MUSTAQIL
    private const val KEY_GV = "rs_gv"             // good-list versiyasi (rollback-guard) — MUSTAQIL
    private const val KEY_RULES_JSON = "rs_rules"  // xom rules massivi (sovuq start uchun)
    private const val KEY_GOOD_JSON = "rs_good"    // xom good massivi (sovuq start uchun)

    /**
     * Bitta qoida. `needles` — kichik harfli, `target` bo'yicha tanlanadigan pichoq(lar)i;
     * `minHits`=0 → BARCHA needle'lar mos kelishi shart, aks holda kamida `minHits` ta.
     * `advisory`=true → hit hech qachon DANGER'ga majburlamaydi (faqat SUSPICIOUS hissa).
     */
    data class Rule(
        val id: String,
        val family: String,
        val severity: String,   // critical | high | medium | low
        val target: String,     // dex_string | manifest | path
        val needles: List<String>,
        val minHits: Int,
        val advisory: Boolean,
    )

    /** Bulut "known-good" yozuvi. `cert` bo'sh bo'lsa — faqat paket nomi bo'yicha mos keladi. */
    data class GoodEntry(
        val pkg: String,
        val cert: String,       // signing cert sha256 (lowercase hex) yoki bo'sh
    )

    @Volatile private var rulesCache: List<Rule> = emptyList()
    @Volatile private var goodCache: List<GoodEntry> = emptyList()

    /** Joriy (xotiradagi) qoidalar. Hech qachon null qaytarmaydi. */
    fun rules(): List<Rule> = rulesCache

    /** Joriy (xotiradagi) known-good yozuvlar. Hech qachon null qaytarmaydi. */
    fun good(): List<GoodEntry> = goodCache

    /**
     * Yangi rules massivini rollback-guard bilan qo'llaydi. `rv` — remote epoch versiyasi.
     * `rv >= stored` bo'lsagina qo'llanadi (aks holda eski/rollback → e'tiborsiz). Fail-soft.
     */
    fun applyRules(ctx: Context, arr: JSONArray?, rv: Int) {
        try {
            val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // MUSTAQIL rollback-guard (KEY_V idiomasi): faqat remote >= stored bo'lsa qo'llanadi.
            if (rv < sp.getInt(KEY_RV, -1)) {
                Log.w(TAG, "rules versiyasi eski (rv=$rv) — rad etildi")
                return
            }
            val parsed = parseRules(arr)
            rulesCache = parsed
            sp.edit()
                .putString(KEY_RULES_JSON, (arr ?: JSONArray()).toString())
                .putInt(KEY_RV, rv)
                .apply()
            Log.i(TAG, "rules qo'llandi (rv=$rv, count=${parsed.size})")
        } catch (e: Throwable) {
            Log.w(TAG, "applyRules failed", e)
        }
    }

    /**
     * Yangi good (known-good) massivini rollback-guard bilan qo'llaydi. `gv` — remote epoch versiyasi.
     * `gv >= stored` bo'lsagina qo'llanadi. Fail-soft.
     */
    fun applyGood(ctx: Context, arr: JSONArray?, gv: Int) {
        try {
            val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (gv < sp.getInt(KEY_GV, -1)) {
                Log.w(TAG, "good versiyasi eski (gv=$gv) — rad etildi")
                return
            }
            val parsed = parseGood(arr)
            goodCache = parsed
            sp.edit()
                .putString(KEY_GOOD_JSON, (arr ?: JSONArray()).toString())
                .putInt(KEY_GV, gv)
                .apply()
            Log.i(TAG, "good qo'llandi (gv=$gv, count=${parsed.size})")
        } catch (e: Throwable) {
            Log.w(TAG, "applyGood failed", e)
        }
    }

    /**
     * Keshlangan (avval qo'llangan) rules/good'ni xotiraga tiklaydi. Tarmoq YO'Q — App.onCreate /
     * skan boshida chaqirish uchun. Imzo bu bosqichda tekshirilmaydi: konvert allaqachon
     * [CloudBlacklist.loadCached] tomonidan qayta imzo bo'yicha tekshirilgach applyRules/applyGood
     * chaqiriladi; bu funksiya faqat process qayta ishga tushganda xom keshni parse qiladi.
     */
    fun loadCached(ctx: Context) {
        try {
            val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            sp.getString(KEY_RULES_JSON, null)?.let { raw ->
                rulesCache = parseRules(JSONArray(raw))
            }
            sp.getString(KEY_GOOD_JSON, null)?.let { raw ->
                goodCache = parseGood(JSONArray(raw))
            }
            Log.i(TAG, "loadCached: rules=${rulesCache.size} good=${goodCache.size}")
        } catch (e: Throwable) {
            Log.w(TAG, "loadCached failed", e)
        }
    }

    private fun parseRules(arr: JSONArray?): List<Rule> {
        if (arr == null) return emptyList()
        val out = ArrayList<Rule>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").trim()
            if (id.isEmpty()) continue
            val nArr = o.optJSONArray("n")
            val needles = ArrayList<String>()
            if (nArr != null) {
                for (j in 0 until nArr.length()) {
                    val n = nArr.optString(j, "").trim().lowercase()
                    if (n.isNotEmpty()) needles.add(n)
                }
            }
            if (needles.isEmpty()) continue  // needlesiz qoida — foydasiz, tashlab yuboramiz
            val target = o.optString("t", "").trim().lowercase()
            if (target != "dex_string" && target != "manifest" && target != "path") continue
            out.add(
                Rule(
                    id = id,
                    family = o.optString("f", "Cloud.rule").ifBlank { "Cloud.rule" },
                    severity = o.optString("s", "low").trim().lowercase(),
                    target = target,
                    needles = needles,
                    minHits = o.optInt("m", 0).coerceAtLeast(0),
                    advisory = o.optInt("adv", 0) == 1,
                )
            )
        }
        return out
    }

    private fun parseGood(arr: JSONArray?): List<GoodEntry> {
        if (arr == null) return emptyList()
        val out = ArrayList<GoodEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pkg = o.optString("p", "").trim().lowercase()
            if (pkg.isEmpty()) continue
            val cert = o.optString("c", "").trim().lowercase()
            out.add(GoodEntry(pkg = pkg, cert = cert))
        }
        return out
    }
}
