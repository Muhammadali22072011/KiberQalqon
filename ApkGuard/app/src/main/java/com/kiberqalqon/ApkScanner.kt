/*
 *  #### #  # #### #  #    #  # #### #  #     ← MIDDLE (yadro / engine)
 *  #    #  # #    # #     #  # #  # #  #
 *  ###  #  # #    ##      #### #  # #  #
 *  #    #  # #    # #       #  #  # #  #
 *  #    #### #### #  #      #  #### ####
 *  Bu kod Muhammadaliniki. O'g'irlama. — KiberQalqon
 */
package com.kiberqalqon

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.ArrayDeque
import java.util.zip.ZipFile

data class ScanResult(
    val verdict: Verdict,
    val reason: String,
    val details: List<String>,
    val dangerousPermissions: List<String>,
    val malwareSignatures: List<String>,
    val durationMs: Long = 0,
    // ML advisory ([MlRiskModel]): 0..1 ehtimollik. -1 = hisoblanmagan (erta-chiqish
    // yo'llari, kesh-hit). Verdictga TA'SIR QILMAYDI — faqat details/telemetriya uchun.
    val mlRisk: Double = -1.0
) {
    enum class Verdict { SAFE, SUSPICIOUS, DANGER }
}

object ApkScanner {

    private const val TAG = "ApkScanner"
    private const val APK_MIME = "application/vnd.android.package-archive"

    // СТАРЫЙ список оставлен в [ObfuscatedSignatures] (через hash+XOR — `strings` не покажет).
    // Универсальные слова ("trojan", "scam", "fraud", "backdoor", "rootkit") УБРАНЫ —
    // они дают false-positive на ЧУЖИХ антивирусных либах. Современная сигнатурная
    // защита делается через специфичные IoC (URL, bot-имена, endpoints) + DEX patterns
    // + permission combos. См. DexPatternAnalyzer / PermissionCombos / ObfuscatedSignatures.

    private val DANGEROUS_PERMISSIONS = setOf(
        "android.permission.SEND_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.READ_SMS",
        "android.permission.CALL_PHONE",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.READ_CALL_LOG",
        "android.permission.WRITE_CALL_LOG",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.REQUEST_INSTALL_PACKAGES"
    )

    /**
     * Быстрый поиск APK: MediaStore + fallback рекурсивный обход
     */
    fun findApkFiles(context: Context, timeBudgetMs: Long = 10_000): List<ApkItem> {
        val start = SystemClock.elapsedRealtime()
        val result = mutableListOf<ApkItem>()
        val seenPaths = HashSet<String>(2048)

        // 1) MediaStore (быстро)
        try {
            queryMediaStoreApks(context, result, seenPaths, start, timeBudgetMs)
            Log.d(TAG, "MediaStore нашёл: ${result.size} APK")
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore query failed", e)
        }

        // 2) Fallback: рекурсивный обход
        try {
            val roots = buildFoldersToScan()
            for (folder in roots) {
                if (SystemClock.elapsedRealtime() - start > timeBudgetMs) break
                scanFolderIterative(
                    root = folder,
                    result = result,
                    seenPaths = seenPaths,
                    start = start,
                    timeBudgetMs = timeBudgetMs,
                    maxDirs = 2_000,
                    maxFiles = 50_000
                )
            }
            Log.d(TAG, "После fallback: ${result.size} APK")
        } catch (e: Exception) {
            Log.e(TAG, "Fallback scan failed", e)
        }

        // Никогда не показываем сам KiberQalqon в списке — иначе юзер может его случайно удалить.
        return result
            .filterNot { SelfGuard.isOwnApk(context, it.path) }
            .sortedByDescending { it.file.lastModified() }
    }

