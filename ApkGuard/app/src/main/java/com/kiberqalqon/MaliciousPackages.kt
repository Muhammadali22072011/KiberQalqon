package com.uzguard

/**
 * Blacklist po package name. Eto vtoroy uroven' posle [MaliciousHashes]: esli
 * zloy peresobral APK (bayts izmenilis' → hash drugaya), no package name ostalsya
 * tem zhe — vse ravno DANGER.
 *
 * Format: package name (lowercase) → kodovoe imya semeystva.
 *
 * ШИФРОВАНИЕ ([Shield]): имена пакетов и метки семейств хранятся в hex-шифре,
 * чтобы `strings`/grep по APK не показал наш blacklist. Открытый текст — только
 * в комментариях (они НЕ компилируются в APK).
 *
 * Kuda eto dobavlyaetsya:
 *  - Pol'zovatel' Le Muhammadali yavno otmetil chto eti paketi — virus
 *  - Sgenerirovano iz Telegram community-otchetov
 *  - Iz README §10 (RASMLAR (18).apk family, RoundRift dropper)
 *
 * Dobavit' novyy: python scripts/shield_encode.py "com.paket" "Family" → vstav' syuda.
 */
object MaliciousPackages {

    private val ENTRIES: Map<String, String> by lazy { build() }

    // Сбой расшифровки → пустая карта (fail-open): не блокируем по пакету,
    // но cert/hash/поведенческие детекторы остаются активны.
    private fun build(): Map<String, String> = try {
        mapOf(
            // Ajina.Banker — Markaziy Osiyo bank troyani.
            Shield.dec("67715a79972562264176625d64419f1f258cf0e27e535ab6") to
                Shield.dec("45745e399a71542f556b7f5b"),   // com.lzthzvxte.xazoalzxhr -> Ajina.Banker
            Shield.dec("67715a799434622950736e07731d840e3484f4") to
                Shield.dec("45745e399a71542f556b7f5b"),   // com.oktgkst.rrcpkge -> Ajina.Banker
            Shield.dec("67715a798225652855697f07720594042b93f9fe6d58") to
                Shield.dec("45745e399a71542f556b7f5b"),   // com.yzsfnie.sjsztphpis -> Ajina.Banker
            Shield.dec("67715a798b2a7e284d79694b2f019d1c3997e4e0695a43") to
                Shield.dec("45745e399a71542f556b7f5b"),   // com.puhfvysb.nzbftunmqq -> Ajina.Banker

            // 2026-05-28 partiyasi — "вирусы" papkasidan yangi to'liq namunalar.
            Shield.dec("67715a7995257922587772076b0b9d152680f5") to
                Shield.dec("45745e399a71542f556b7f5b"),   // com.nzolcwh.jdzkycd -> Ajina.Banker (Rasmlar (11) (3).apk)
            Shield.dec("67715a7999306f2f4e6f694d7506c91b2892f4e47c5856") to
                Shield.dec("45745e399a71542f556b7f5b"),   // com.boyauosdti.ewqejxsd -> Ajina.Banker (Rasmlar (11) (4/5/6).apk)
            Shield.dec("67715a79983378384c7968462f058d1b2899f8f96c5a") to
                Shield.dec("45745e399a71542f556b7f5b"),   // com.clnvwyro.jjewziwhq -> Ajina.Banker (Rasmlar (9).apk)
            Shield.dec("67715a79892c7339547a625b6a01c9182f8de2ef7041") to
                Shield.dec("45745e399a71542f556b7f5b"),   // com.rsewozxrkn.fpnsatj -> Ajina.Banker (RasmlarAlbum_2026...apk)
            Shield.dec("67715a798d31712154637b4a6a1dc91b2f90e5e86c") to
                Shield.dec("45745e399a71542f556b7f5b"),   // com.vngoocackr.epstfh -> Ajina.Banker (Toydan fotolar (20)...apk)
            Shield.dec("67715a798e256f24496776442f1e84083c94e9ef") to
                Shield.dec("45745e399a71542f556b7f5b"),   // com.uzyjrglm.qcvcwxa -> Ajina.Banker (VID_22032026_74383.mp4 (2).apk)
            Shield.dec("67715a798937792b526e774f751cc91d2b94fcff6349") to
                Shield.dec("45745e399a71542f556b7f5b"),   // com.rhoeinmfts.ctwmqgb -> Ajina.Banker (VID_22032026_74383.mp4 (3).apk)
            Shield.dec("67715a799c347a245c667d4160419f172b87e0f86342") to
                Shield.dec("45745e399a71542f556b7f5b"),   // com.gkljgfgha.xitdqvgi -> Ajina.Banker (Video_159-289.mp4 (3).apk)
            Shield.dec("67715a79942c642c527a75487404c916368be2fc714c50ab") to
                Shield.dec("45745e399a71542f556b7f5b"),   // com.osrbizoauk.hihsrugbo -> Ajina.Banker (SEKIS_PORNO_VIDEO_ZIP.apk)
            Shield.dec("6b6c5079973e743d1563764c6001820c") to
                Shield.dec("45745e399a71542f556b7f5b"),   // org.labs.cleaner -> Ajina.Banker (soxta "cleaner" dropper)

            // RoundRift dropper — paket nomi domen-shaknda.
            Shield.dec("7d7a553b97317c2a15637544") to
                Shield.dec("567142399f0d7f284f"),   // ydbllnjd.com -> RoundRift

            // Uzbek-dropper.vudgi — pyw.kzxwc, "Video_NNN_NNN_mp8.apk" + HANGUL FILLER.
            Shield.dec("7467407990256e3958") to
                Shield.dec("516455329072723c54706a4c7341910b3b84f8"),   // pyw.kzxwc -> Uzbek-dropper.vudgi

            // Uzbek-dropper.taklifnoma — uzbekchill.com, "TAKLIFNOMA TOY ...apk".
            Shield.dec("71645532903c7e27576c344a6e02") to
                Shield.dec("516455329072723c54706a4c7341931f348ff8e86a445fa5"),   // uzbekchill.com -> Uzbek-dropper.taklifnoma
        )
    } catch (_: Throwable) {
        emptyMap()
    }

    /** Returns family code name if the package is in blacklist, else null. */
    fun maliciousFamily(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        val key = packageName.lowercase()
        // 1) qo'lda kiritilgan baza (yuqori sifat, mahalliy namunalar)
        // 2) bulut feed (ThreatDb.packageFamily) — fallback. Init/feed bo'lmasa null.
        return ENTRIES[key] ?: ThreatDb.packageFamily(key)
    }
}
