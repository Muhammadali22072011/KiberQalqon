package com.uzguard

/**
 * Blacklist SHA-256 hashes faylov samih APK (ne sertifikatov — dlya togo est' [MaliciousCerts]).
 *
 * Esli kakoy-to virus rasprostranyaetsya pod razlichnymi imenami ("RASMLAR (18).apk",
 * "2_5222407840815161050.apk" i t.d.), no soderzhimoe bit-v-bit odno i to zhe, hash budet
 * tot zhe. Eto samaya tochnaya sigantura: zlyumu pridyotsya peresobrat' APK, chtoby uyti.
 *
 * Format: sha256(apk_file_bytes) -> kodovoe imya semeystva.
 *
 * ШИФРОВАНИЕ ([Shield]):
 *   Сами хэши и метки семейств хранятся в hex-шифре, а не открытым текстом —
 *   чтобы `strings uzguard.apk | grep` не выдал наш blacklist и атакующий
 *   не узнал, какие именно образцы мы детектим. Открытый текст — только в
 *   комментариях (они НЕ попадают в скомпилированный APK).
 *
 * Kak dobavlyat':
 *   1) python scripts/shield_encode.py "<sha256>" "<Family.label>"
 *   2) Vstav' poluchennye Shield.dec("...") to Shield.dec("...") syuda + kommentariy.
 *   (Ili dobav' v MALICIOUS_HASHES v scripts/shield_encode.py i zapusti --all.)
 */
object MaliciousHashes {

    private val ENTRIES: Map<String, String> by lazy { build() }

