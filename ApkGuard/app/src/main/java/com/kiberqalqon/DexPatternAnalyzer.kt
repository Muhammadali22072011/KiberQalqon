package com.uzguard

import android.util.Log
import java.util.zip.ZipFile

/**
 * Анализ classes.dex (и multidex'ов) на уровне БАЙТ — без полной декомпиляции.
 *
 * Что ищем (всё это в DEX лежит как plain-text string в string pool):
 *
 *  • Dynamic code loading:
 *      "dalvik.system.DexClassLoader"
 *      "dalvik.system.PathClassLoader"
 *      "dalvik.system.InMemoryDexClassLoader"
 *      "java.lang.reflect.Method.invoke" — рефлексия для скрытого вызова API
 *
 *  • Native loading из non-standard location:
 *      "System.load" в связке с "/data/data/" или "/sdcard/" — загрузка downloaded .so
 *
 *  • Sensitive API calls:
 *      "getDeviceId" / "getSimSerialNumber" / "getSubscriberId" — IMEI/IMSI стилеры
 *      "sendTextMessage" / "sendMultipartTextMessage" — SMS-стилеры
 *      "android.intent.action.NEW_OUTGOING_CALL" — call hijack
 *
 *  • Anti-analysis:
 *      "isDebuggerConnected" — анти-дебаг (сама по себе не плохо, но в связке)
 *      "android.os.Debug" + "TracerPid:" — продвинутый анти-дебаг через /proc
 *
 *  • Packer markers:
 *      "Bangcle" / "ApkProtect" / "ijiami" / "qihoo360" / "Tencent" — known packers,
 *      packed APKs нельзя анализировать статически
 *
 *  • Telegram bot URLs (часто используются как C2):
 *      "api.telegram.org/bot" + захардкоженный token-like string
 *
 * Мы НЕ парсим DEX полностью — это медленно и хрупко. Просто читаем DEX как байты
 * и ищем UTF-8 substrings, потому что DEX string pool — UTF-8.
 */
object DexPatternAnalyzer {

    private const val TAG = "DexPatternAnalyzer"
    private const val SAMPLE_SIZE = 4 * 1024 * 1024     // первые 4 MB достаточно для большинства

    // RuleEngine (YARA-lite) uchun to'plangan kichik-harfli DEX string-pool matni chegarasi.
    // DEX baytlar ALLAQACHON o'qiladi (qo'shimcha IO yo'q) — faqat lowercase nusxa yig'iladi.
    // OOM'dan himoya uchun umumiy hajmni cheklaymiz (patologik multidex'ga qarshi).
    private const val MAX_RULE_TEXT = 6 * 1024 * 1024

    data class Findings(
        val score: Int,
        val patterns: List<String>,
        val packerDetected: String? = null,
        val tooManyDex: Boolean = false,
        /**
         * classes*.dex string-pool matnining KICHIK HARFLI (ISO-8859-1) birlashmasi —
         * [RuleEngine] uchun "dex_string" haystack'i. Bo'sh/o'qib bo'lmasa null.
         * DEX baytlari shu analiz vaqtida bir marta o'qiladi (qayta IO yo'q → issiqlik oshmaydi).
         */
        val dexStringsLower: String? = null,
    )

    private data class Pattern(
        val needle: String,
        val score: Int,
        val label: String,
        // ANTI-DEAD-NEEDLE (2026-07): raw DEX string pool'da tip deskriptori
        // (masalan "Landroid/telephony/SmsManager;") va metod nomi ("sendTextMessage")
        // ALOHIDA, tutash bo'lmagan string_data yozuvlarida yotadi. Smali'dagi
        // "Lclass;->method" konkatenatsiyasi DEX baytlarida HECH QACHON tutash uchramaydi.
        // Shu bois metod-havolani ikki bo'lakka bo'lamiz: needle (deskriptor) VA needle2
        // (metod nomi) — ikkalasi ham SHU dex ichida bo'lsa hit. needle2=null → oddiy bitta needle.
        val needle2: String? = null
    )

