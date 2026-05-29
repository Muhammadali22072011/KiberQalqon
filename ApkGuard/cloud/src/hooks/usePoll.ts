import { useCallback, useEffect, useRef, useState } from 'react';
import type { ApiError } from '../lib/api';

// Real-time: berilgan funksiyani darhol va keyin har intervalMs'da chaqiradi.
// 401 (auth) xatosi yutiladi — AuthProvider sessiyani o'zi yopadi.
export function usePoll<T>(fn: () => Promise<T>, intervalMs: number) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const fnRef = useRef(fn);
  fnRef.current = fn;

  const load = useCallback(async () => {
    try {
      const d = await fnRef.current();
      setData(d);
      setError(null);
    } catch (e) {
      const err = e as ApiError;
      if (!err?.auth) setError(err?.message || 'xato');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let alive = true;
    load();
    if (intervalMs > 0) {
      const id = window.setInterval(() => { if (alive) load(); }, intervalMs);
      return () => { alive = false; window.clearInterval(id); };
    }
    return () => { alive = false; };
  }, [load, intervalMs]);

  return { data, error, loading, reload: load };
}
