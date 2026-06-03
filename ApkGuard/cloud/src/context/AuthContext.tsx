import {
  createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode,
} from 'react';
import {
  clearSession, getSession, login as apiLogin, adminLogin as apiAdminLogin,
  setSession, setUnauthHandler, type Kind, type Session,
} from '../lib/api';

interface AuthCtx {
  ready: boolean;
  authed: boolean;
  kind: Kind | null;
  /** Egasi (owner) — master kalit bilan kiradi, to'liq huquq. */
  isOwner: boolean;
  /** Cheklangan admin — login+parol bilan kiradi: ko'rish + eksport + e'lon. */
  isAdmin: boolean;
  name: string;
  doLogin: (secret: string, otp: string) => Promise<void>;
  doAdminLogin: (login: string, password: string) => Promise<void>;
  logout: () => void;
}

const Ctx = createContext<AuthCtx>({
  ready: false, authed: false, kind: null, isOwner: false, isAdmin: false, name: '',
  doLogin: async () => {}, doAdminLogin: async () => {}, logout: () => {},
});

export const useAuth = () => useContext(Ctx);

function isExpired(s: Session | null): boolean {
  return Boolean(s?.exp && s.exp * 1000 <= Date.now());
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSess] = useState<Session | null>(null);
  const [ready, setReady] = useState(false);

  const logout = useCallback(() => {
    clearSession();
    setSess(null);
  }, []);

  // API 401 qaytarsa — avtomatik chiqish.
  useEffect(() => {
    setUnauthHandler(() => setSess(null));
    return () => setUnauthHandler(null);
  }, []);

  // Boshlanishida: saqlangan sessiyani tiklaymiz. Muddati o'tgan bo'lsa — tozalaymiz.
  // (Haqiqiy enforcement har so'rovda serverda; bu faqat lokal tezkor tekshiruv.)
  useEffect(() => {
    const s = getSession();
    if (s && !isExpired(s)) {
      setSess(s);
    } else if (s) {
      clearSession();
    }
    setReady(true);
  }, []);

  // Token muddati tugaganda — aniq o'sha vaqtda avtomatik chiqish (UI dead-token bilan
  // ortda qolmasin, keraksiz polling bo'lmasin). Allaqachon o'tgan bo'lsa — darhol.
  useEffect(() => {
    if (!session?.exp) return;
    const delay = session.exp * 1000 - Date.now();
    if (delay <= 0) { logout(); return; }
    const id = window.setTimeout(logout, delay);
    return () => window.clearTimeout(id);
  }, [session, logout]);

  const doLogin = useCallback(async (secret: string, otp: string) => {
    const r = await apiLogin(secret, otp);
    const s: Session = { token: r.token, kind: 'owner', exp: r.exp };
    setSession(s);
    setSess(s);
  }, []);

  const doAdminLogin = useCallback(async (login: string, password: string) => {
    const r = await apiAdminLogin(login, password);
    const s: Session = { token: r.token, kind: 'admin', name: r.name || login, exp: r.exp };
    setSession(s);
    setSess(s);
  }, []);

  const value = useMemo<AuthCtx>(() => {
    const isOwner = session?.kind === 'owner';
    const isAdmin = session?.kind === 'admin';
    // authed faqat sessiya bor emas, balki token muddati ham o'tmagan bo'lsa true.
    const valid = Boolean(session) && !isExpired(session);
    return {
      ready,
      authed: valid,
      kind: session?.kind ?? null,
      isOwner,
      isAdmin,
      name: session?.name ?? (isOwner ? 'Egasi' : 'Admin'),
      doLogin,
      doAdminLogin,
      logout,
    };
  }, [session, ready, doLogin, doAdminLogin, logout]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
