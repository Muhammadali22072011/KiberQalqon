package com.kiberqalqon

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

    data class Findings(
        val score: Int,
        val patterns: List<String>,
        val packerDetected: String? = null,
        val tooManyDex: Boolean = false
    )

    private data class Pattern(
        val needle: String,
        val score: Int,
        val label: String
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
        Pattern("Ljava/lang/System;->load", 0, "System.load native chaqiruv"),  // любой app с .so
        Pattern("Ljava/lang/Runtime;->exec", 25, "Runtime.exec (shell chaqiruv)"),

        // Sensitive APIs (часто в analytics/ads SDK легитимных приложений → низкий балл)
        Pattern("Landroid/telephony/TelephonyManager;->getDeviceId", 8, "IMEI o'qish"),
        Pattern("Landroid/telephony/TelephonyManager;->getSubscriberId", 10, "IMSI o'qish"),
        Pattern("Landroid/telephony/TelephonyManager;->getSimSerialNumber", 10, "SIM serial o'qish"),
        Pattern("Landroid/telephony/SmsManager;->sendTextMessage", 35, "SMS yuborish API"),
        Pattern("Landroid/telephony/SmsManager;->sendMultipartTextMessage", 35, "Multipart SMS yuborish"),
        Pattern("android.provider.Telephony.SMS_RECEIVED", 20, "SMS qabul intent"),
        Pattern("android.intent.action.NEW_OUTGOING_CALL", 18, "Chiquvchi qo'ng'iroqlarni ushlash"),

        // Anti-analysis
        Pattern("Landroid/os/Debug;->isDebuggerConnected", 5, "Anti-debug check"),
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
        Pattern("Ljavax/crypto/Cipher;->doFinal", 0, "Cipher.doFinal"),
        Pattern("Landroid/util/Base64;->decode", 0, "Base64 decode"),

        // Дополнительные SMS-стилер маркеры
        Pattern("android.provider.Telephony.SMS_DELIVER", 25, "SMS_DELIVER (приоритетный перехват)"),
        Pattern("abortBroadcast", 15, "abortBroadcast — SMS перехват и отмена"),

        // === Overlay-фишинг (banker style — README §3.6 Ajina.Banker) ===
        // TYPE_APPLICATION_OVERLAY есть у легитимных chat-heads/overlay-приложений → умеренно.
        Pattern("TYPE_APPLICATION_OVERLAY", 12, "Application overlay (banker fake oynasi)"),
        Pattern("TYPE_PHONE", 10, "Eski overlay TYPE_PHONE — banker xattilik belgisi"),
        // Accessibility orqali OTP'ni topish (это уже malware-специфично):
        Pattern("AccessibilityEvent;->getText", 25, "Accessibility orqali matn o'qish (OTP grabber)"),
        Pattern("AccessibilityNodeInfo;->getText", 20, "AccessibilityNode matn o'qish"),
        Pattern("performGlobalAction", 18, "performGlobalAction (Accessibility orqali tap simulyatsiyasi)"),

        // === Anti-analysis (README §3.6 — Ajina.Banker hiylalari) ===
        // Bu ramkalardan birini ko'rsa — malware ataylab tahlilga qarshi qurilgan.
        Pattern("frida-server", 35, "Anti-Frida check (tahlilga qarshi)"),
        Pattern("frida/gadget", 35, "Anti-Frida gadget probing"),
        Pattern("com.topjohnwu.magisk", 30, "Anti-Magisk (root check)"),
        Pattern("/data/local/tmp/", 12, "Suspicious tmp path probe"),
        Pattern("Landroid/net/VpnService;", 0, "VpnService"),   // легитимные VPN/firewall — не штраф
        // ZIP-evasion (GP-flag=0x01 trick — Ajina.Banker'ning antivirus bypass'i):
        Pattern("setMethod(ZipEntry.DEFLATED)", 10, "ZipEntry custom method (ZIP-evasion)"),

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

                    for (pattern in PATTERNS) {
                        // Балл 0 = задокументированный, но безобидный паттерн (рефлексия,
                        // Base64, System.load...). Не добавляем в находки, чтобы не шуметь.
                        if (pattern.score <= 0) continue
                        if (text.contains(pattern.needle)) {
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
            tooManyDex = tooMany
        )
    }
}
