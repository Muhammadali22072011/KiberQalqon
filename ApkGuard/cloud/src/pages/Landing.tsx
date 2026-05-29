import { Link } from 'react-router-dom';

const FEATURES = [
  {
    icon: '🧬',
    title: 'Ko‘p qatlamli aniqlash',
    text: 'Dropper, SMS-o‘g‘ri, josus va bank troyanlarini 8+ analizator orqali aniqlaydi — real o‘zbek namunalari asosida tuzilgan.',
  },
  {
    icon: '🗺️',
    title: 'Jonli geo-monitoring',
    text: 'O‘zbekiston bo‘ylab himoyalangan qurilmalar va tahdidlar xaritada real vaqtda yonib turadi.',
  },
  {
    icon: '📡',
    title: 'Real vaqt oqimi',
    text: 'Har bir skan natijasi soniyalar ichida panelga tushadi — xavfli ilovalar darhol ko‘rinadi.',
  },
  {
    icon: '🔐',
    title: 'Maxfiy rol tizimi',
    text: 'Soatlik kod + login + parol. Operatorlarga aniq huquqlar beriladi, har bir kirish audit qilinadi.',
  },
  {
    icon: '🛡',
    title: 'Buzib bo‘lmas himoya',
    text: 'APK teskari muhandislik qilinsa ham master kalit ichida yo‘q. Server kalitlari brauzerga umuman chiqmaydi.',
  },
  {
    icon: '📰',
    title: 'E‘lonlar lentasi',
    text: 'Ogohlantirish va yangiliklar to‘g‘ridan-to‘g‘ri foydalanuvchining ilovasiga yetkaziladi.',
  },
];

export default function Landing() {
  return (
    <div className="landing">
      <div className="lwrap">
        <nav className="lnav">
          <div className="logo">🛡</div>
          <div className="brand"><b>KiberQalqon</b><small>Cloud · himoya markazi</small></div>
          <div className="spacer" />
          <Link to="/login" className="btn ghost">Panelga kirish</Link>
        </nav>

        <header className="lhero">
          <div className="lbadge">● O‘zbekiston · Android xavfsizlik</div>
          <h1>
            Android tahdidlarni <span className="accent">real vaqtda</span> ko‘rib turing
          </h1>
          <p>
            KiberQalqon — O‘zbekiston foydalanuvchilariga qaratilgan bank troyanlari, dropperlar va
            josus ilovalarni aniqlaydigan, ularni xaritada va jonli oqimda kuzatib boruvchi himoya tizimi.
          </p>
          <div className="lcta">
            <Link to="/login" className="btn lg">Boshqaruv paneli →</Link>
            <a className="btn ghost lg" href="#feat">Imkoniyatlar</a>
          </div>
        </header>

        <section className="lfeatures" id="feat">
          {FEATURES.map((f) => (
            <div className="lfeat" key={f.title}>
              <div className="lf-ico">{f.icon}</div>
              <h3>{f.title}</h3>
              <p>{f.text}</p>
            </div>
          ))}
        </section>

        <section className="lstats">
          <div className="lstat"><b>8+</b><span>aniqlash qatlami</span></div>
          <div className="lstat"><b>24/7</b><span>real vaqt kuzatuvi</span></div>
          <div className="lstat"><b>0</b><span>kalit APK ichida</span></div>
          <div className="lstat"><b>100%</b><span>o‘zbek tilida</span></div>
        </section>

        <footer className="lfoot">© 2026 · KiberQalqon · Muhammadali</footer>
      </div>
    </div>
  );
}