    // Любой сбой расшифровки → пустая карта (fail-open): hash-детектор просто
    // ничего не найдёт, а поведенческие детекторы + ThreatDb-feed продолжат работать.
    // Соответствует правилу "никогда не возвращать ложный SAFE из-за технического сбоя".
    private fun build(): Map<String, String> = try {
        mapOf(
            // Ajina.Banker oilasi — Telegram orqali tarqaladi.
            Shield.dec("332b5333cd672f7b0e372f1c3659844e3adba2b9341d51f407d6d3465994877963dcbbf0ee64fa8ad1d3ecc1f6de9c1a9b7a65c00362a248c468e373ca5951bb") to
                Shield.dec("45745e399a71542f556b7f5b2f039d0a3799e7f6704e"),   // 75dd6895575576c0e83706c07c226cb16d23174e2372d8217626c95797e1b215 -> Ajina.Banker.lzthzvxte (RASMLAR (18).apk)
            Shield.dec("3c7a0531ca6d2e2d58617f183559824b3edaa8bf611904a7048087120cc58678318feaa0e637aad6dad9e8c0aad29c1fcf7566c7026ba41acd67b6779d5259ec") to
                Shield.dec("45745e399a71542f556b7f5b2f008c0a3888e2fa"),   // 8d2f128ccae146e5a991e26c45ffc2c0d7cc9dd999338424c911b03e0805599b -> Ajina.Banker.oktgkst (RASMLAR (8).apk)
            Shield.dec("302601679a3e702f09322e1d635fd34d6fd1a7be601a0af10385d0125dc1842b60ddbaa0e667fc89da85edc3f1dfc84f9a2e61c00168a54d9869e276985c05b7") to
                Shield.dec("45745e399a71542f556b7f5b2f169d0d398df8eb"),   // 4860aafa2244b0430260d185301f26ac5e3c942f9e60c9fd6b66a322e6d407e9 -> Ajina.Banker.yzsfnie (toydanfotolar(9.jpg) (10).apk)
            Shield.dec("332e0460cc6c237d0e397f4f3856831c3cd7f0b83c1905a25585d44258c4dd2e6481ebfaec35ffd98683ecc0a082961c9d2831c4566bf41bc96bb37acd5201e8") to
                Shield.dec("45745e399a71542f556b7f5b2f019d113380e6e6"),   // 7037735359ef99dbc4a6827fe056738f19b93f16ec732d871df260cd4458e9af -> Ajina.Banker.nzolcwh (com.nzolcwh.jdzkycd)
            Shield.dec("332a5336c968247f0b62794d3009d74867daa1bf3d130aa701d3874709cf84296cd9bdf3bb35a889d281edc3f3d59f1995786093533ea3489b6ebf77cc5d51ec") to
                Shield.dec("45745e399a71542f556b7f5b2f0d88073e96fefd605f5b"),   // 74da27210bcd1f068901988c1ff3f8aa9a40dfff1a60a312947e3e47f195d61b -> Ajina.Banker.boyauosdti (com.boyauosdti.ewqejxsd)
            Shield.dec("662a0066ca3a77780f327b10360a861d6ed3f4ba354803a25183d2455992847c33debdfbeb65fcde86d2edc0a7849d199e7431935938a54ecf3eb277cb5f01bb") to
                Shield.dec("45745e399a71542f556b7f5b2f0c8b102994e8fc6b"),   // b4711ea642a97eac10e41c1fa6316ea4ff484621e2635b3228fe9c212a45c4a5 -> Ajina.Banker.clnvwyro (com.clnvwyro.jjewziwhq)
            Shield.dec("66295466ca6e2f2d02312d1c625dd31f69d5a2bf614f51a1078185170d95d47064d9b8a6e767abdc8082eecaf184964f982a3690043da41ccf39e427ce5253e8") to
                Shield.dec("45745e399a71542f556b7f5b2f1d941b288cebf676405c"),   // b7c1119c9175c24a6631edce74dcbb181a1e84e3cb59cb8d4fafdf3c2fbef93f -> Ajina.Banker.rsewozxrkn (RasmlarAlbum_2026...apk)
            Shield.dec("362a0435c96770280b617910670bd4493c86a4ed321f04f4548dd5400d94dd7c6d8de8a6bd64abdfd1d4e2c4f0de9f1f9e7d64ce546caf49993eb2279c5204ed") to
                Shield.dec("45745e399a71542f556b7f5b2f198919308cf2ef674040"),   // 243b28ff0ac9fd37ce5c6460d844bc8485aeb7e02497b81421384786da4e49dc -> Ajina.Banker.vngoocackr (Toydan fotolar (20)...apk)
            Shield.dec("662900369d3e22765864221e625b821a3adba4bc624f57f207d0854757c2dd2b648db1a7bd65fad9d1d3b895f383cd49ca2834c55163f51ccb6be423905a01bc") to
                Shield.dec("45745e399a71542f556b7f5b2f03861c2c80fdeb654557b6"),   // b77afa48cd87c4ede852fde67ed3858c158db64623cfaecbfdc318bc64ba81a2 -> Ajina.Banker.labscleaner (Toydan fotolar (22)...apk, org.labs.cleaner)
            Shield.dec("34280f61c23a247f0f61224f630cdf486685a9b6611b54f456d784170ec4d47d3388bff0e76af6d6d281b8c2f7d5cd1dcd2866c6516ea51b9e3ee07b980d56b8") to
                Shield.dec("45745e399a71542f556b7f5b2f1a9d073591f6e269"),   // 06869e214a8fbc869f88e0f0fbeca315f06389891ac1e3c6ad10152dcaf90f66 -> Ajina.Banker.uzyjrglm (VID_22032026_74383.mp4 (2).apk)
            Shield.dec("3d295334ca6f232a5f642b4d3409d6466a87a3b8671b53f455d7d14d0d92d1716089bbf2e660f88cd684edc0f7d7cd1e9c7a36c30363ae1c9b6fb2739d0a50b8") to
                Shield.dec("45745e399a71542f556b7f5b2f1d8f113a8affe3625f41"),   // 97dc105ddd1d5f185d26c0a0eb09be495121936c5d63e1c506a5c89cf0415a06 -> Ajina.Banker.rhoeinmfts (VID_22032026_74383.mp4 (3).apk)
            Shield.dec("33285234986d2e2b0e30281a345ed54668daf0be671e00f556d6d6155692d57e318eb1fabe31fcd88584eac6a282cd4a9a7a6397566ea34cc968b3269e0f59bc") to
                Shield.dec("45745e399a71542f556b7f5b2f088c123584f7e96c4a"),   // 76ecc28e5023512879a0c521fc7a9e06d689ab27fd150dca664a6543475d6d92 -> Ajina.Banker.gkljgfgha (Video_159-289.mp4 (3).apk)
            Shield.dec("332c0034ca3921760c627e183659df1a3ed7a0ef321d0ba00187d9410d93872e60dceda5e630fdd8d6d4be91ab80cb129e7a65975362a04c993eb2709a5f58b7") to
                Shield.dec("45745e399a71542f556b7f5b2f00940c3d8aebe1655e59"),   // 727c1f787bd1768da41a669d1285bdbf5ddf9c3754eb9fe9262a3973da422489 -> Ajina.Banker.osrbizoauk (SEKIS_PORNO_VIDEO_ZIP.apk)

            // RoundRift dropper (ydbllnjd.com).
            Shield.dec("327d5666cc3e742a08387e4a3956d04e3e86f0e8621307a05386d91656c0dc7f61dcbaf7eb66acdc80d8ecc4f4d1ca489a2e66975569a74bc86cb777cb0854bd") to
                Shield.dec("567142399f0d7f284f2e634d63038b103587"),   // 6ca17abd38dc8970aeaff85dc38b97974d3445b3c877f7dc6b1a52045315cc43 -> RoundRift.ydbllnjd (VIDEO.20.01.2026.mp4 (2).apk)

            // Uzbek-dropper.vudgi (pyw.kzxwc) — HANGUL FILLER hiylasi, assets/vudgi.json (552KB).
            Shield.dec("317b5365cd3e26780b637e10340ad74f6dd7f3eb351954f3548cd5150992d42e6488b1fbef36f6d787d6eec4a0d59c18c9796794556ca61bcc67e075cb5a57ba") to
                Shield.dec("516455329072723c54706a4c7341910b3b84f8"),   // 5ed26a060cd95e0124be12f7d94afe1f10880e88d6572323e50b571d18f7c174 -> Uzbek-dropper.vudgi (Video_202_089_mp8.apk)

            // Uzbek-dropper.taklifnoma — ZIP-encrypted entries (481/484) AV-evasion.
            Shield.dec("607b0061c83d232c03312c4d640dd64a3ed2f3b7371801f0068c824d5f91d62e678fbdf0bd30af8c81d8b8caa4829e4ac87d35cf5362a01dcf67e370995a55ec") to
                Shield.dec("516455329072723c54706a4c7341931f348ff8e86a445fa5"),   // de763b5b816deb14a1b9333469c90f3f2743bcacb8c96d0ad1b9397b28e2115b -> Uzbek-dropper.taklifnoma (TAKLIFNOMA TOY AVGUST...apk)
        )
    } catch (_: Throwable) {
        emptyMap()
    }

    /** Vozvrashchaet imya semeystva, esli APK-hash v blacklist. */
    fun maliciousFamily(sha256: String?): String? {
        if (sha256.isNullOrBlank()) return null
        val key = sha256.lowercase()
        // 1) qo'lda tahlil qilingan, oilasi aniq baza (yuqori sifat, mahalliy namunalar)
        // 2) assets'dan yuklangan keng feed (ThreatDb) — fallback. Init bo'lmasa null qaytaradi.
        return ENTRIES[key] ?: ThreatDb.fileHashFamily(key)
    }

    /** Diagnostika uchun: qo'lda kiritilgan yozuvlar soni (feed'siz). */
    fun curatedCount(): Int = ENTRIES.size
}
