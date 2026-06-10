import { useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

type Mode = 'owner' | 'admin';

export default function Login() {
  const { doLogin, doAdminLogin, authed } = useAuth();
  const nav = useNavigate();
  const loc = useLocation();
  const from = (loc.state as { from?: string } | null)?.from || '/app';

  const [mode, setMode] = useState<Mode>('owner');
  // Sessiya muddati tugab tashlangan bo'lsa — jim emas, sababini ko'rsatamiz (bir marta).
  const [info] = useState<string>(() => {
    try {
      if (sessionStorage.getItem('kq_logout_reason') === 'expired') {
        sessionStorage.removeItem('kq_logout_reason');
        return 'Sessiya muddati tugadi — qaytadan kiring.';
      }
    } catch { /* ignore */ }
    return '';
  });
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);

  // Allaqachon kirgan bo'lsa /login bo'sh forma ko'rsatmaydi — panelga yuboradi.
  if (authed) return <Navigate to={from} replace />;

  // egasi
  const [secret, setSecret] = useState('');
  const [otp, setOtp] = useState('');
  // admin
  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');

  const switchMode = (m: Mode) => { setMode(m); setErr(''); };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setErr('');
    setBusy(true);
    try {
      if (mode === 'owner') {
        await doLogin(secret.trim(), otp.trim());
      } else {
        await doAdminLogin(login.trim(), password);
      }
      nav(from, { replace: true });
    } catch (ex) {
      setErr((ex as Error).message || 'Kirish amalga oshmadi');
      setBusy(false);
    }
  };

  const canSubmit = mode === 'owner'
    ? Boolean(secret)
    : Boolean(login && password);

  return (
    <div className="gate">
      <form className="gate-card" onSubmit={submit}>
        <div className="logo" style={{ margin: '0 auto' }}>🛡</div>
        <div className="gate-sub">KiberQalqon · maxfiy panel</div>
        <h1 className="gate-title">Boshqaruvga kirish</h1>

        <div className="gate-tabs" role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={mode === 'owner'}
            className={'gate-tab' + (mode === 'owner' ? ' active' : '')}
            onClick={() => switchMode('owner')}
          >
            👑 Egasi
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={mode === 'admin'}
            className={'gate-tab' + (mode === 'admin' ? ' active' : '')}
            onClick={() => switchMode('admin')}
          >
            🧑‍💼 Admin
          </button>
        </div>

        {mode === 'owner' ? (
          <>
            <div className="field">
              <input
                type="password"
                placeholder="Maxfiy kalit"
                value={secret}
                onChange={(e) => setSecret(e.target.value)}
                autoFocus
                autoComplete="off"
              />
            </div>
            <div className="field">
              <input
                inputMode="numeric"
                placeholder="2FA kod (yoqilgan bo'lsa)"
                value={otp}
                onChange={(e) => setOtp(e.target.value.replace(/\D/g, ''))}
                autoComplete="off"
                maxLength={6}
              />
            </div>
          </>
        ) : (
          <>
            <div className="field">
              <input
                placeholder="Login"
                value={login}
                onChange={(e) => setLogin(e.target.value)}
                autoFocus
                autoComplete="username"
              />
            </div>
            <div className="field">
              <input
                type="password"
                placeholder="Parol"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
              />
            </div>
          </>
        )}

        {info && !err && <div className="gate-info">{info}</div>}
        <div className="gate-err">{err}</div>
        <button className="btn block" disabled={busy || !canSubmit}>
          {busy ? <span className="spinner" /> : 'Kirish'}
        </button>

        <p className="gate-hint">
          {mode === 'owner'
            ? 'Kalit faqat egada saqlanadi va brauzerga yozilmaydi. Har bir urinish qayd etiladi.'
            : 'Login va parol — egasi bergan admin hisobi. Admin faqat ko‘radi, eksport qiladi va e‘lon joylaydi.'}
        </p>
        <Link to="/" className="gate-back">← Bosh sahifaga</Link>
      </form>
    </div>
  );
}
