/* ============================================================
   ANOR QALQON — namuna ma'lumotlar (sodda, tushunarli til)
   Texnik jargon YO'Q: XOR/AES, C2, terminal o'rniga
   oddiy odam tushunadigan tushuntirishlar.
   ============================================================ */

// "Nima qiladi" — inson tilida
const DOES = {
  bankPass: { icon: "card", t: "Bank ilovasi parolini o'g'irlaydi" },
  sms:      { icon: "message", t: "SMS tasdiq kodlarini o'qiydi" },
  screen:   { icon: "eye", t: "Ekraningizni yashirincha kuzatadi" },
  hide:     { icon: "lock", t: "O'zini telefondan yashiradi" },
  download: { icon: "download", t: "Telefonga yashirin dasturlar yuklaydi" },
  remote:   { icon: "globe", t: "Telefonni masofadan boshqaradi" },
  contacts: { icon: "user", t: "Kontaktlaringizni o'qiydi" },
  calls:    { icon: "phone", t: "Qo'ng'iroqlaringizni kuzatadi" },
};

const THREATS = [
  {
    id: "t1", name: "RASMLAR (18).apk",
    looksLike: "Rasm albomi", from: "Telegram", fromIcon: "message",
    time: "Bugun, 14:32", size: "1.6 MB", sev: "danger", sevLabel: "Xavfli",
    short: "Bu fayl o'zini rasm albomi qilib ko'rsatadi, lekin aslida bank ilovangizdagi pul va parolni o'g'irlamoqchi.",
    does: ["bankPass", "sms", "screen", "hide"],
    sourceWarn: "Bu APK Telegram orqali keldi. Haqiqiy ilovalar faqat Play Market'dan o'rnatiladi — rasm hech qachon .apk bo'lmaydi.",
  },
  {
    id: "t2", name: "VIDEO_20.01.2026.apk",
    looksLike: "Video fayl", from: "Sayt havolasi", fromIcon: "globe",
    time: "Bugun, 11:05", size: "2.1 MB", sev: "danger", sevLabel: "Xavfli",
    short: "Bu fayl ochilganda telefoningizga yashirincha boshqa zararli dasturlarni yuklaydi.",
    does: ["download", "remote", "screen"],
    sourceWarn: "Bu fayl noma'lum saytdan yuklab olingan. Videolar .apk shaklida bo'lmaydi.",
  },
  {
    id: "t3", name: "toy_rasmlari (9).apk",
    looksLike: "To'y rasmlari", from: "Telegram", fromIcon: "message",
    time: "Kecha, 19:40", size: "1.5 MB", sev: "danger", sevLabel: "Xavfli",
    short: "Bu fayl o'zini to'y rasmlari qilib ko'rsatadi, lekin bank kodlaringizni o'g'irlaydi.",
    does: ["bankPass", "sms", "hide"],
    sourceWarn: "Bu APK Telegram orqali keldi. Tanish odamdan kelgan bo'lsa ham — uning telefoni zararlangan bo'lishi mumkin.",
  },
];

const APPS = [
  { id: "a1", name: "RASMLAR (18).apk", from: "Telegram", size: "1.6 MB", safe: false, sev: "danger", label: "Xavfli", tId: "t1" },
  { id: "a2", name: "VIDEO_20.01.2026.apk", from: "Sayt havolasi", size: "2.1 MB", safe: false, sev: "danger", label: "Xavfli", tId: "t2" },
  { id: "a3", name: "toy_rasmlari (9).apk", from: "Telegram", size: "1.5 MB", safe: false, sev: "danger", label: "Xavfli", tId: "t3" },
  { id: "a4", name: "RASMLAR (8).apk", from: "Telegram", size: "1.4 MB", safe: false, sev: "danger", label: "Xavfli", tId: "t1" },
  { id: "a5", name: "VID_23856.apk", from: "WhatsApp", size: "1.5 MB", safe: false, sev: "danger", label: "Xavfli", tId: "t1" },
  { id: "a6", name: "Telegram", from: "Play Market", size: "73 MB", safe: true, sev: "safe", label: "Xavfsiz" },
  { id: "a7", name: "Instagram", from: "Play Market", size: "65 MB", safe: true, sev: "safe", label: "Xavfsiz" },
  { id: "a8", name: "MyTaxi", from: "Play Market", size: "42 MB", safe: true, sev: "safe", label: "Xavfsiz" },
];

