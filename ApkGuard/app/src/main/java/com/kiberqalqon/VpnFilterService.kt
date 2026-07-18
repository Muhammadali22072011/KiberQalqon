package com.uzguard

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * TAJRIBAVIY (opt-in, default OFF) DNS-sinkhole VPN.
 *
 * Faqat DNS so'rovlarini ushlaydi: VPN marshrutiga FAQAT virtual DNS server qo'shiladi
 * ([addRoute] /32), shuning uchun boshqa trafik VPN'ga UMUMAN kirmaydi — internet
 * tezligi/ishlashiga ta'sir yo'q. Ma'lum C2 domenlariga ([C2_DOMAINS]) so'rov NXDOMAIN
 * bilan bloklanadi (troyan allaqachon o'rnatilgan bo'lsa ham C2 bilan aloqasi uziladi —
 * defense-in-depth). Qolgan domenlar real resolverga ([UPSTREAM_DNS]) uzatiladi.
 *
 * MUHIM — BANK ILOVALARI VPN'DAN CHIQARIB TASHLANADI ([addDisallowedApplication],
 * [KnownBanks.ALL]). Sabab: ko'p bank ilovalari anti-frod SDK'lari qurilmada FAOL VpnService
 * borligini aniqlaydi (TRANSPORT_VPN / tun0 interfeysi) va kirishni bloklaydi — VPN qanday
 * ishlashidan qat'i nazar. Bu "VPN yoqilsa ko'p bank ochilmaydi, brauzer ishlaydi" alomatining
 * eng ehtimolli sababi. Bank paketlarini disallow qilsak — ular trafigi TUN'ga UMUMAN kirmaydi,
 * VPN'ni sezmaydi va odatdagidek ochiladi. C2 filtri qolgan barcha ilovalar uchun ishlashda
 * davom etadi (banklarga baribir sinkhole kerak emas — ular ishonchli).
 *
 * Qo'shimcha himoya: [ALLOW_SUFFIXES] (O'zbekiston bank/fintech domenlari) HECH QACHON
 * bloklanmaydi — brauzerda bank saytiga kirilganda bulut feed'iga xato domen tushib qolsa ham
 * uzilmaydi. (Umumiy bulut zonalari — amazonaws/cloudfront/firebaseio — allowlist'ga
 * QO'SHILMAYDI: malware ko'pincha o'sha yerda C2 saqlaydi, ularni ozod qilish filtrni buzardi.)
 *
 * Avtomatik YOQILMAYDI: foydalanuvchi sozlamalardan yoqadi, tizim VpnService ruxsat
 * oynasini tasdiqlaydi ([prepareIntent] → startActivityForResult → [start]).
 *
 * ⚠️ Bu modulni REAL QURILMADA sinash kerak. Kamchilik bo'lsa eng yomon holatda DNS
 * ishlamaydi (VPN'ni o'chirish kifoya) — boshqa trafik buzilmaydi.
 * ⚠️ Ma'lum cheklovlar (opt-in, tajribaviy): DNS-over-TCP (TC=1 dan keyingi qayta so'rov) va
 * DoT/DoH/QUIC ushlanmaydi — u holatlarda filtr o'tkazib yuboradi (blok emas, bypass).
 */
class VpnFilterService : VpnService() {

