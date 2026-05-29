import { createClient, SupabaseClient } from '@supabase/supabase-js';
import WebSocket from 'ws';

let cached: SupabaseClient | null = null;

export function db(): SupabaseClient {
  if (cached) return cached;
  const url = process.env.SUPABASE_URL;
  const key = process.env.SUPABASE_SERVICE_KEY;
  if (!url || !key) throw new Error('SUPABASE_URL / SUPABASE_SERVICE_KEY not set');
  cached = createClient(url, key, {
    auth: { persistSession: false, autoRefreshToken: false },
    // Vercel runtime'i Node 20 — unda global WebSocket yo'q. supabase-js realtime
    // mijozini createClient ichida darhol quradi va WebSocket topolmay yiqiladi
    // ("Node.js 20 detected without native WebSocket support"). 'ws' ni transport
    // sifatida beramiz (realtime'dan foydalanmasak ham, klient qurilishi uchun kerak).
    realtime: { transport: WebSocket as unknown as never },
  });
  return cached;
}
