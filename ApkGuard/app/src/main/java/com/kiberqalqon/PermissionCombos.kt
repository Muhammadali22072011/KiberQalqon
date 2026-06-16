package com.uzguard

/**
 * Анализ КОМБИНАЦИЙ разрешений, а не их количества.
 *
 * Современная Android-малварь часто запрашивает всего 2-3 разрешения, но в
 * специфической комбинации, которая выдаёт её цель:
 *
 *  • SMS-стилер: READ_SMS + RECEIVE_SMS + INTERNET (+опц. BOOT_COMPLETED)
 *  • Overlay-фишер: SYSTEM_ALERT_WINDOW + BIND_ACCESSIBILITY_SERVICE
 *  • Credential-стилер: GET_ACCOUNTS + AUTHENTICATE_ACCOUNTS + READ_CONTACTS + INTERNET
 *  • Ransomware: BIND_DEVICE_ADMIN + WRITE_EXTERNAL_STORAGE
 *  • Dropper: REQUEST_INSTALL_PACKAGES + INTERNET + READ_EXTERNAL_STORAGE
 *  • Stealth-spy: RECORD_AUDIO + CAMERA + ACCESS_FINE_LOCATION + INTERNET (одновременно)
 *  • Call-hijack: CALL_PHONE + READ_CALL_LOG + PROCESS_OUTGOING_CALLS
 *
 * Score-based: каждая совпавшая комбинация даёт ball'ы; >=50 → DANGER uplift.
 */
object PermissionCombos {

    data class Combo(
        val label: String,
        val score: Int,
        val required: Set<String>,
        val anyOf: Set<String> = emptySet()  // хотя бы одно из этих тоже должно быть
    )

    data class Match(
        val combo: Combo,
        val matched: Set<String>
    )

    private val P = "android.permission."

    // РЕКАЛИБРОВКА (2026-05): "capability-breadth" комбо (камера+микрофон+гео,
    // контакты+гео, accounts) есть у ЛЕГИТИМНЫХ соцсетей/видео/мессенджеров и давали
    // false-positive. Им снижен балл. Реально malware-СПЕЦИФИЧНЫЕ комбо (SMS+чтение,
    // overlay+accessibility, SMS+accessibility) сохраняют высокий балл — легитимные
    // приложения такие сочетания не используют.
    private val COMBOS: List<Combo> = listOf(
        Combo(
            label = "SMS-stealer (banking OTP)",
            score = 60,
            required = setOf("${P}READ_SMS", "${P}INTERNET"),
            anyOf = setOf("${P}RECEIVE_SMS", "${P}SEND_SMS")
        ),
        Combo(
            label = "Overlay phisher (soxta bank oynasi)",
            score = 60,
            required = setOf("${P}SYSTEM_ALERT_WINDOW"),
            anyOf = setOf("${P}BIND_ACCESSIBILITY_SERVICE", "${P}WRITE_SETTINGS")
        ),
        Combo(
            label = "Credential stealer",
            score = 12,   // GET_ACCOUNTS+INTERNET+контакты есть у любого app со входом через Google
            required = setOf("${P}GET_ACCOUNTS", "${P}INTERNET"),
            anyOf = setOf("${P}AUTHENTICATE_ACCOUNTS", "${P}READ_CONTACTS")
        ),
        Combo(
            label = "Ransomware/wiper alomati",
            score = 45,
            required = setOf("${P}BIND_DEVICE_ADMIN")
        ),
        Combo(
            label = "Dropper (yangi APK o'rnatadi)",
            score = 22,   // браузеры/сторы/Telegram легитимно ставят APK
            required = setOf("${P}REQUEST_INSTALL_PACKAGES", "${P}INTERNET")
        ),
        Combo(
            label = "Stealth-spy (mikrofon+kamera+geo)",
            score = 15,   // видеозвонки/соцсети/камеры просят ровно это
            required = setOf("${P}RECORD_AUDIO", "${P}CAMERA", "${P}ACCESS_FINE_LOCATION", "${P}INTERNET")
        ),
        Combo(
            label = "Call hijacker",
            score = 35,
            required = setOf("${P}CALL_PHONE", "${P}READ_CALL_LOG"),
            anyOf = setOf("${P}PROCESS_OUTGOING_CALLS", "${P}ANSWER_PHONE_CALLS")
        ),
        Combo(
            label = "Yashirin auto-launch (boot + foreground)",
            score = 5,    // огромное число легитимных foreground-сервисов
            required = setOf("${P}RECEIVE_BOOT_COMPLETED", "${P}FOREGROUND_SERVICE")
        ),
        Combo(
            label = "Kontakt+lokatsiya o'g'irlash",
            score = 8,    // соцсети/карты/доставка
            required = setOf("${P}READ_CONTACTS", "${P}ACCESS_FINE_LOCATION", "${P}INTERNET")
        ),
        Combo(
            label = "Notification interception",
            score = 30,
            required = setOf("${P}BIND_NOTIFICATION_LISTENER_SERVICE"),
            anyOf = setOf("${P}INTERNET")
        ),

        // === Markaziy Osiyo banker classics — README §3.6 Ajina.Banker pattern ===

        // OTP-grabber: SMS_READ + ACCESSIBILITY = klassik bank-trojan kombo.
        // Accessibility ekrandagi matnni o'qiydi, SMS ruxsati bank kodlarni
        // perehvat qiladi. Bu yakka o'zi DANGER beradigan eng kuchli kombo.
        Combo(
            label = "OTP-grabber (SMS+Accessibility)",
            score = 90,
            required = setOf("${P}READ_SMS", "${P}BIND_ACCESSIBILITY_SERVICE")
        ),

        // Full-banker: overlay + accessibility + internet = polnocennyy banking trojan.
        // Risuyet fake-app poverkh real'nogo, perehvatyvayet vse vvody, otpravlyaet na C2.
        Combo(
            label = "Full banker (overlay+a11y+net)",
            score = 100,
            required = setOf(
                "${P}SYSTEM_ALERT_WINDOW",
                "${P}BIND_ACCESSIBILITY_SERVICE",
                "${P}INTERNET",
            )
        ),

        // Persistent botnet: boot + foreground + accessibility = ne ub'esh' ot reboot do uninstall.
        Combo(
            label = "Persistent botnet (boot+a11y+fg)",
            score = 70,
            required = setOf(
                "${P}RECEIVE_BOOT_COMPLETED",
                "${P}BIND_ACCESSIBILITY_SERVICE",
                "${P}FOREGROUND_SERVICE",
            )
        )
    )

    /**
     * Главная точка входа. Возвращает все совпавшие combo + summary score.
     */
    fun evaluate(requestedPermissions: Collection<String>): List<Match> {
        val set = requestedPermissions.toSet()
        val matches = mutableListOf<Match>()
        for (combo in COMBOS) {
            if (!set.containsAll(combo.required)) continue
            if (combo.anyOf.isNotEmpty() && combo.anyOf.none { it in set }) continue

            val matched = (combo.required + combo.anyOf.intersect(set))
            matches.add(Match(combo, matched))
        }
        return matches
    }

    /** Суммарный score по списку match'ей. */
    fun totalScore(matches: List<Match>): Int = matches.sumOf { it.combo.score }
}
