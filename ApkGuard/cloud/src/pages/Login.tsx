import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

export default function Login() {
  const { doLogin } = useAuth();
  const nav = useNavigate();
  const loc = useLocation();
  const from = (loc.state as { from?: string } | null)?.from || '/app';

  const [secret, setSecret] = useState('');
  const [otp, setOtp] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setErr('');
    setBusy(true);
    try {
      await doLogin(secret.trim(), otp.trim());
      nav(from, { replace: true });
    } catch (ex) {
      setErr((ex as Error).message || 'Kirish amalga oshmadi');
      setBusy(false);
    }
  };

  return (
    <div className="gate">
      <form className="gate-card" onSubmit={submit}>
        <div className="logo" style={{ margin: '0 auto' }}>🛡</div>
        <div className="gate-sub">KiberQalqon · maxfiy panel</div>
        <h1 className="gate-title">Boshqaruvga kirish</h1>

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

        <div className="gate-err">{err}</div>
        <button className="btn block" disabled={busy || !secret}>
          {busy ? <span className="spinner" /> : 'Kirish'}
        </button>

        <p className="gate-hint">
          Kalit faqat egada saqlanadi va brauzerga yozilmaydi. Har bir urinish qayd etiladi.
        </p>
        <Link to="/" className="gate-back">← Bosh sahifaga</Link>
      </form>
    </div>
  );
}