    // РЕКАЛИБРОВКА (2026-05): множество паттернов встречается в КАЖДОМ крупном
    // легитимном приложении (Chrome, GMS, Instagram) и давало ложные срабатывания.
    // Принцип: повсеместно-безобидные API → 0 баллов; чувствительные-но-частые →
    // низкий балл; реально malware-специфичные (SMS-перехват, overlay+accessibility,
    // динамическая загрузка кода, packer'ы, анти-Frida) → высокий балл.
    private val PATTERNS = listOf(
        // Dynamic loading — DexClassLoader реально редок в обычных приложениях.
        Pattern("Ldalvik/system/DexClassLoader;", 30, "DexClassLoader (runtime kod yuklash)"),
        Pattern("Ldalvik/system/InMemoryDexClassLoader;", 40, "InMemoryDexClassLoader (xotirada DEX)"),
        Pattern("Ldalvik/system/PathClassLoader;", 0, "PathClassLoader"),       // штатный загрузчик — у всех
        Pattern("Ljava/lang/reflect/Method;", 0, "Reflection API"),             // рефлексия есть у всех

        // Native loading
        Pattern("Ljava/lang/System;", 0, "System.load native chaqiruv"),  // любой app с .so
        // Runtime.exec: deskriptor + metod nomi alohida uchraydi → ikkalasi ham shu dex'da bo'lsin.
        Pattern("Ljava/lang/Runtime;", 25, "Runtime.exec (shell chaqiruv)", needle2 = "exec"),

        // Sensitive APIs (часто в analytics/ads SDK легитимных приложений → низкий балл)
        // Metod nomlari o'zi yetarli darajada o'ziga xos → bare needle bilan qidiramiz.
        Pattern("getDeviceId", 8, "IMEI o'qish"),
        Pattern("getSubscriberId", 10, "IMSI o'qish"),
        Pattern("getSimSerialNumber", 10, "SIM serial o'qish"),
        Pattern("sendTextMessage", 35, "SMS yuborish API"),
        Pattern("sendMultipartTextMessage", 35, "Multipart SMS yuborish"),
        Pattern("android.provider.Telephony.SMS_RECEIVED", 20, "SMS qabul intent"),
        Pattern("android.intent.action.NEW_OUTGOING_CALL", 18, "Chiquvchi qo'ng'iroqlarni ushlash"),

        // Anti-analysis
        Pattern("isDebuggerConnected", 5, "Anti-debug check"),
        Pattern("TracerPid", 15, "TracerPid /proc anti-debug"),

        // Telegram bot C2 (классический pattern Uzbek banking malware)
        Pattern("api.telegram.org/bot", 25, "Telegram bot URL (C2 belgisi)"),

        // Known packers (если упакован — мы вообще ничего не видим, считаем подозрительным)
        Pattern("Lcom/bangcle/", 40, "Bangcle packer"),
        Pattern("Lcom/secneo/", 40, "SecNeo packer"),
        Pattern("Lcom/qihoo/", 40, "Qihoo360 packer"),
        Pattern("Lcom/tencent/StubShell", 40, "Tencent Legu packer"),
        Pattern("Lcom/ijiami/", 40, "Ijiami packer"),

        // Crypto/encoding — повсеместно (HTTPS, токены, кэш). Не штрафуем.
        Pattern("Ljavax/crypto/Cipher;", 0, "Cipher.doFinal"),
        Pattern("Landroid/util/Base64;", 0, "Base64 decode"),

        // Дополнительные SMS-стилер маркеры
        Pattern("android.provider.Telephony.SMS_DELIVER", 25, "SMS_DELIVER (приоритетный перехват)"),
        Pattern("abortBroadcast", 15, "abortBroadcast — SMS перехват и отмена"),

        // === Overlay-фишинг (banker style — README §3.6 Ajina.Banker) ===
        // TYPE_APPLICATION_OVERLAY есть у легитимных chat-heads/overlay-приложений → умеренно.
        Pattern("TYPE_APPLICATION_OVERLAY", 12, "Application overlay (banker fake oynasi)"),
        Pattern("TYPE_PHONE", 10, "Eski overlay TYPE_PHONE — banker xattilik belgisi"),
        // Accessibility orqali OTP'ni topish (это уже malware-специфично):
        // getText o'zi juda keng tarqalgan → tip deskriptori bilan juftlab qidiramiz.
        Pattern("AccessibilityEvent;", 25, "Accessibility orqali matn o'qish (OTP grabber)", needle2 = "getText"),
        Pattern("AccessibilityNodeInfo;", 20, "AccessibilityNode matn o'qish", needle2 = "getText"),
        Pattern("performGlobalAction", 18, "performGlobalAction (Accessibility orqali tap simulyatsiyasi)"),

        // === Anti-analysis (README §3.6 — Ajina.Banker hiylalari) ===
        // Bu ramkalardan birini ko'rsa — malware ataylab tahlilga qarshi qurilgan.
        Pattern("frida-server", 35, "Anti-Frida check (tahlilga qarshi)"),
        Pattern("frida/gadget", 35, "Anti-Frida gadget probing"),
        Pattern("com.topjohnwu.magisk", 30, "Anti-Magisk (root check)"),
        Pattern("/data/local/tmp/", 12, "Suspicious tmp path probe"),
        Pattern("Landroid/net/VpnService;", 0, "VpnService"),   // легитимные VPN/firewall — не штраф
        // "setMethod(ZipEntry.DEFLATED)" needle O'CHIRILDI (2026-08-13): bu manba-kod
        // ifodasi DEX string-pool'da hech qachon yaxlit satr bo'lib uchramaydi
        // (setMethod — alohida metod-nom yozuvi, DEFLATED — compile-time int) — o'lik imzo edi.

        // === Foreground process snooping (overlay timing) — есть у launcher'ов, task-killer'ов ===
        Pattern("getRunningAppProcesses", 5, "getRunningAppProcesses (qaysi ilova ochiq?)"),
        Pattern("UsageStatsManager", 5, "UsageStatsManager (overlay trigger uchun)"),
        Pattern("topActivity", 0, "topActivity"),

        // === Screen capture — есть у легитимных cast/recorder приложений → умеренно ===
        Pattern("Landroid/media/projection/MediaProjection;", 8, "MediaProjection (ekran yozish)"),
        Pattern("createVirtualDisplay", 5, "VirtualDisplay (ekran ko'chirish)"),

        // === Click hijacking / accessibility takeover ===
        Pattern("ACCESSIBILITY_SERVICE", 0, "AccessibilityService registration"),  // слишком общая строка
        Pattern("dispatchGesture", 18, "Programmatik gesture (accessibility hijack)")
    )

