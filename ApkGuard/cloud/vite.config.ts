import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// React SPA. /api/*.ts serverless funksiyalarga tegmaydi — Vercel ularni alohida quradi.
// Natija dist/ ga chiqadi; Vercel uni statik root sifatida beradi (SPA fallback vercel.json'da).
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist',
    target: 'es2020',
    sourcemap: false,
    // modulePreload polyfill index.html'ga inline <script> qo'shadi — qat'iy CSP buzmasligi uchun o'chirildi.
    modulePreload: { polyfill: false },
  },
  server: { port: 5173 },
});
