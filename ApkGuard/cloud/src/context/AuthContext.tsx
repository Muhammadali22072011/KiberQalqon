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
    return {
      ready,
      authed: Boolean(session),
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