const GUARDS = [
  { icon: "folder", t: "Fayllar nazorati", s: "Yangi fayllar tekshiriladi", on: true },
  { icon: "card", t: "Bank himoyasi", s: "Bank ilovalaringiz qo'riqlanadi", on: true },
  { icon: "message", t: "SMS himoyasi", s: "Soxta xabarlar bloklanadi", on: true },
  { icon: "globe", t: "Internet himoyasi", s: "Zararli saytlar to'siladi", on: true },
];

const WEEK = [
  { d: "Du", n: 32, bad: 0 }, { d: "Se", n: 41, bad: 1 }, { d: "Cho", n: 38, bad: 0 },
  { d: "Pa", n: 52, bad: 2 }, { d: "Ju", n: 47, bad: 1 }, { d: "Sh", n: 28, bad: 0 }, { d: "Ya", n: 9, bad: 1 },
];

// Karantin — o'chirilgan/saqlangan xavfli fayllar
const QUARANTINE = [
  { id: "q1", name: "RASMLAR (18).apk", from: "Telegram", time: "Bugun, 14:32" },
  { id: "q2", name: "VIDEO_20.01.2026.apk", from: "Sayt havolasi", time: "Bugun, 11:05" },
  { id: "q3", name: "toy_rasmlari (9).apk", from: "Telegram", time: "Kecha, 19:40" },
  { id: "q4", name: "RASMLAR (8).apk", from: "Telegram", time: "Kecha, 08:15" },
  { id: "q5", name: "VID_23856.apk", from: "WhatsApp", time: "2 kun oldin" },
];

// Himoya holati — ruxsatlar/sozlamalar holati
const PROT_STATUS = [
  { icon: "folder", t: "Fayllarga kirish", s: "APK fayllarni tekshirish uchun", ok: true },
  { icon: "bell", t: "Bildirishnomalar", s: "Xavf haqida ogohlantirish", ok: true },
  { icon: "alert", t: "Oynalar ustida ko'rsatish", s: "Tezkor ogohlantirish chiqarish", ok: true },
  { icon: "refresh", t: "Fonda ishlash", s: "Har 15 daqiqada tekshirish", ok: true },
  { icon: "message", t: "Bildirishnomalarni o'qish", s: "Soxta SMS'larni bloklash", ok: false },
];

// Xavfli APK so'ragan ruxsatlar — inson tilida, jargonsiz
const PERMS = {
  crit: [
    { icon: "message", t: "SMS o'qish", s: "Bankdan kelgan tasdiq kodlarini o'qiy oladi" },
    { icon: "message", t: "SMS yuborish", s: "Sizning nomingizdan pul o'tkazma so'rovi yuboradi" },
    { icon: "eye", t: "Maxsus imkoniyatlar", s: "Ekraningizni ko'radi va o'zi tugmalarni bosadi" },
    { icon: "alert", t: "Oynalar ustida ko'rsatish", s: "Bank ilovasi ustiga soxta oyna qo'yib parol so'raydi" },
  ],
  warn: [
    { icon: "phone", t: "Telefon holati", s: "Telefon raqamingizni va qurilma raqamini biladi" },
    { icon: "user", t: "Kontaktlar", s: "Telefon kitobingizdagi odamlarni o'qiydi" },
    { icon: "lock", t: "Ilova o'rnatish", s: "Yangi dasturlarni o'zi o'rnatishga urinadi" },
  ],
  normal: [
    { icon: "globe", t: "Internet", s: "Ma'lumot uzatish uchun" },
    { icon: "folder", t: "Xotira", s: "Telefondagi fayllarga kirish" },
  ],
};

Object.assign(window, { DOES, THREATS, APPS, GUARDS, WEEK, QUARANTINE, PROT_STATUS, PERMS });
