import {
  createContext, useCallback, useContext, useEffect, useState, type ReactNode,
} from 'react';
import {
  apiGet, clearToken, getToken, login as apiLogin, setToken, setUnauthHandler,
} from '../lib/api';

interface AuthCtx {
  authed: boolean;
  ready: boolean;
  doLogin: (secret: string, otp: string) => Promise<void>;
  logout: () => void;
}

const Ctx = createContext<AuthCtx>({
  authed: false, ready: false, doLogin: async () => {}, logout: () => {},
});

export const useAuth = () => useContext(Ctx);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [authed, setAuthed] = useState(false);
  const [ready, setReady] = useState(false);

  const logout = useCallback(() => {
    clearToken();
    setAuthed(false);
  }, []);

  // API 401 qaytarsa — avtomatik chiqish.
  useEffect(() => {
    setUnauthHandler(() => setAuthed(false));
    return () => setUnauthHandler(null);
  }, []);

  // Boshlanishida: token bo'lsa, uni /api/stats bilan tekshiramiz.
  useEffect(() => {
    let alive = true;
    (async () => {
      if (getToken()) {
        try {
          await apiGet('/api/stats');
          if (alive) setAuthed(true);
        } catch {
          if (alive) { clearToken(); setAuthed(false); }
        }
      }
      if (alive) setReady(true);
    })();
    return () => { alive = false; };
  }, []);

  const doLogin = useCallback(async (secret: string, otp: string) => {
    const r = await apiLogin(secret, otp);
    setToken(r.token);
    setAuthed(true);
  }, []);

  return <Ctx.Provider value={{ authed, ready, doLogin, logout }}>{children}</Ctx.Provider>;
}