    fun analyze(apkPath: String): Findings {
        val found = mutableListOf<Pair<String, Int>>()
        var totalScore = 0
        var packer: String? = null
        var dexCount = 0
        // RuleEngine uchun kichik-harfli DEX matni (bounded). null qoladi, agar hech nima o'qilmasa.
        val ruleText = StringBuilder()

        try {
            ZipFile(apkPath).use { zip ->
                val entries = zip.entries().toList().filter {
                    it.name.startsWith("classes") && it.name.endsWith(".dex") && !it.isDirectory
                }
                dexCount = entries.size

                for (entry in entries) {
                    // ENG-02: katta DEX'ni butunlay TASHLAB YUBORMAYMIZ. Avval >30MB DEX continue bilan
                    // o'tkazib yuborilardi — malware classes.dex'ni ~31MB'gacha "padding" qilib butun bir
                    // tirни (anti-Frida/anti-Magisk/TracerPid/SMS/overlay/packer/C2) o'chirib qo'yardi
                    // (dexFindings.score=0, evasionCount=0). Endi hajmidan qat'i nazar birinchi
                    // SAMPLE_SIZE (4MB) baytni doimo o'qiymiz — paттернlar odatda boshida.
                    if (entry.size <= 0L) continue
                    val readBytes = entry.size.coerceAtMost(SAMPLE_SIZE.toLong()).toInt()
                    val buf = ByteArray(readBytes)
                    var off = 0
                    try {
                        zip.getInputStream(entry).use { input ->
                            while (off < readBytes) {
                                val n = input.read(buf, off, readBytes - off)
                                if (n <= 0) break
                                off += n
                            }
                        }
                    } catch (_: Exception) {
                        continue
                    }
                    if (off == 0) continue
                    val data = if (off == readBytes) buf else buf.copyOf(off)
                    // DEX string pool — UTF-8. ISO-8859-1 ловит всё то же + бинарные совпадения.
                    val text = String(data, Charsets.ISO_8859_1)

                    // RuleEngine haystack'i uchun kichik-harfli matnni yig'amiz (umumiy cap ostida).
                    if (ruleText.length < MAX_RULE_TEXT) {
                        val remaining = MAX_RULE_TEXT - ruleText.length
                        val slice = if (text.length <= remaining) text else text.substring(0, remaining)
                        ruleText.append(slice.lowercase())
                    }

                    for (pattern in PATTERNS) {
                        // Балл 0 = задокументированный, но безобидный паттерн (рефлексия,
                        // Base64, System.load...). Не добавляем в находки, чтобы не шуметь.
                        if (pattern.score <= 0) continue
                        // needle2 bo'lsa — ikkala string ham shu dex ichida bo'lishi shart
                        // (metod-havola DEX'da deskriptor + metod nomi ko'rinishida alohida yotadi).
                        val matched = text.contains(pattern.needle) &&
                            (pattern.needle2 == null || text.contains(pattern.needle2))
                        if (matched) {
                            // Не дублируем один и тот же needle между classes2.dex/classes3.dex
                            if (found.none { it.first == pattern.label }) {
                                found.add(pattern.label to pattern.score)
                                totalScore += pattern.score
                                // Packer detected — отмечаем отдельно
                                if (packer == null && pattern.label.endsWith("packer")) {
                                    packer = pattern.label
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "DEX analysis failed", e)
        }

        // >5 dex — подозрительно (multidex обычно 2-3 для крупных приложений).
        val tooMany = dexCount > 5
        if (tooMany) {
            found.add("$dexCount ta DEX fayl (multidex normada 1-3)" to 15)
            totalScore += 15
        }

        return Findings(
            score = totalScore,
            patterns = found.map { it.first },
            packerDetected = packer,
            tooManyDex = tooMany,
            dexStringsLower = if (ruleText.length == 0) null else ruleText.toString(),
        )
    }
}