    private fun queryMediaStoreApks(
        context: Context,
        result: MutableList<ApkItem>,
        seenPaths: MutableSet<String>,
        start: Long,
        timeBudgetMs: Long
    ) {
        val cr = context.contentResolver

        val collection: Uri =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Files.getContentUri("external")
            }

        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.RELATIVE_PATH
        )

        val selection = """
            (${MediaStore.Files.FileColumns.MIME_TYPE}=? OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?)
        """.trimIndent()

        val selectionArgs = arrayOf(APK_MIME, "%.apk")
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        cr.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idxData = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            val idxName = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val idxSize = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val idxRel = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)

            // Без имени дальнейшая работа невозможна — выходим, чтобы не словить CursorIndexOutOfBoundsException.
            if (idxName < 0) {
                Log.w(TAG, "MediaStore: DISPLAY_NAME column missing")
                return
            }

            while (cursor.moveToNext()) {
                if (SystemClock.elapsedRealtime() - start > timeBudgetMs) break

                val name = cursor.getString(idxName) ?: continue
                val size = if (idxSize >= 0) cursor.getLong(idxSize) else 0L

                val absPath: String? = if (idxData >= 0) cursor.getString(idxData) else null
                val relPath: String? = if (idxRel >= 0) cursor.getString(idxRel) else null

                val file = when {
                    !absPath.isNullOrBlank() -> File(absPath)
                    !relPath.isNullOrBlank() -> {
                        // relPath из MediaStore уже заканчивается на "/", но подстраховаться надо.
                        val rel = if (relPath.endsWith("/")) relPath else "$relPath/"
                        File(Environment.getExternalStorageDirectory(), rel + name)
                    }
                    else -> null
                } ?: continue

                if (!file.name.endsWith(".apk", ignoreCase = true)) continue
                if (!file.exists() || !file.isFile) continue

                val path = file.absolutePath
                if (seenPaths.add(path)) {
                    result.add(
                        ApkItem(
                            file = file,
                            name = file.name,
                            path = path,
                            sizeBytes = if (size > 0) size else file.length()
                        )
                    )
                }
            }
        }
    }

    private fun buildFoldersToScan(): List<File> {
        val storage = Environment.getExternalStorageDirectory()
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        return listOfNotNull(
            downloads,
            File(storage, "Telegram/Telegram Documents"),
            File(storage, "Telegram"),
            File(storage, "WhatsApp/Media"),
            File(storage, "Android/media/org.telegram.messenger"),
            File(storage, "Android/media/com.whatsapp/WhatsApp/Media"),
            File(storage, "Bluetooth")
        ).distinctBy { it.absolutePath }
    }

    private fun scanFolderIterative(
        root: File,
        result: MutableList<ApkItem>,
        seenPaths: MutableSet<String>,
        start: Long,
        timeBudgetMs: Long,
        maxDirs: Int,
        maxFiles: Int
    ) {
        if (!root.exists()) return
        if (!root.canRead()) {
            Log.w(TAG, "No read access: ${root.absolutePath}")
            return
        }

        val queue = ArrayDeque<File>()
        queue.add(root)

        var dirsScanned = 0
        var filesChecked = 0

        while (queue.isNotEmpty()) {
            if (SystemClock.elapsedRealtime() - start > timeBudgetMs) return
            if (dirsScanned++ > maxDirs) return
            val dir = queue.removeFirst()

            val children = try {
                dir.listFiles()
            } catch (_: Exception) {
                null
            } ?: continue

            for (f in children) {
                if (SystemClock.elapsedRealtime() - start > timeBudgetMs) return
                if (filesChecked++ > maxFiles) return

                try {
                    if (f.isDirectory) {
                        val name = f.name.lowercase()
                        if (name == "android" && f.absolutePath == Environment.getExternalStorageDirectory().resolve("Android").absolutePath) {
                            continue
                        }
                        queue.add(f)
                    } else if (f.isFile && f.name.endsWith(".apk", ignoreCase = true)) {
                        val path = f.absolutePath
                        if (seenPaths.add(path)) {
                            result.add(
                                ApkItem(
                                    file = f,
                                    name = f.name,
                                    path = path,
                                    sizeBytes = f.length()
                                )
                            )
                        }
                    }
                } catch (_: Exception) {
                    // игнорируем проблемы отдельных файлов
                }
            }
        }
    }

    /**
     * Грубое определение источника APK по пути — для context-aware уведомлений.
     * "Этот файл из Telegram — будь осторожен" приятнее, чем сухой verdict.
     */
    fun inferSource(apkPath: String): String? {
        val lower = apkPath.lowercase()
        return when {
            "telegram" in lower -> "telegram"
            "whatsapp" in lower -> "whatsapp"
            "/download" in lower || "/downloads/" in lower -> "download"
            "/cache/shared/" in lower || "/sharereceiver/" in lower -> "share"
            "bluetooth" in lower -> "bluetooth"
            else -> null
        }
    }

    /** MlRiskModel darajasi — foydalanuvchiga ko'rinadigan matn faqat o'zbekcha. */
    private fun mlBandUz(p: Double): String = when (MlRiskModel.band(p)) {
        "critical" -> "juda yuqori"
        "high" -> "yuqori"
        "medium" -> "o'rtacha"
        else -> "past"
    }

    /** Размер файла в человекочитаемом формате (B/KB/MB). */
    internal fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1fKB".format(kb)
        val mb = kb / 1024.0
        return "%.1fMB".format(mb)
    }

    /**
     * Сигнатуры-доменные имена ("dashapp-v2.org") — точное совпадение.
     * Общие слова ("trojan", "scam") — с word-boundary, чтобы "trojandetected"
     * в чужой антивирусной либе не давало false positive.
     */
    @androidx.annotation.VisibleForTesting
    internal fun matchesSignature(text: String, sig: String): Boolean {
        val isWordlike = sig.all { it.isLetterOrDigit() || it == '_' }
        return if (isWordlike && sig.length < 16) {
            Regex("(?i)(?<![A-Za-z0-9_])${Regex.escape(sig)}(?![A-Za-z0-9_])").containsMatchIn(text)
        } else {
            text.contains(sig, ignoreCase = true)
        }
    }

    /**
     * "Tasodifiy" ko'rinishdagi package name. Bu malware generatorlari uchun klassik belgi:
     * com.lzthzvxte.xazoalzxhr — undosh harflar tasodifiy, hech qanday so'z yo'q.
     * Real ilovalar com.facebook.katana, com.whatsapp, uz.mobiuz.android — tanimas so'zlar.
     *
     * Heuristic:
     *  - Package nameda 2+ segment bo'lsin (com.X.Y)
     *  - Segmentlar 4+ belgi (qisqa segment'larda ham — masalan "pyw.kzxwc" — virus belgisi)
     *  - Unli harflar nisbati < 25% (tasodifiy consonant cluster belgisi)
     *  - Yoki ketma-ket 4+ undosh harf bor (xazoalzxhr → "lzxhr", kzxwc → "kzxwc")
     *
     * pyw.kzxwc (Uzbek-dropper.vudgi) tipidagi qisqa segment'larni qo'lga olish uchun
     * minimal uzunlik 6 → 4 ga tushirildi. False-positive xavfini kamaytirish uchun
     * "real prefix" oq ro'yxati (com/org/io/net/uz/edu/gov) qo'shildi — bular tekshirilmaydi.
     */
    private val SAFE_TLD_SEGMENTS = setOf(
        "com", "org", "net", "io", "uz", "ru", "eu", "us", "co", "uk", "in", "de", "fr",
        "edu", "gov", "mil", "app", "dev", "ai",
        // Common app prefixes (after TLD)
        "google", "android", "androidx", "support", "fb", "facebook", "telegram", "whatsapp",
    )

    @androidx.annotation.VisibleForTesting
    internal fun looksRandomPackageName(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val parts = pkg.split('.')
        if (parts.size < 2) return false
        // Tashlangan TLD/known prefix'lar — ular qisqa va xavfsiz.
        val candidates = parts.filter { it.length >= 4 && it.lowercase() !in SAFE_TLD_SEGMENTS }
        if (candidates.isEmpty()) return false
        val vowels = "aeiouy".toSet()
        var hits = 0
        for (seg in candidates) {
            val lower = seg.lowercase()
            val vowelCount = lower.count { it in vowels }
            val vowelRatio = vowelCount.toDouble() / lower.length

            // Ketma-ket undoshlarni topish (run of consonants).
            var maxConsRun = 0
            var run = 0
            for (c in lower) {
                if (c.isLetter() && c !in vowels) {
                    run++
                    if (run > maxConsRun) maxConsRun = run
                } else {
                    run = 0
                }
            }

            // Shannon entropy belgilar ustida. Sof tasodifiy harflarda ≈ 4.0-4.5,
            // ingliz so'zlarida ≈ 2.5-3.5 (zipf-distribution). 3.7+ = shubha,
            // 4.0+ = juda kuchli signal (xazoalzxhr ≈ 3.8, messenger ≈ 2.9).
            val entropy = shannonEntropyChars(lower)

            // Qisqa segment'lar (4-5 belgi) uchun consonant-run threshold 3 ga tushdi,
            // chunki "pyw" / "kzxwc" da maxConsRun = 3 (kzx) va 5 (kzxwc).
            val consRunThreshold = if (lower.length <= 5) 3 else 4

            // Kombinatsiya: agar 2+ signalga to'g'ri kelsa — hit.
            val signals = listOf(
                vowelRatio < 0.25,
                maxConsRun >= consRunThreshold,
                entropy >= 3.7,
            ).count { it }
            if (signals >= 2) hits++
        }
        return hits >= 1
    }

    /** Shannon entropy в bit'akh dlya stroki simvolov. 0 = vse odinakovo, log2(N) = ravnomerno. */
    @androidx.annotation.VisibleForTesting
    internal fun shannonEntropyChars(s: String): Double {
        if (s.isEmpty()) return 0.0
        val counts = HashMap<Char, Int>(32)
        for (c in s) counts[c] = (counts[c] ?: 0) + 1
        val n = s.length.toDouble()
        var h = 0.0
        for (c in counts.values) {
            val p = c / n
            h -= p * (kotlin.math.ln(p) / kotlin.math.ln(2.0))
        }
        return h
    }

    /**
     * Skan natijasini yakuniy qayd etish: Statistika (7-kunlik grafik shu yerda yangilanadi),
     * Telegram telemetriya, community report, ScanHistory, ScanCache, widget va ProtectionService.
     *
     * WHY: ilgari bu mantiq faqat to'liq pipeline oxirida (scan() ichida) bajarilardi.
     * "Erta chiqish" verdikt'lari — hash mosligi, ZIP-shifrlash evasion, qora ro'yxatdagi
     * imzo/paket, ikonka taqlidi va h.k. → darhol DANGER — bu blokga umuman yetib bormasdi.
     * Natijada eng aniq tahdidlar statistikaga ham, tarixга ham tushmas, telegramга
     * xabar ketmasdi va Statistika ekranidagi grafik bo'm-bo'sh ko'rinardi. Endi har bir
     * verdict shu yagona nuqtadan o'tadi (kesh-hit va o'z-o'zini skanlash bundan mustasno).
     *
     * @param cache false bo'lsa natija kesh'ga yozilmaydi — vaqtinchalik skan xatosi (catch)
     *              keyingi safar qayta sinab ko'rilishi uchun.
     */
    private fun finalizeResult(
        context: Context,
        apkPath: String,
        inResult: ScanResult,
        certFingerprint: String? = null,
        cache: Boolean = true,
        scanStartNs: Long? = null,
    ): ScanResult {
        // Skan davomiyligini real wall-clock bo'yicha o'lchaymiz (System.nanoTime) —
        // UI animatsiyasi vaqtidan (AutoScanActivity ≥800 ms) mustaqil. Cloud telemetriya
        // va kesh shu durationMs bilan yoziladi; panel'dagi "tezlik" grafigi shundan oziqlanadi.
        val durationMs = if (scanStartNs != null) {
            ((System.nanoTime() - scanStartNs) / 1_000_000L).coerceAtLeast(0L)
        } else {
            inResult.durationMs
        }
        val result = inResult.copy(durationMs = durationMs)
        val verdict = result.verdict
        val reason = result.reason

        try {
            Statistics.incrementScanned(context)
            when (verdict) {
                ScanResult.Verdict.DANGER -> Statistics.incrementBlocked(context)
                ScanResult.Verdict.SAFE -> Statistics.incrementSafe(context)
                else -> {}
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Statistics update failed", e)
        }

        // Шлём результат скана в Telegram-телеметрию (текст + при включенной опции, сам APK файл).
        try {
            val f = File(apkPath)
            val verdictIcon = when (verdict) {
                ScanResult.Verdict.DANGER -> "🚫"
                ScanResult.Verdict.SUSPICIOUS -> "⚠️"
                ScanResult.Verdict.SAFE -> "✅"
            }
            val source = inferSource(apkPath) ?: "?"
            TelemetryReporter.report(
                context, "SCAN",
                "$verdictIcon Skaner natijasi:\n" +
                "📦 ${f.name}\n" +
                "Xulosa: ${TelemetryReporter.verdictUz(verdict.name)}\n" +
                "Sabab: $reason\n" +
                "Manba: $source\n" +
                "Hajm: ${humanSize(f.length())}\n" +
                "Vaqt: ${result.durationMs} ms" +
                (if (result.mlRisk >= 0) "\nAI xavf bahosi: ${(result.mlRisk * 100).toInt()}% (${mlBandUz(result.mlRisk)})" else "")
            )
            // Vyspecializovannye sobytiya — chtoby user mog filtrovat' v gruppe.
            if (verdict == ScanResult.Verdict.DANGER) {
                val pkg = try {
                    context.packageManager.getPackageArchiveInfo(apkPath, 0)?.packageName ?: "?"
                } catch (_: Throwable) { "?" }
                TelemetryReporter.reportThreat(
                    context,
                    pkg = pkg,
                    label = f.name,
                    verdict = verdict.name,
                    reason = reason,
                    hash = certFingerprint
                )
            }
            if (result.dangerousPermissions.size >= 3) {
                val pkg = try {
                    context.packageManager.getPackageArchiveInfo(apkPath, 0)?.packageName ?: "?"
                } catch (_: Throwable) { "?" }
                TelemetryReporter.reportPermissionAbuse(context, pkg, f.name, result.dangerousPermissions)
            }
            // Если включен toggle «Skanerdan keyin APK yuborish» — кидаем файл в группу.
            // По умолчанию шлём только DANGER/SUSPICIOUS, чтобы не засорять чат безопасными.
            if (TelegramBot.isSendApkEnabled(context) && verdict != ScanResult.Verdict.SAFE) {
                val caption = "$verdictIcon ${f.name}\nXulosa: ${TelemetryReporter.verdictUz(verdict.name)}\nSabab: ${reason.take(200)}\nManba: $source"
                TelegramBot.sendDocument(context, f, caption)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Telemetry report failed", e)
        }

        // Community threat sharing: только если юзер отдельно opt-in (см. ConsentActivity),
        // и только для DANGER/SUSPICIOUS. SAFE никогда не шлётся.
        try {
            CommunityReportClient.reportThreat(context, apkPath, result)
        } catch (e: Throwable) {
            Log.w(TAG, "Community report failed", e)
        }

        // Markaziy panel/xarita uchun strukturali telemetriya (Vercel + Supabase).
        // Telegram hisobotidan farqli — HAR BIR skan (SAFE ham) yuboriladi, chunki
        // "jami skan" va xaritadagi yashil nuqtalar shu orqali hisoblanadi.
        // Bir xil opt-in (hasUserConsent + hasCommunityShareConsent) bilan himoyalangan.
        try {
            CloudTelemetry.uploadScan(context, apkPath, result)
        } catch (e: Throwable) {
            Log.w(TAG, "Cloud telemetry failed", e)
        }

        // Логируем в историю — критическая защита: даже если SharedPreferences упадёт,
        // показ результата не должен сломаться.
        try {
            val f = File(apkPath)
            ScanHistory.add(
                context,
                ScanHistory.Entry(
                    timestamp = System.currentTimeMillis(),
                    apkName = f.name,
                    apkPath = apkPath,
                    verdict = verdict,
                    reason = reason,
                    explanationKeys = ScanHistory.explanationKeysFor(result),
                    source = inferSource(apkPath)
                )
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to log scan", e)
        }

        // Keshga saqlash — keyingi safargi shu fayl uchun skan tezda kesh'dan qaytadi
        // (telemetry/history qayta yozilmaydi, foydalanuvchi spam ko'rmaydi).
        if (cache) {
            try {
                ScanCache.put(context, apkPath, result)
            } catch (e: Throwable) {
                Log.w(TAG, "ScanCache.put failed", e)
            }
        }

        // Bosh ekrandagi widget'ni yangilash — agar foydalanuvchi widget qo'ygan
        // bo'lsa, status nuqtasi va oxirgi skan vaqti darhol o'zgaradi.
        try {
            KqWidgetProvider.refreshAll(context)
        } catch (e: Throwable) {
            Log.w(TAG, "Widget refresh failed", e)
        }

        // Doimiy himoya bildirishnomasini ham yangilash — agar yangi xavfli APK
        // topilgan bo'lsa, status bar'dagi yozuv "N ta xavfli fayl topildi"ga o'tadi.
        try {
            ProtectionService.refresh(context)
        } catch (e: Throwable) {
            Log.w(TAG, "ProtectionService refresh failed", e)
        }

        return result
    }

    fun scan(context: Context, apkPath: String): ScanResult {
        val scanStartNs = System.nanoTime()
        val dangerousFound = mutableListOf<String>()
        val signaturesFound = mutableListOf<String>()
        // SCAN-02: g'ayritabiiy katta DEX (skanlash chegarasidan oshgan) uchun kichik jazo.
        var oversizedDexPenalty = 0
        // O'rnatilgan + ishonchli stor/tizim ilova (o'z sourceDir'i skanlandi). Funksiya darajasida —
        // verdict `when` undan foydalanadi (try blokidan tashqarida hisoblanmasin).
        var trustedInstalledApp = false
        var packageNameForHeuristic: String? = null
        // Imzo bilan tasdiqlangan reputatsiya — packageName + certFingerprint o'qilgach
        // hisoblanadi (pastda). SIGNATURE_MISMATCH darhol DANGER, VERIFIED esa
        // verdict `when` ichida SAFE ga tushiradi (faqat qat'iy signal yo'q bo'lsa).
        var reputation = AppReputation.Reputation.UNKNOWN
        // To'liq requested permissions ro'yxati — PermissionCombos uchun. dangerousFound
        // faqat DANGEROUS_PERMISSIONS bilan filtrlangan, lekin combo'lar (Dropper, OTP-grabber,
        // Full banker) INTERNET / BIND_ACCESSIBILITY_SERVICE / RECEIVE_BOOT_COMPLETED kabi
        // qo'shimcha ruxsatlarni talab qiladi — ular DANGEROUS_PERMISSIONS to'plamida yo'q.
        val allRequestedPerms = mutableListOf<String>()

        // SelfGuard — может бросить (PackageManager не доступен и т.п.). Не падаем.
        val isSelf = try { SelfGuard.isOwnApk(context, apkPath) } catch (e: Throwable) {
            Log.w(TAG, "SelfGuard failed", e); false
        }
        if (isSelf) {
            return ScanResult(
                verdict = ScanResult.Verdict.SAFE,
                reason = "Bu Anor Qalqonning o'zi — o'tkazib yuboriladi",
                details = listOf("Himoyachi o'zini o'zi skanerlamaydi va o'chirmaydi."),
                dangerousPermissions = emptyList(),
                malwareSignatures = emptyList()
            )
        }

        // Skan keshi: agar shu yo'l + mtime + size bo'yicha avval skanlangan bo'lsa,
        // qayta hisoblamaymiz va yangi telemetry/history yozmaymiz. Bu list refresh,
        // takroriy tap va batch scan'larni 5-20 marta tezlashtiradi. Fayl o'zgarsa
        // (mtime/size farq qiladi) — kesh miss, to'liq pipeline qaytadan ishlaydi.
        try {
            val cached = ScanCache.get(context, apkPath)
            if (cached != null) {
                Log.d(TAG, "Scan cache HIT: $apkPath → ${cached.verdict}")
                return cached
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ScanCache.get failed", e)
        }

        try {
            val file = File(apkPath)
            if (!file.exists() || !file.canRead()) {
                // Fayl mavjud emas yoki o'qib bo'lmaydi — bu "xavfsiz" degani EMAS.
                // SUSPICIOUS qaytaramiz, false-safe verdict bermaslik uchun.
                return finalizeResult(context, apkPath, ScanResult(
                    verdict = ScanResult.Verdict.SUSPICIOUS,
                    reason = "Fayl topilmadi yoki ochib bo'lmaydi",
                    details = listOf("Faylni o'qib bo'lmadi — tekshira olmadik, ehtiyot bo'ling"),
                    dangerousPermissions = emptyList(),
                    malwareSignatures = emptyList()
                ), cache = false, scanStartNs = scanStartNs)
            }

            // 0) APK-fayl SHA-256 hash tekshiruvi. Agar community-blacklist'da
            //    [MaliciousHashes] bo'lsa — darhol DANGER, qolgan tekshiruvlarsiz.
            //    "RASMLAR (18).apk" va "2_5222407840815161050.apk" bir xil hash
            //    bo'lsa — bir xil virus, faqat qayta nomlangan.
            val apkHash = try {
                CertUtil.apkFileSha256(apkPath)
            } catch (e: Throwable) {
                Log.w(TAG, "apkFileSha256 failed", e); null
            }
            val maliciousByHash = try {
                MaliciousHashes.maliciousFamily(apkHash)
            } catch (e: Throwable) {
                Log.w(TAG, "MaliciousHashes lookup failed", e); null
            }
            if (maliciousByHash != null) {
                return finalizeResult(context, apkPath, ScanResult(
                    verdict = ScanResult.Verdict.DANGER,
                    reason = "Hamjamiyat blacklist'ida: $maliciousByHash",
                    details = listOf(
                        "Bu APK ning SHA-256 hash'i ma'lum zararli APK bilan to'liq mos.",
                        "Hash: ${apkHash?.take(16)}…",
                        "Qat'iyan o'rnatmang."
                    ),
                    dangerousPermissions = emptyList(),
                    malwareSignatures = listOf("hash:$maliciousByHash")
                ), scanStartNs = scanStartNs)
            }

            // 0b) ZIP-entry encryption flag tekshiruvi. Bu Ajina.Banker oilasining
            //     klassik antivirus-evasion hiylasi: GP-flag bit 0 o'rnatilgan,
            //     Android e'tiborsiz qoldiradi, lekin Java ZipFile o'qiy olmaydi.
            //     Natijada Manifest/DEX/Dropper/ObfuscatedSig/Native — barchasi 0 score
            //     qaytaradi va verdict SAFE bo'lib qoladi. Buni darhol DANGER deb chiqaramiz.
            //     Real APK'lar (gradle/aapt2) bu flag'ni hech qachon ishlatmaydi.
            val zipEncFindings = try {
                ZipEncryptionDetector.analyze(apkPath)
            } catch (e: Throwable) {
                Log.w(TAG, "ZipEncryptionDetector failed", e)
                ZipEncryptionDetector.Findings(0, 0, emptyList())
            }
            if (zipEncFindings.hasEncrypted) {
                val sample = zipEncFindings.sampleNames.take(3).joinToString(", ")
                val pct = (zipEncFindings.fractionEncrypted * 100).toInt()
                return finalizeResult(context, apkPath, ScanResult(
                    verdict = ScanResult.Verdict.DANGER,
                    reason = "Antivirus-evasion: ZIP entry shifrlangan ($pct%)",
                    details = listOf(
                        "Bu APK ichidagi ${zipEncFindings.encryptedCount}/${zipEncFindings.totalEntries} ta fayl ZIP-shifrlash bayrog'i bilan belgilangan.",
                        "Android bu bayroqni e'tiborsiz qoldiradi va o'rnatadi, lekin antivirus o'qiy olmaydi.",
                        "Bu — Ajina.Banker oilasining klassik antivirus chetlab o'tish hiylasi.",
                        "Real ilovalar (gradle/aapt2) hech qachon bu usuldan foydalanmaydi.",
                        if (sample.isNotEmpty()) "Shifrlangan fayllar: $sample" else "",
                        "Qat'iyan o'rnatmang."
                    ).filter { it.isNotEmpty() },
                    dangerousPermissions = emptyList(),
                    malwareSignatures = listOf("zip-encryption-evasion")
                ), scanStartNs = scanStartNs)
            }

            // Проверка подписи — каждый шаг защищён, даже если PackageManager отсутствует.
            val certFingerprint = try {
                CertUtil.fingerprintSha256(context, apkPath)
            } catch (e: Throwable) {
                Log.w(TAG, "fingerprintSha256 failed", e); null
            }
            val maliciousFamily = try {
                MaliciousCerts.maliciousFamily(certFingerprint)
            } catch (e: Throwable) {
                Log.w(TAG, "maliciousFamily failed", e); null
            }

            // #14: Qora ro'yxatdagi imzo — HARD-DANGER. Avval bu tekshiruv quyidagi
            // getPackageArchiveInfo try-bloki ICHIDA edi; ba'zi OEM'larda/buzilgan yoki
            // juda katta APK'da getPackageArchiveInfo throw qiladi, catch esa faqat log
            // yozib DAVOM etardi → qora ro'yxatdagi imzo bosib qolinib, soxta SAFE chiqishi
            // mumkin edi. Endi imzo hisoblangach DARHOL, try'dan TASHQARIDA tekshiramiz.
            if (maliciousFamily != null) {
                return finalizeResult(context, apkPath, ScanResult(
                    verdict = ScanResult.Verdict.DANGER,
                    reason = "Qora ro'yxatdagi imzo: $maliciousFamily",
                    details = listOf(
                        "APK raqamli imzosi ma'lum zararli guruh imzosi bilan mos keladi.",
                        "O'rnatish qat'iyan tavsiya etilmaydi."
                    ),
                    dangerousPermissions = emptyList(),
                    malwareSignatures = listOf("cert:$maliciousFamily")
                ), certFingerprint, scanStartNs = scanStartNs)
            }

            try {
                val pm = context.packageManager
                val flags = PackageManager.GET_PERMISSIONS
                val info = pm.getPackageArchiveInfo(apkPath, flags)
                val packageName = info?.packageName
                packageNameForHeuristic = packageName

                // FALSE-POSITIVE himoyasi: skanlanayotgan fayl AYNAN o'rnatilgan ilovaning o'z
                // base.apk'si (sourceDir) bo'lsa — bu HAQIQIY o'rnatilgan ilova: uning /data/app
                // dagi faylini boshqa ilova ALMASHTIRA OLMAYDI. Ishonchli stordan (Play/Galaxy...)
                // yoki tizim ilovasi bo'lsa, QAT'IY signal yo'qligida yumshoq-ochkoli DANGER bosiladi
                // (o'rnatilgan legit ilovalar — YouTube/Payme/Soliq/ELSA... — "virus" deb belgilanmasin).
                // Telegram'dan kelgan sideload malware bu yo'lga TUSHMAYDI (sourceDir emas) va baribir
                // qat'iy signallar (random paket, ZIP-shifr, dropper...) bilan DANGER bo'ladi.
                val selfInstalled = isInstalledSelfScan(context, packageName, apkPath)
                trustedInstalledApp = selfInstalled && installedFromTrustedSource(context, packageName)

                // 1b) Ma'lum malware paket nomi (Ajina.Banker / RoundRift) — DANGER
                //     hatto agar APK qayta o'ralgan bo'lsa va hash boshqacha bo'lsa ham.
                val maliciousByPkg = try {
                    MaliciousPackages.maliciousFamily(packageName)
                } catch (e: Throwable) {
                    Log.w(TAG, "MaliciousPackages lookup failed", e); null
                }
                if (maliciousByPkg != null) {
                    return finalizeResult(context, apkPath, ScanResult(
                        verdict = ScanResult.Verdict.DANGER,
                        reason = "Ma'lum zararli paket: $maliciousByPkg",
                        details = listOf(
                            "Bu paket nomi ($packageName) — $maliciousByPkg oilasiga tegishli ma'lum malware.",
                            "Hatto APK qayta o'ralgan bo'lsa ham, paket nomi o'zgarmaydi.",
                            "Qat'iyan o'rnatmang."
                        ),
                        dangerousPermissions = emptyList(),
                        malwareSignatures = listOf("pkg:$maliciousByPkg")
                    ), certFingerprint, scanStartNs = scanStartNs)
                }

                // 2) Whitelist: (package + cert sha256) совпали с доверенным.
                val trusted = TrustedSignatures.trustedName(packageName, certFingerprint)
                if (trusted != null) {
                    return finalizeResult(context, apkPath, ScanResult(
                        verdict = ScanResult.Verdict.SAFE,
                        reason = "Tekshirilgan ilova: $trusted",
                        details = listOf("Imzo rasmiy imzo bilan mos"),
                        dangerousPermissions = emptyList(),
                        malwareSignatures = emptyList()
                    ), certFingerprint, scanStartNs = scanStartNs)
                }

                // 2b) Imzo bilan tasdiqlangan reputatsiya. Ishonchli brend nomi
                //     (com.android.*, com.google.*, bank/fintech paketlari...) AMMO
                //     qurilmada o'rnatilgan SHU NOMDAGI ilovaning imzosidan BOSHQA
                //     kalit bilan imzolangan bo'lsa — bu qalbaki ilova. Paket nomi oson
                //     qalbakilashtiriladi, imzo esa yo'q. Soxta Click/Payme/Kapitalbank —
                //     bank troyanlarining klassik vektori; oldin nomi tufayli SAFE bo'lardi.
                reputation = try {
                    AppReputation.evaluate(context, packageName, certFingerprint)
                } catch (e: Throwable) {
                    Log.w(TAG, "AppReputation.evaluate failed", e)
                    AppReputation.Reputation.UNKNOWN
                }
                // O'rnatilgan ilovaning O'Z faylini skanlayapmiz — u o'ziga nisbatan "soxta imzo"
                // bo'la olmaydi. SIGNATURE_MISMATCH bu yerda cert o'qishdagi nomuvofiqlik
                // (getPackageArchiveInfo fayldan vs getPackageInfo PM'dan — split/rotatsiya/null),
                // soxtalik emas. Haqiqiy o'rnatilgan ilova → VERIFIED (false-DANGER tuzatildi).
                if (selfInstalled && reputation == AppReputation.Reputation.SIGNATURE_MISMATCH) {
                    reputation = AppReputation.Reputation.VERIFIED
                }
                if (reputation == AppReputation.Reputation.SIGNATURE_MISMATCH) {
                    return finalizeResult(context, apkPath, ScanResult(
                        verdict = ScanResult.Verdict.DANGER,
                        reason = "Soxta imzo: '$packageName' ishonchli brend nomi, lekin imzosi qalbaki",
                        details = listOf(
                            "Bu paket nomi ($packageName) ishonchli brendga tegishli, lekin imzosi qurilmangizdagi o'rnatilgan ilovanikidan farq qiladi.",
                            "Bu — qalbaki yangilanish yoki brend nomidan foydalangan zararli ilova.",
                            "Haqiqiy ilova ishlab chiqaruvchining kaliti bilan imzolanadi — bu fayl unday emas.",
                            "Qat'iyan o'rnatmang."
                        ),
                        dangerousPermissions = emptyList(),
                        malwareSignatures = listOf("signature-mismatch:$packageName")
                    ), certFingerprint, scanStartNs = scanStartNs)
                }

                info?.requestedPermissions?.forEach { perm ->
                    allRequestedPerms.add(perm)
                    if (perm in DANGEROUS_PERMISSIONS) {
                        dangerousFound.add(perm)
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error reading permissions", e)
            }

            try {
                ZipFile(apkPath).use { zip ->
                    // Eski cheklov: 20 fayl, har biri <2MB, faqat birinchi 500KB. Bu degani
                    // classes.dex (odatda 3-15MB) butunlay tashlab ketilar edi. Ajina.Banker
                    // imzo o'rnatilgan fayllarni butunlay ko'rmas edi.
                    //
                    // Yangi: classes*.dex har doim oxirgacha skanlanadi (kerak bo'lsa 8MB
                    // gacha), boshqa entry'lar 256 ta gacha 1MB chegara bilan.
                    val maxOtherFiles = 256
                    val maxOtherBytes = 1 * 1024 * 1024
                    val maxDexBytes = 8 * 1024 * 1024
                    var checkedOther = 0

                    // Avval barcha entry'larni ajratamiz: DEX'lar har doim, qolganlar limit bilan.
                    val allEntries = zip.entries().toList()
                    val dexEntries = allEntries.filter {
                        !it.isDirectory && it.name.startsWith("classes") && it.name.endsWith(".dex")
                    }
                    val otherEntries = allEntries.filter {
                        !it.isDirectory && it !in dexEntries && it.size > 0
                    }

                    fun scanEntry(entry: java.util.zip.ZipEntry, maxBytes: Int) {
                        try {
                            zip.getInputStream(entry).use { input ->
                                val bytesToRead = maxBytes.coerceAtMost(entry.size.toInt().coerceAtLeast(1))
                                val bytes = ByteArray(bytesToRead)
                                var off = 0
                                while (off < bytesToRead) {
                                    val n = input.read(bytes, off, bytesToRead - off)
                                    if (n <= 0) break
                                    off += n
                                }
                                val actual = if (off == bytesToRead) bytes else bytes.copyOf(off)
                                val textUtf8 = String(actual, Charsets.UTF_8)
                                val textLatin = String(actual, Charsets.ISO_8859_1)

                                for (label in ObfuscatedSignatures.matchDecrypted(textUtf8)) {
                                    if (label !in signaturesFound) signaturesFound.add(label)
                                }
                                for (label in ObfuscatedSignatures.matchDecrypted(textLatin)) {
                                    if (label !in signaturesFound) signaturesFound.add(label)
                                }
                                for (family in ObfuscatedSignatures.matchTokenHashes(textUtf8)) {
                                    if (family !in signaturesFound) signaturesFound.add(family)
                                }
                            }
                        } catch (_: Exception) {
                            // Encrypted/corrupted entry — skip
                        }
                    }

                    // DEX fayllar — HAR QANDAY o'lchamdagi DEX'ning kamida birinchi maxDexBytes'i
                    // skanlanadi (scanEntry o'qishni baribir maxDexBytes bilan cheklaydi → xotira
                    // xavfsiz). SCAN-02: avval >4×maxDexBytes (>32MB) DEX BUTUNLAY tashlab yuborilardi
                    // → padding ichidagi string-IoC jim o'tkazib yuborilardi.
                    for (dex in dexEntries) {
                        if (dex.size <= 0L) continue
                        scanEntry(dex, maxDexBytes)
                        if (dex.size > maxDexBytes.toLong() * 4) {
                            // Haddan tashqari katta DEX g'ayritabiiy (ko'pincha hash/o'lcham
                            // asosidagi skanlashdan qochish uchun padding) — kichik score + log.
                            Log.w(TAG, "Juda katta DEX (${dex.size} bayt) — birinchi $maxDexBytes bayt skanlandi: ${dex.name}")
                            oversizedDexPenalty += 15
                        }
                    }

                    // Qolgan entry'lar — limit bilan
                    for (entry in otherEntries) {
                        if (checkedOther >= maxOtherFiles) break
                        if (entry.size < maxOtherBytes.toLong() * 4) {
                            scanEntry(entry, maxOtherBytes)
                            checkedOther++
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error scanning ZIP", e)
            }

            // ============================================================
            //  Расширенные анализаторы (Manifest, Permission combos, DEX
            //  patterns, Dropper, Icon impersonation).
            //  Каждый возвращает score; общий score = сумма + старая логика.
            // ============================================================

            val manifestFindings = try {
                ManifestAnalyzer.analyze(context.packageManager, apkPath)
            } catch (e: Throwable) {
                Log.w(TAG, "ManifestAnalyzer failed", e)
                ManifestAnalyzer.Findings(0, emptyList(), emptyList(), false, false, emptyList(), emptyList())
            }

            // MUHIM: combos uchun TO'LIQ requested permissions ro'yxati kerak —
            // INTERNET / BIND_ACCESSIBILITY_SERVICE / RECEIVE_BOOT_COMPLETED kabi
            // ruxsatlar DANGEROUS_PERMISSIONS to'plamida yo'q, lekin Dropper /
            // OTP-grabber / Full banker combo'lari aynan ularni talab qiladi.
            //
            // #16 KOʻPRIK: BIND_ACCESSIBILITY_SERVICE / BIND_DEVICE_ADMIN /
            // BIND_NOTIFICATION_LISTENER_SERVICE <uses-permission> emas, balki
            // <service/receiver android:permission> da e'lon qilinadi — shuning uchun
            // PackageInfo.requestedPermissions' da YO'Q. Ularsiz eng kuchli combo'lar
            // (OTP-grabber=90, Full banker=100, Persistent botnet=70, Ransomware,
            // Notification interception) HECH QACHON ishlamasdi. Manifest topilmalarini
            // permission to'plamiga qo'shamiz.
            if (manifestFindings.declaresAccessibility)
                allRequestedPerms.add("android.permission.BIND_ACCESSIBILITY_SERVICE")
            if (manifestFindings.declaresDeviceAdmin)
                allRequestedPerms.add("android.permission.BIND_DEVICE_ADMIN")
            if (manifestFindings.declaresNotificationListener)
                allRequestedPerms.add("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE")

            val comboMatches = try {
                PermissionCombos.evaluate(allRequestedPerms)
            } catch (e: Throwable) {
                Log.w(TAG, "PermissionCombos failed", e)
                emptyList()
            }
            val comboScore = PermissionCombos.totalScore(comboMatches)

            // ENG-03: "deviceAdminWithCombo" uchun MUSTAQIL kombo balli — BIND_DEVICE_ADMIN'ning
            // O'ZINI talab qiladigan kombo(lar)ni chiqarib tashlaymiz. Avval Ransomware-kombo
            // (required = {BIND_DEVICE_ADMIN}) DeviceAdmin e'lonining O'ZIDAN kelib chiqib har doim
            // mos kelar, shu sabab deviceAdminWithCombo aylanma mantiq bilan DOIM true bo'lib, har
            // qanday DeviceAdmin-li ilova (Find My Device, Knox/MDM, remote-wipe'li bank) VERIFIED
            // bo'lsa ham qattiq DANGER bo'lardi. Endi DeviceAdmin + MUSTAQIL zararli kombo (overlay/
            // SMS/accessibility...) >= 30 bo'lsagina ishlaydi.
            val comboScoreIndependentOfDeviceAdmin = comboMatches
                .filter { m ->
                    val req = m.combo.required
                    !(req.size == 1 && req.contains("android.permission.BIND_DEVICE_ADMIN"))
                }
                .sumOf { it.combo.score }

            val dexFindings = try {
                DexPatternAnalyzer.analyze(apkPath)
            } catch (e: Throwable) {
                Log.w(TAG, "DexPatternAnalyzer failed", e)
                DexPatternAnalyzer.Findings(0, emptyList())
            }

            val dropperFindings = try {
                DropperDetector.analyze(apkPath)
            } catch (e: Throwable) {
                Log.w(TAG, "DropperDetector failed", e)
                DropperDetector.Findings(0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
            }

            val iconMatch = try {
                IconImpersonationDetector.detect(context, apkPath, packageNameForHeuristic)
            } catch (e: Throwable) {
                Log.w(TAG, "IconImpersonation failed", e)
                null
            }

            // Анализ нативных библиотек — entropy + suspicious imports.
            val nativeFindings = try {
                NativeLibAnalyzer.analyze(apkPath)
            } catch (e: Throwable) {
                Log.w(TAG, "Native analysis failed", e)
                NativeLibAnalyzer.Findings(emptyList(), emptyList())
            }

            // Извлекаем app label для filename heuristic L7 (label vs filename mismatch).
            val appLabel = try {
                val info = context.packageManager.getPackageArchiveInfo(apkPath, 0)
                info?.applicationInfo?.let { appInfo ->
                    // applicationInfo.loadLabel требует чтобы sourceDir указывал на apk —
                    // иначе вернёт packageName. Подставляем.
                    appInfo.sourceDir = apkPath
                    appInfo.publicSourceDir = apkPath
                    context.packageManager.getApplicationLabel(appInfo)?.toString()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "loadLabel failed", e); null
            }

            // Filename heuristic (8 слоёв: шаблоны, lure, brand impersonation,
            // typosquat, homoglyph, label mismatch, bigram-rarity).
            val filenameFindings = try {
                FilenameHeuristic.analyze(apkPath, packageNameForHeuristic, appLabel)
            } catch (e: Throwable) {
                Log.w(TAG, "Filename heuristic failed", e)
                FilenameHeuristic.Findings(0, emptyList())
            }
            // Hard danger из filename heuristic — мгновенный DANGER без оглядки на score.
            when (val h = filenameFindings.hardDanger) {
                is FilenameHeuristic.HardDanger.BrandImpersonation -> {
                    val kindLabel = when (h.matchKind) {
                        "exact" -> "aniq nom"
                        "typosquat" -> "buzilgan harflar bilan (typosquat)"
                        else -> h.matchKind
                    }
                    return finalizeResult(context, apkPath, ScanResult(
                        verdict = ScanResult.Verdict.DANGER,
                        reason = "Brand impersonation: '${h.brand}' nomi soxta ($kindLabel)",
                        details = listOf(
                            "Fayl nomida \"${h.brand}\" so'zi bor — siz haqiqiy ilova deb o'ylashingiz mumkin.",
                            "Lekin paket nomi (${h.actualPackage ?: "yo'q"}) rasmiy emas.",
                            "Haqiqiy paket: ${h.expectedPackage}.*",
                            "Bu fayl haqiqiy ${h.brand} emas — qat'iyan o'rnatmang."
                        ),
                        dangerousPermissions = dangerousFound,
                        malwareSignatures = listOf("impersonation:${h.brand}:${h.matchKind}")
                    ), certFingerprint, scanStartNs = scanStartNs)
                }
                is FilenameHeuristic.HardDanger.HomoglyphScript -> {
                    return finalizeResult(context, apkPath, ScanResult(
                        verdict = ScanResult.Verdict.DANGER,
                        reason = "Homoglyph hujum: \"${h.sample}\" so'zida kirill + lotin aralash",
                        details = listOf(
                            "Fayl nomida \"${h.sample}\" so'zi bor.",
                            "Bu so'zda kirill harflari lotin harfiga o'xshatib qo'yilgan (masalan, kirillcha 'Т' lotincha 'T' o'rniga).",
                            "Bu klassik phishing trick — ko'rinishi bo'yicha bren, lekin amalda boshqa fayl.",
                            "Qat'iyan o'rnatmang."
                        ),
                        dangerousPermissions = dangerousFound,
                        malwareSignatures = listOf("homoglyph:${h.sample}")
                    ), certFingerprint, scanStartNs = scanStartNs)
                }
                null -> { /* нет hard danger, идём дальше */ }
            }

            // Random-looking package name (com.lzthzvxte.xazoalzxhr) — virus generator belgisi.
            // Bu yolg'iz o'zi DANGER bermaydi, lekin SUSPICIOUS → DANGER ko'tarish uchun yetadi.
            val randomPkg = try { looksRandomPackageName(packageNameForHeuristic) } catch (_: Throwable) { false }

            // === REPUTATSIYA: ma'lum, qonuniy ishlab chiqaruvchi ilovasimi? ===
            // Chrome/Gmail/Google Play/Instagram/Telegram/banklar — native lib'ga ham,
            // 3+ "xavfli" ruxsatga ham ega. Eski mantiq ularning HAMMASINI "Zararli dastur"
            // deb belgilab qo'yardi. Reputatsiya yuqorida (2b) IMZO bilan tasdiqlangan:
            // faqat qurilmadagi o'rnatilgan ilova imzosi bilan MOS kelsa VERIFIED bo'ladi.
            // Bu yerda VERIFIED + qat'iy zararli signal yo'q → SAFE. Qat'iy signallar
            // (dropper, ikonka taqlidi, qora ro'yxat) pastdagi `when` ichida reputatsiyadan
            // OLDIN turadi, shuning uchun VERIFIED ilovada ham yashirin yuk bo'lsa ushlanadi.
            val verifiedTrusted = reputation == AppReputation.Reputation.VERIFIED

            // Sensitivity nastroyka iz Settings — vliyaet na porogi verdikta.
            //  low   — faqat aniq tahdidlar (qora ro'yxat / kuchli combo / qat'iy signal).
            //  medium — baseline.
            //  high  — agressivroq pastroq threshold.
            val sensitivity = try { Config.getSensitivityLevel(context) } catch (_: Throwable) { "medium" }

            // Verdikt CHEGARALARI masofaviy (imzolangan) config'dan — yuklab olingan APK ichida
            // "qaysi ball DANGER beradi" degan ANIQ raqamlar turmasin. get() faqat keshdan
            // o'qiydi (skan issiq yo'lida TARMOQ YO'Q); masofaviy faqat KUCHAYTIRA oladi (clamp=min).
            // Har qanday xato → baked standartlar (quyidagi joriy qiymatlar bilan AYNAN bir xil).
            val rc = RemoteConfig.get(context)

            // Anti-analysis (evasion) belgilari soni — Anti-Frida/Anti-Magisk/TracerPid/tmp-probe
            // /Anti-debug. Real ilovalar bunday hech qachon qilmaydi. 2+ ta birga
            // bo'lsa — bu sof virus, score'dan qat'iy nazar DANGER.
            val evasionLabels = setOf(
                "Anti-debug check",
                "TracerPid /proc anti-debug",
                "Anti-Frida check (tahlilga qarshi)",
                "Anti-Frida gadget probing",
                "Anti-Magisk (root check)",
                "Suspicious tmp path probe",
                "ZipEntry custom method (ZIP-evasion)"
            )
            val evasionCount = dexFindings.patterns.count { it in evasionLabels }

            // Eng kuchli YAKKA combo (OTP-grabber=90, Full-banker=100) — malware-specifik.
            // Bir nechta ZAIF combo'ni qo'shib DANGER bermaymiz (legit super-app shunday
            // yig'iladi); faqat yakka kuchli combo mgновen DANGER beradi.
            val strongCombo = comboMatches.any { it.combo.score >= rc.strongComboMin }

            // Soxta "xavfsizlik/tozalash" ilovasi: ko'rinadigan nomi (label) antivirus/
            // cleaner/booster/shield/guard/VPN deydi, LEKIN mikrofon (RECORD_AUDIO) so'raydi.
            // Haqiqiy antivirus/tozalagich/booster hech qachon mikrofon so'ramaydi — bu
            // deyarli har doim josuslik niqobi ("Secure Shield" = com.secureshield.app:
            // kamera+mikrofon+internet, lekin nomi "himoyachi"). Past FP: reputatsiya
            // (VERIFIED) o'rnatilgan legit ilovalarni yuqorida himoya qiladi; bu +50 faqat
            // SUSPICIOUS darajasiga yetadi (o'zi DANGER bermaydi).
            val fakeSecurityMicAbuse = run {
                val label = (appLabel ?: "").lowercase()
                val securityWords = listOf(
                    "antivirus", "anti-virus", "anti virus", "cleaner", "booster",
                    "optimizer", "security", "shield", "guard", "protector",
                    "vpn", "cache clean", "speed boost", "phone clean"
                )
                val claimsSecurity = securityWords.any { it in label }
                val wantsMic = allRequestedPerms.any { it.endsWith("RECORD_AUDIO") }
                claimsSecurity && wantsMic
            }
            val fakeSecurityScore = if (fakeSecurityMicAbuse) 50 else 0

            // MUHIM: native topilma endi MUSTAQIL DANGER bermaydi (bu false-positive'ning
            // asosiy sababi edi — har bir native lib'da dlopen/JNI_OnLoad bor). U umumiy
            // score'ga qo'shiladi va boshqa signallar bilan tasdiqlanishi kerak.
            val totalScore = manifestFindings.score + comboScore + dexFindings.score +
                    dropperFindings.score + filenameFindings.score + nativeFindings.score +
                    fakeSecurityScore + oversizedDexPenalty

            // Threshold'lar masofaviy config'dan (RemoteConfig). Baked standartlar avvalgi
            // qiymatlar bilan AYNAN bir xil (high 55/28, medium 85/40, low 120/60); masofaviy
            // faqat pasaytira oladi (= ko'proq aniqlash), hech qachon zaiflashtira olmaydi.
            val dangerThreshold = rc.dangerThreshold(sensitivity)
            val suspiciousThreshold = rc.suspiciousThreshold(sensitivity)

            // DET-02: payload-lokatsiyada (.so lib/{abi} TASHQARISIDA — assets/res/raw/META-INF/ildiz)
            // joylashgan native ELF — dropper payload belgisi (hiddenDex/hiddenApk kabi). lib/ ichidagi
            // nostandart ABI'larni (legit plagin bo'lishi mumkin) chetlab o'tamiz → past FP, "yuqori ishonch".
            val droppedSo = dropperFindings.soOutsideLib.any { n ->
                n.startsWith("assets/") || n.startsWith("res/raw/") ||
                        n.startsWith("META-INF/") || !n.contains("/")
            }

            // Yakuniy qaror toza (test qilinadigan) funksiyaga ajratilgan (decideVerdict) — invariant
            // #1/#5 (xato hech qachon SAFE emas; VERIFIED + qat'iy signal = DANGER) endi unit-test bilan
            // qo'riqlanadi. Mantiq AYNI — faqat ko'chirilgan.
            val verdict = decideVerdict(
                VerdictSignals(
                    iconImpersonation = iconMatch != null,
                    hiddenApkOrDex = dropperFindings.hiddenApks.isNotEmpty() ||
                            dropperFindings.hiddenDex.isNotEmpty(),
                    hiddenElfOrDroppedSo = dropperFindings.hiddenElf.isNotEmpty() || droppedSo,
                    encryptedPayloadWithSignal = dropperFindings.encryptedPayloads.isNotEmpty() &&
                            (dropperFindings.soOutsideLib.isNotEmpty() || randomPkg),
                    deviceAdminWithCombo = manifestFindings.declaresDeviceAdmin &&
                            comboScoreIndependentOfDeviceAdmin >= 30,
                    obfuscatedSignature = signaturesFound.isNotEmpty(),
                    strongCombo = strongCombo,
                    evasionCount = evasionCount,
                    verifiedTrusted = verifiedTrusted,
                    trustedInstalledApp = trustedInstalledApp,
                    totalScore = totalScore,
                    dangerThreshold = dangerThreshold,
                    suspiciousThreshold = suspiciousThreshold,
                    randomPkg = randomPkg,
                    filenameScore = filenameFindings.score,
                    randomPkgFilenameMin = rc.randomPkgFilenameMin,
                    dangerousPermCount = dangerousFound.size,
                    randomPkgDangerousPermsMin = rc.randomPkgDangerousPermsMin,
                    sensitivity = sensitivity,
                )
            )

            val reason = when (verdict) {
                ScanResult.Verdict.DANGER -> "Zararli dastur belgilari topildi. Bu faylni o'rnatmang."
                ScanResult.Verdict.SUSPICIOUS -> "Xavfli ruxsatlar. O'rnatmaslik tavsiya etiladi."
                ScanResult.Verdict.SAFE -> "Kritik belgilar topilmadi."
            }

            // === ML advisory (MlRiskModel) — verdictni O'ZGARTIRMAYDI (golden qoida). ===
            // Detektorlar allaqachon hisoblagan signallardan features yig'amiz; natija
            // faqat details + telemetriya uchun. decideVerdict() bunga qaramaydi.
            val mlRisk = try {
                MlRiskModel.riskProbability(
                    MlRiskModel.Features(
                        dangerousPermCount = dangerousFound.size,
                        permComboScore = comboScore,
                        dexPatternHits = dexFindings.patterns.size,
                        hasNativeSuspicious = nativeFindings.suspiciousLibs.isNotEmpty(),
                        hasObfuscatedSig = signaturesFound.isNotEmpty(),
                        evasionTechniques = evasionCount,
                        // DropperDetector entropiyani tashqariga chiqarmaydi; shifrlangan
                        // payload aniqlanishining o'zi >=7.5 entropy talab qiladi.
                        maxAssetEntropy = if (dropperFindings.encryptedPayloads.isNotEmpty()) 7.5 else 0.0,
                        hasHiddenPayload = dropperFindings.hiddenApks.isNotEmpty() ||
                                dropperFindings.hiddenDex.isNotEmpty() ||
                                dropperFindings.hiddenElf.isNotEmpty(),
                        filenameSuspicion = filenameFindings.score,
                        // ZIP-shifrlash yuqorida erta-DANGER bilan chiqib ketadi — bu yerga yetmaydi.
                        zipEncrypted = false,
                        iconImpersonation = iconMatch != null,
                    )
                )
            } catch (e: Throwable) {
                Log.w(TAG, "MlRiskModel failed", e); -1.0
            }

            val details = mutableListOf<String>()
            if (dangerousFound.isNotEmpty()) {
                val perms = dangerousFound.take(5).joinToString(", ") { it.substringAfterLast(".") }
                details.add("Xavfli ruxsatlar: $perms")
            }
            if (fakeSecurityMicAbuse) {
                details.add("⚠️ \"Xavfsizlik/tozalash\" ilovasi mikrofon (RECORD_AUDIO) so'rayapti — bu odatda josuslik niqobi")
            }
            if (signaturesFound.isNotEmpty()) {
                val sigs = signaturesFound.take(3).joinToString(", ")
                details.add("Zararli dastur belgilari: $sigs")
            }
            if (nativeFindings.suspiciousLibs.isNotEmpty()) {
                details.addAll(nativeFindings.reasons.take(3))
            }
            if (randomPkg && packageNameForHeuristic != null) {
                details.add("Shubhali tasodifiy package nomi: $packageNameForHeuristic")
            }

            // === Manifest tahlili ===
            if (manifestFindings.redFlags.isNotEmpty()) {
                details.addAll(manifestFindings.redFlags.take(3))
            }
            if (manifestFindings.orangeFlags.isNotEmpty()) {
                details.addAll(manifestFindings.orangeFlags.take(2))
            }
            if (manifestFindings.declaresAccessibility) {
                details.add("⚠️ Accessibility xizmati e'lon qilingan — bank ilovalarini boshqarishga urinishi mumkin")
            }

            // === Permission combos ===
            for (m in comboMatches.take(3)) {
                details.add("Xavfli ruxsat kombinatsiyasi: ${m.combo.label}")
            }

            // === DEX patterns ===
            if (dexFindings.patterns.isNotEmpty()) {
                details.add("Kod ichida: ${dexFindings.patterns.take(3).joinToString(", ")}")
            }
            if (dexFindings.packerDetected != null) {
                details.add("⚠️ Packer aniqlandi (${dexFindings.packerDetected}) — kod yashirilgan, tahlil qilib bo'lmaydi")
            }

            // === Dropper ===
            if (dropperFindings.hiddenApks.isNotEmpty()) {
                details.add("🚫 Ichida yashirin APK: ${dropperFindings.hiddenApks.take(2).joinToString(", ")}")
            }
            if (dropperFindings.hiddenDex.isNotEmpty()) {
                details.add("🚫 Ichida yashirin DEX: ${dropperFindings.hiddenDex.take(2).joinToString(", ")}")
            }
            if (dropperFindings.hiddenElf.isNotEmpty()) {
                details.add("🚫 Soxta fayl ichida ELF: ${dropperFindings.hiddenElf.take(2).joinToString(", ")}")
            }
            if (dropperFindings.soOutsideLib.isNotEmpty()) {
                details.add("⚠️ Standartdan tashqari .so: ${dropperFindings.soOutsideLib.take(2).joinToString(", ")}")
            }
            if (dropperFindings.encryptedPayloads.isNotEmpty()) {
                details.add("🚫 Yashirin shifrlangan payload: ${dropperFindings.encryptedPayloads.take(2).joinToString(", ")}")
            }

            // === Icon impersonation ===
            if (iconMatch != null) {
                details.add("🚫 Ikonkasi \"${iconMatch.mimickedLabel}\" ga taqlid qiladi (phishing)")
            }

            // === Filename heuristic ===
            if (filenameFindings.flags.isNotEmpty()) {
                details.addAll(filenameFindings.flags.take(3))
            }

            // === ML advisory ===
            if (mlRisk >= 0.35) {
                details.add("🤖 AI bahosi: zararli bo'lish ehtimoli ~${(mlRisk * 100).toInt()}% (daraja: ${mlBandUz(mlRisk)}) — maslahat, xulosaga ta'sir qilmaydi")
            }

            if (details.isEmpty()) {
                details.add("Shubhali elementlar topilmadi")
            }

            // === FOYDALANUVCHI OQ RO'YXATI (UserWhitelist) ===
            // FAQAT SHUBHALI bosiladi. DANGER'ga TEGILMAYDI (oltin qoida): qat'iy IOC'lar
            // (ma'lum hash/imzo/paket, ZIP-shifr) yuqorida erta-return bilan allaqachon chiqib
            // ketgan, bu nuqtaga yetmaydi; qolgan DANGER ham kuchli signal — oqlanmaydi.
            // Mos kelish kriptografik: fayl SHA-256 YOKI (package + imzo-cert) juftligi.
            var finalVerdict = verdict
            var finalReason = reason
            if (verdict == ScanResult.Verdict.SUSPICIOUS) {
                val userTrusted = try {
                    UserWhitelist.isWhitelistedFile(context, apkHash) ||
                        UserWhitelist.isWhitelistedApp(context, packageNameForHeuristic, certFingerprint)
                } catch (e: Throwable) {
                    Log.w(TAG, "UserWhitelist lookup failed", e); false
                }
                if (userTrusted) {
                    finalVerdict = ScanResult.Verdict.SAFE
                    finalReason = "Siz bu fayl/ilovani ishonchli deb belgilagansiz."
                    details.add(0, "ℹ️ Ishonchli ro'yxatingizda — shubhali belgilar bosildi (Sozlamalar → Ishonchli ro'yxat)")
                }
            }

            val result = ScanResult(
                verdict = finalVerdict,
                reason = finalReason,
                details = details,
                dangerousPermissions = dangerousFound,
                malwareSignatures = signaturesFound,
                mlRisk = mlRisk
            )
            
            // Statistika + telemetriya + tarix + kesh + widget — barchasi yagona nuqtada.
            return finalizeResult(context, apkPath, result, certFingerprint, scanStartNs = scanStartNs)

        } catch (e: Throwable) {
            // Throwable — ловим даже OutOfMemory и StackOverflow.
            // Skaner xatosi → SUSPICIOUS (НЕ SAFE!). Falsh-safe verdict — eng havfli xato:
            // foydalanuvchi virus o'rnatib qo'yadi, biz "xavfsiz" deganimiz uchun.
            Log.e(TAG, "Critical error during scan", e)
            // Skan xatosi ham statistikaga "skanlangan" deb tushadi, lekin kesh'ga
            // yozilmaydi — keyingi safar fayl qayta sinab ko'riladi (cache = false).
            return finalizeResult(context, apkPath, ScanResult(
                verdict = ScanResult.Verdict.SUSPICIOUS,
                reason = "Skaner xatosi: ${e.javaClass.simpleName}: ${e.message ?: "noma'lum"}",
                details = listOf(
                    "Faylni tekshirib bo'lmadi — verdict ishonchli emas.",
                    "Sabab: ${e.javaClass.simpleName}",
                    e.message ?: "(nomsiz xato)"
                ),
                dangerousPermissions = emptyList(),
                malwareSignatures = emptyList()
            ), cache = false, scanStartNs = scanStartNs)
        }
    }
}

/**
 * [ApkScanner.scan] yakuniy verdikt qarori uchun toza signal-to'plami. Mantiq [decideVerdict]'da —
 * qurilma/fayl/Android'ga bog'liq emas, shuning uchun unit-test qilinadi (#10).
 */
internal data class VerdictSignals(
    val iconImpersonation: Boolean,
    val hiddenApkOrDex: Boolean,
    val hiddenElfOrDroppedSo: Boolean,
    val encryptedPayloadWithSignal: Boolean,
    val deviceAdminWithCombo: Boolean,
    val obfuscatedSignature: Boolean,
    val strongCombo: Boolean,
    val evasionCount: Int,
    val verifiedTrusted: Boolean,
    // O'rnatilgan, ishonchli stordan/tizimdan kelgan ilova (o'z sourceDir'i skanlandi). verifiedTrusted
    // kabi — faqat QAT'IY signal yo'qligida SAFE qiladi (yumshoq ochkolarni bosadi).
    val trustedInstalledApp: Boolean,
    val totalScore: Int,
    val dangerThreshold: Int,
    val suspiciousThreshold: Int,
    val randomPkg: Boolean,
    val filenameScore: Int,
    val randomPkgFilenameMin: Int,
    val dangerousPermCount: Int,
    val randomPkgDangerousPermsMin: Int,
    val sensitivity: String,
)

/**
 * Signal-to'plamidan yakuniy verdikt. INVARIANTLAR (test bilan qo'riqlanadi):
 *  • Qat'iy signallar (icon-impersonation, hidden APK/DEX/ELF dropper, shifrlangan payload+signal,
 *    device-admin+combo, obfuscated IoC, kuchli combo, 2+ evaziya) reputatsiyadan QAT'IY NAZAR DANGER.
 *  • VERIFIED faqat qat'iy signal YO'Q bo'lsa SAFE qiladi (yumshoq signallarni bosadi).
 *  • Tartib MUHIM — qat'iy bloklar verifiedTrusted'dan OLDIN. [ApkScanner.scan] ichidagi `when` shu yerga
 *    AYNAN ko'chirildi (xulq o'zgarmagan).
 */
internal fun decideVerdict(s: VerdictSignals): ScanResult.Verdict = when {
    s.iconImpersonation -> ScanResult.Verdict.DANGER
    s.hiddenApkOrDex -> ScanResult.Verdict.DANGER
    s.hiddenElfOrDroppedSo -> ScanResult.Verdict.DANGER
    s.encryptedPayloadWithSignal -> ScanResult.Verdict.DANGER
    s.deviceAdminWithCombo -> ScanResult.Verdict.DANGER
    s.obfuscatedSignature -> ScanResult.Verdict.DANGER
    s.strongCombo -> ScanResult.Verdict.DANGER
    s.evasionCount >= 2 -> ScanResult.Verdict.DANGER
    s.verifiedTrusted -> ScanResult.Verdict.SAFE
    // O'rnatilgan + ishonchli stor/tizim + qat'iy signal yo'q → SAFE (yuqoridagi barcha QAT'IY
    // bloklardan KEYIN — dropper/ikonka/blacklist/ZIP-shifr ham bunday ilovada baribir DANGER).
    s.trustedInstalledApp -> ScanResult.Verdict.SAFE
    s.totalScore >= s.dangerThreshold -> ScanResult.Verdict.DANGER
    s.randomPkg && s.filenameScore >= s.randomPkgFilenameMin -> ScanResult.Verdict.DANGER
    s.randomPkg && s.dangerousPermCount >= s.randomPkgDangerousPermsMin -> ScanResult.Verdict.DANGER
    s.totalScore >= s.suspiciousThreshold -> ScanResult.Verdict.SUSPICIOUS
    s.evasionCount >= 1 -> ScanResult.Verdict.SUSPICIOUS
    s.randomPkg && s.sensitivity != "low" -> ScanResult.Verdict.SUSPICIOUS
    s.dangerousPermCount >= 4 && s.sensitivity == "high" -> ScanResult.Verdict.SUSPICIOUS
    else -> ScanResult.Verdict.SAFE
}

/**
 * Skanlanayotgan fayl AYNAN o'rnatilgan [pkg] ilovaning o'z base.apk'si (sourceDir)mi?
 * Agar shunday bo'lsa — bu HAQIQIY o'rnatilgan ilova (/data/app dagi faylni boshqa ilova
 * almashtira olmaydi), demak o'ziga nisbatan "soxta" bo'la olmaydi. Telegram'dan kelgan
 * sideload fayl (Downloads/cache) bu shartga TUSHMAYDI → soxta-tekshiruv unga ishlaydi.
 */
private fun isInstalledSelfScan(context: Context, pkg: String?, apkPath: String): Boolean {
    if (pkg.isNullOrBlank()) return false
    return try {
        context.packageManager.getApplicationInfo(pkg, 0).sourceDir == apkPath
    } catch (_: Exception) {
        false
    }
}

/** Ishonchli store installerlari — bulardan o'rnatilgan ilova juda kam hollarda malware. */
private val TRUSTED_INSTALLERS = setOf(
    "com.android.vending",                  // Google Play
    "com.google.android.feedback",
    "com.sec.android.app.samsungapps",      // Galaxy Store
    "com.huawei.appmarket",                 // AppGallery
    "com.xiaomi.market", "com.xiaomi.mipicks",
    "com.heytap.market", "com.oppo.market",
    "com.vivo.appstore",
    "com.amazon.venezia",
    "ru.vk.store",                          // RuStore
)

/** [pkg] tizim (yoki yangilangan-tizim) ilovasimi YOKI ishonchli stordan o'rnatilganmi? */
private fun installedFromTrustedSource(context: Context, pkg: String?): Boolean {
    if (pkg.isNullOrBlank()) return false
    return try {
        val pm = context.packageManager
        val ai = pm.getApplicationInfo(pkg, 0)
        val isSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (ai.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        if (isSystem) return true
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
        }
        installer != null && installer in TRUSTED_INSTALLERS
    } catch (_: Exception) {
        false
    }
}
