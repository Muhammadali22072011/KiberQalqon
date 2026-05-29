import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react';

interface ToastCtx { show: (msg: string) => void; }
const Ctx = createContext<ToastCtx>({ show: () => {} });

export const useToast = () => useContext(Ctx);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [msg, setMsg] = useState('');
  const [on, setOn] = useState(false);
  const timer = useRef<number | undefined>(undefined);

  const show = useCallback((m: string) => {
    setMsg(m);
    setOn(true);
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setOn(false), 3200);
  }, []);

  return (
    <Ctx.Provider value={{ show }}>
      {children}
      <div className={'toast' + (on ? ' show' : '')} role="status" aria-live="polite">{msg}</div>
    </Ctx.Provider>
  );
}