    @Volatile private var running = false
    private var vpnInterface: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    // TUN'ga yozish bir nechta forward-thread'dan kelishi mumkin — FileOutputStream.write thread-safe
    // emas, shuning uchun yozishni serializatsiya qilamiz.
    private val outputLock = Any()
    // Fail-open kill-switch hisobi: upstream ketma-ket necha marta ishlamadi.
    private val consecutiveUpstreamFailures = java.util.concurrent.atomic.AtomicInteger(0)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        try {
            val builder = Builder()
                .setSession("UzGuard C2 filter")
                .addAddress(VIRT_ADDR, 32)
                .addDnsServer(VIRT_DNS)
                .addRoute(VIRT_DNS, 32)          // FAQAT virtual DNS serverga trafik ushlanadi
                .setMtu(VIRT_MTU)                // katta EDNS0 javob (4096) TUN'ga sig'sin
                .setBlocking(true)
                .setConfigureIntent(
                    PendingIntent.getActivity(
                        this, 0, Intent(this, SettingsActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
            // Bank/fintech/to'lov ilovalarini VPN'dan CHIQARIB tashlaymiz — ular VPN'ni
            // sezmasin va anti-frod bloklamasin. addDisallowedApplication o'rnatilmagan paketda
            // NameNotFoundException tashlaydi → har birini alohida try/catch bilan o'tkazamiz.
            var excluded = 0
            for (bank in KnownBanks.ALL) {
                try { builder.addDisallowedApplication(bank.pkg); excluded++ }
                catch (_: Throwable) { /* o'rnatilmagan — o'tkazamiz */ }
            }
            Log.i(TAG, "VPN: $excluded ta bank ilovasi filtrdan chiqarildi")

            val pfd = builder.establish()
            if (pfd == null) {
                Log.w(TAG, "establish() null — VPN ruxsati berilmagan?")
                stopVpn()
                return
            }
            vpnInterface = pfd
            running = true
            worker = Thread({ runLoop(pfd) }, "kq-vpn").also { it.start() }
            Log.i(TAG, "VPN C2 filter started")
        } catch (e: Throwable) {
            Log.e(TAG, "startVpn failed", e)
            stopVpn()
        }
    }

    private fun runLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        // Har bir DNS so'rovi ALOHIDA qisqa muddatli thread'da yo'naltiriladi — bitta sekin/o'lik
        // upstream butun qurilma DNS'ini STOPORlamasin (eski xato: bitta bloklovchi soket + 4s timeout
        // + bitta thread = har bir sekin so'rov butun TUN o'qishni to'xtatardi). Pool to'lib ketsa
        // ortiqcha so'rovlar TASHLANADI (DiscardPolicy) — ilova resolveri qayta urinadi, TUN o'qish esa
        // HECH QACHON bloklanmaydi.
        val pool = java.util.concurrent.ThreadPoolExecutor(
            2, 16, 10L, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.SynchronousQueue(),
            java.util.concurrent.ThreadPoolExecutor.DiscardPolicy()
        )
        val packet = ByteArray(32767)
        try {
            while (running) {
                val n = try { input.read(packet) } catch (_: Throwable) { break }
                if (n <= 0) continue
                // packet buferi qayta ishlatiladi — dispatchdan oldin nusxa olamiz.
                val copy = packet.copyOf(n)
                try {
                    pool.execute {
                        try { handlePacket(copy, copy.size, output) }
                        catch (e: Throwable) { Log.w(TAG, "packet error", e) }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "dispatch error", e)
                }
            }
        } finally {
            try { pool.shutdownNow() } catch (_: Throwable) {}
            try { input.close() } catch (_: Throwable) {}
            try { output.close() } catch (_: Throwable) {}
        }
    }

    /** IPv4 + UDP + DNS'ni parse qiladi; faqat UDP/53 bilan ishlaydi. */
    private fun handlePacket(buf: ByteArray, len: Int, out: FileOutputStream) {
        if (len < 28) return
        val verIhl = buf[0].toInt() and 0xFF
        if ((verIhl ushr 4) != 4) return                  // faqat IPv4
        val ihl = (verIhl and 0x0F) * 4
        if ((buf[9].toInt() and 0xFF) != 17) return        // faqat UDP
        val srcIp = byteArrayOf(buf[12], buf[13], buf[14], buf[15])
        val dstIp = byteArrayOf(buf[16], buf[17], buf[18], buf[19])
        val u = ihl
        if (u + 8 > len) return
        val srcPort = ((buf[u].toInt() and 0xFF) shl 8) or (buf[u + 1].toInt() and 0xFF)
        val dstPort = ((buf[u + 2].toInt() and 0xFF) shl 8) or (buf[u + 3].toInt() and 0xFF)
        if (dstPort != 53) return
        val dnsStart = u + 8
        val dnsLen = len - dnsStart
        if (dnsLen < 12) return
        val dns = ByteArray(dnsLen)
        System.arraycopy(buf, dnsStart, dns, 0, dnsLen)

        val domain = parseDnsQName(dns)
        // Bank/fintech domenlari HECH QACHON bloklanmaydi (allowlist) — faqat uzatiladi.
        // Bulut feed'iga xato yozuv tushsa ham (brauzerda) bank saytini buzmaydi.
        if (domain != null && !isAllowed(domain) && isBlockedC2(domain)) {
            Log.w(TAG, "C2 DNS bloklandi: $domain")
            val resp = buildNxdomain(dns) ?: return
            writeUdpResponse(out, dstIp, srcIp, dstPort, srcPort, resp)  // src=DNS, dst=ilova
            return
        }
        val reply = forwardDns(dns) ?: return
        writeUdpResponse(out, dstIp, srcIp, dstPort, srcPort, reply)
    }

    private fun parseDnsQName(dns: ByteArray): String? {
        if (dns.size < 13) return null
        var pos = 12
        val sb = StringBuilder()
        while (pos < dns.size) {
            val l = dns[pos].toInt() and 0xFF
            if (l == 0) break
            if (l and 0xC0 != 0) return null               // savolda kompressiya kutilmaydi
            pos++
            if (pos + l > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (j in 0 until l) sb.append((dns[pos + j].toInt() and 0xFF).toChar())
            pos += l
        }
        val s = sb.toString().lowercase()
        return s.ifEmpty { null }
    }

    private fun buildNxdomain(query: ByteArray): ByteArray? {
        if (query.size < 12) return null
        var pos = 12
        while (pos < query.size) {
            val l = query[pos].toInt() and 0xFF
            if (l == 0) { pos++; break }
            if (l and 0xC0 != 0) return null
            pos += l + 1
        }
        pos += 4                                            // QTYPE + QCLASS
        if (pos > query.size) return null
        val r = query.copyOf(pos)
        r[2] = (r[2].toInt() or 0x80).toByte()              // QR = 1
        r[3] = ((r[3].toInt() and 0xF8) or 0x03).toByte()   // RCODE = 3 (NXDOMAIN)
        r[6] = 0; r[7] = 1                                  // QDCOUNT = 1
        r[8] = 0; r[9] = 0; r[10] = 0; r[11] = 0            // AN/NS/AR = 0
        return r
    }

    /**
     * So'rovni ochiq resolverlarga ([UPSTREAM_DNS]) uzatadi. Birinchi javob bergani ishlatiladi;
     * biri o'lik/sekin bo'lsa keyingisi sinab ko'riladi (bitta flaky resolver so'rovni yo'qotmasin).
     * Soketlar protect()'lanadi — asosiy tarmoqdan chiqadi (real O'zbekiston IP'i bilan, EDNS
     * Client Subnet saqlanadi → CDN/geo javoblar VPN'siz holat bilan bir xil).
     */
    private fun forwardDns(query: ByteArray): ByteArray? {
        if (query.size < 2) return null
        for (server in UPSTREAM_DNS) {
            val reply = queryOne(query, server)
            if (reply != null) {
                consecutiveUpstreamFailures.set(0)
                return reply
            }
        }
        onUpstreamFailure()
        return null
    }

    /** Bitta upstream serverga bitta so'rov (alohida protected soket). */
    private fun queryOne(query: ByteArray, server: String): ByteArray? {
        var sock: DatagramSocket? = null
        return try {
            // Har so'rovga ALOHIDA soket: bitta umumiy soketda boshqa so'rov javobi aralashib
            // ketmaydi, va bitta sekin so'rov boshqalarni bloklamaydi.
            sock = DatagramSocket().also { protect(it); it.soTimeout = UPSTREAM_TIMEOUT_MS }
            val addr = InetAddress.getByName(server)        // server = IP literal → DNS chaqirilmaydi
            sock.send(DatagramPacket(query, query.size, addr, 53))
            // 4096: EDNS0 katta javob (ko'p A-yozuv / DNSSEC) qirqilmasin (setMtu ham 4096).
            val resp = ByteArray(4096)
            val dp = DatagramPacket(resp, resp.size)
            sock.receive(dp)
            // DNS transaction-ID javobda so'rov bilan mos kelishi shart (xato javobni qaytarmaslik uchun).
            if (dp.length < 12 || resp[0] != query[0] || resp[1] != query[1]) null
            else resp.copyOf(dp.length)
        } catch (_: Throwable) {
            null
        } finally {
            try { sock?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Fail-OPEN himoyasi: upstream DNS ketma-ket [MAX_UPSTREAM_FAILURES] marta ishlamasa,
     * VPN'ni o'chiramiz — shunda qurilma tizim DNS'iga qaytadi va butun DNS o'lib qolmaydi
     * (filtr buzilsa — internetni bloklab qo'ymaslik muhim).
     */
    private fun onUpstreamFailure() {
        if (consecutiveUpstreamFailures.incrementAndGet() >= MAX_UPSTREAM_FAILURES) {
            Log.w(TAG, "upstream DNS ketma-ket ishlamadi — VPN o'chirilyapti (fail-open)")
            stopVpn()
        }
    }

    /** IPv4 + UDP paket yasaydi (UDP checksum 0 = ishlatilmaydi, IPv4'da ruxsat). */
    private fun writeUdpResponse(
        out: FileOutputStream,
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        payload: ByteArray,
    ) {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val pkt = ByteArray(totalLen)
        // IPv4 header
        pkt[0] = 0x45                                       // version 4, IHL 5
        pkt[2] = (totalLen ushr 8).toByte(); pkt[3] = (totalLen and 0xFF).toByte()
        pkt[6] = 0x40                                       // Don't Fragment
        pkt[8] = 64                                         // TTL
        pkt[9] = 17                                         // UDP
        System.arraycopy(srcIp, 0, pkt, 12, 4)
        System.arraycopy(dstIp, 0, pkt, 16, 4)
        val ipck = checksum(pkt, 0, 20)
        pkt[10] = (ipck ushr 8).toByte(); pkt[11] = (ipck and 0xFF).toByte()
        // UDP header
        val u = 20
        pkt[u] = (srcPort ushr 8).toByte(); pkt[u + 1] = (srcPort and 0xFF).toByte()
        pkt[u + 2] = (dstPort ushr 8).toByte(); pkt[u + 3] = (dstPort and 0xFF).toByte()
        pkt[u + 4] = (udpLen ushr 8).toByte(); pkt[u + 5] = (udpLen and 0xFF).toByte()
        // pkt[u+6..7] = 0 → UDP checksum yo'q
        System.arraycopy(payload, 0, pkt, u + 8, payload.size)
        // Bir nechta forward-thread bitta TUN'ga yozishi mumkin — serializatsiya qilamiz.
        synchronized(outputLock) {
            out.write(pkt, 0, totalLen)
            out.flush()
        }
    }

    private fun checksum(data: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        var remaining = len
        while (remaining > 1) {
            sum += (((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)).toLong()
            i += 2; remaining -= 2
        }
        if (remaining > 0) sum += ((data[i].toInt() and 0xFF) shl 8).toLong()
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** O'zbekiston bank/fintech domenlari — bloklashdan OZOD (allowlist). Feed xatosidan himoya.
     *  DIQQAT: bu yerga umumiy bulut zonalari (amazonaws.com, cloudfront.net, firebaseio.com...)
     *  QO'SHILMAYDI — malware o'sha yerda C2 saqlaydi, ozod qilinsa filtr ko'r bo'lib qolardi. */
    private fun isAllowed(domain: String): Boolean {
        val d = domain.trimEnd('.').lowercase()
        return ALLOW_SUFFIXES.any { d == it || d.endsWith(".$it") }
    }

    private fun isBlockedC2(domain: String): Boolean {
        val d = domain.trimEnd('.').lowercase()
        if (C2_DOMAINS.any { d == it || d.endsWith(".$it") }) return true
        // Bulut feed'idan kelgan domenlar (CloudBlacklist → ThreatDb.mergeCloudDomains).
        // Subdomain ham bloklansin: a.b.evil.com → b.evil.com → evil.com (TLD tekshirilmaydi).
        // ThreatDb yuklanmagan bo'lsa domainFamily null qaytaradi — fail-safe (blok yo'q).
        // Ommaviy suffiks darajasida ([MaliciousDomains.PUBLIC_SUFFIXES]) so'ramaymiz —
        // xato kiritilgan "netlify.app" kabi zona butun *.netlify.app'ni NXDOMAIN qilmasin.
        var cur = d
        while (true) {
            if (!MaliciousDomains.isPublicSuffix(cur) &&
                try { ThreatDb.domainFamily(cur) } catch (_: Throwable) { null } != null) return true
            val dot = cur.indexOf('.')
            if (dot < 0 || dot == cur.lastIndexOf('.')) break
            cur = cur.substring(dot + 1)
        }
        return false
    }

    private fun stopVpn() {
        running = false
        try { worker?.interrupt() } catch (_: Throwable) {}
        try { vpnInterface?.close() } catch (_: Throwable) {}
        vpnInterface = null
        stopSelf()
    }

    override fun onRevoke() { stopVpn(); super.onRevoke() }
    override fun onDestroy() { stopVpn(); super.onDestroy() }

    companion object {
        private const val TAG = "VpnFilterService"
        const val ACTION_STOP = "com.uzguard.VPN_STOP"
        private const val VIRT_ADDR = "10.111.222.1"
        private const val VIRT_DNS = "10.111.222.2"
        private const val VIRT_MTU = 4096
        // Ochiq resolverlar — ketma-ket sinaladi (biri bloklangan/sekin bo'lsa keyingisi).
        // protect() bilan asosiy tarmoqdan chiqadi (real IP + EDNS Client Subnet saqlanadi).
        private val UPSTREAM_DNS = listOf("8.8.8.8", "1.1.1.1", "8.8.4.4")
        // Sekin upstream'da TUN stopor bo'lmasin — qisqa timeout (eski 4000ms juda uzoq edi).
        private const val UPSTREAM_TIMEOUT_MS = 1500
        // Upstream shuncha marta KETMA-KET ishlamasa — VPN o'chadi (fail-open kill-switch).
        private const val MAX_UPSTREAM_FAILURES = 8

        // Ma'lum C2 domenlari (case-study IOC) — baked minimal to'plam. Bulutdan kelganlar
        // isBlockedC2() ichida ThreatDb orqali DINAMIK qo'shiladi (api/threats?feed=1).
        private val C2_DOMAINS = setOf(
            "elrxzx.com",
            "ydbllnjd.com",
            "ilovekkksfm.com",
            "dashapp-v2.org",
        )

        // ALLOWLIST — bloklashdan OZOD domenlar (faqat O'zbekiston bank/fintech/to'lov saytlari).
        // Maqsad: bulut feed'iga xato domen tushib qolsa ham (brauzerda bank saytiga kirilganda)
        // uzilmasin. Bank ILOVALARI'ning o'zi allaqachon addDisallowedApplication bilan VPN'dan
        // chiqarilgan — bu ro'yxat brauzer/veb yo'li uchun qo'shimcha to'r. Umumiy bulut zonalari
        // ATAYIN yo'q (C2 ko'r-nuqtasi bo'lmasin).
        private val ALLOW_SUFFIXES = setOf(
            "payme.uz", "click.uz", "uzcard.uz", "humocard.uz", "humo.uz",
            "apelsin.uz", "oson.uz", "paynet.uz", "upay.uz",
            "kapitalbank.uz", "uzumbank.uz", "uzum.uz", "tbcbank.uz",
            "hamkorbank.uz", "agrobank.uz", "ipakyulibank.uz", "ipotekabank.uz",
            "infinbank.uz", "davrbank.uz", "anorbank.uz", "asakabank.uz",
            "sqb.uz", "aloqabank.uz", "turonbank.uz", "nbu.uz", "xb.uz",
            "mkbank.uz", "trustbank.uz", "orientfinans.uz", "ziraatbank.uz",
        )

        /** VPN ruxsati kerakmi? null → ruxsat bor, darhol [start] qilsa bo'ladi.
         *  Aks holda qaytgan Intent'ni startActivityForResult bilan ko'rsating. */
        fun prepareIntent(context: Context): Intent? = VpnService.prepare(context)

        fun start(context: Context) {
            context.startService(Intent(context, VpnFilterService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, VpnFilterService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
