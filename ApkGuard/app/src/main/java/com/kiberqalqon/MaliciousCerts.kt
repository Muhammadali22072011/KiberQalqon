package com.kiberqalqon

/**
 * Blacklist цифровых подписей известных малварных групп.
 *
 * У дроппер-вирусов в Узбекистане ("TAKLIFNOMA TOY...", "Video_202_089...") один генератор
 * собирает все APK с одним самоподписанным сертификатом. Если поймать fingerprint —
 * детектим ВСЕ APK этой группы, даже с новыми package-name и обфускацией.
 *
 * Это самая надёжная сигнатура: домены/строки можно менять, ключ — нет
 * (иначе пришлось бы выпускать новый billing-ID, что для криминала дорого).
 *
 * Формат: sha256(cert.getEncoded()) -> кодовое имя семейства (для отчёта).
 *
 * ШИФРОВАНИЕ ([Shield]): cert-fingerprint'ы и метки семейств хранятся в hex-шифре,
 * чтобы `strings`/grep по APK не выдал, какие подписи мы блокируем (это самый
 * стабильный IOC — раскрыв его, атакующий бы знал, что пора перевыпустить ключ).
 * Открытый текст — только в комментариях (не компилируются в APK).
 */
object MaliciousCerts {

    private val ENTRIES: Map<String, String> by lazy { build() }

    // Сбой расшифровки → пустая карта (fail-open): cert-детектор молчит,
    // но hash/package/поведенческие детекторы + ThreatDb-feed работают.
    private fun build(): Map<String, String> = try {
        mapOf(
            // Образец: ⬅️TAKLIFNOMA TOY AVGUST 1325478967.apk (virus.apk, ~2.8 MB)
            // — дроппер с зашифрованным DEX (481 файл за паролем).
            Shield.dec("317a556f9a6a207803362e113159d6183cd0a9b6651f01a0568cd64c59c4847937ddbaa0bc6aacddd3d7edc7f1809b499b7965945568a2489c66b174cb5850b9") to
                Shield.dec("516455329072723c54706a4c7341931f348ff8e86a445fa5"),   // 5db8a5668648061fc388a43df97863a1be3cc9b20764cf5b752b5357a976c307 -> Uzbek-dropper.taklifnoma

            // Образец: Video_202_089_mp8 (юникод-spaces).apk (virus_sample.apk, ~1.5 MB)
            // — pyw.kzxwc dropper, открывает WebView с C2-сервером, ставит другие APK.
            Shield.dec("3d2b03329e68277e58342e18380bd61c3ad6a6be65120af304d0d44059c7d37d65dbb9f2eb35f7d7dad7b890a6d39a499f2836c65239f447cd3cbe23ca5f52ea") to
                Shield.dec("516455329072723c54706a4c7341910b3b84f8"),   // 954ee710c4419d1be570a9874e5460650c014f9897cc454b3da02bc80c8ab42d -> Uzbek-dropper.vudgi

            // Добавлять новые так:
            //   python scripts/extract_cert_fingerprint.py path/to/sample.apk   (получить sha256)
            //   python scripts/shield_encode.py "<sha256>" "Family"             (зашифровать)
            // → вставить полученные Shield.dec("...") to Shield.dec("...") сюда.
        )
    } catch (_: Throwable) {
        emptyMap()
    }

    /** Возвращает имя малварного семейства, если подпись в blacklist. */
    fun maliciousFamily(sha256: String?): String? {
        if (sha256.isNullOrBlank()) return null
        val key = sha256.lowercase()
        // 1) qo'lda kiritilgan, oilasi aniq sertifikatlar
        // 2) assets'dan yuklangan tashqi feed (ThreatDb) — fallback
        return ENTRIES[key] ?: ThreatDb.certFamily(key)
    }

    /** Diagnostika uchun: qo'lda kiritilgan sertifikatlar soni (feed'siz). */
    fun curatedCount(): Int = ENTRIES.size
}
