---
name: project-security-wave-2026-06-11
description: "Security hardening wave 2026-06-11 — ML wired, quarantine AES-CTR, VPN C2 filter live, CI/Dependabot, admin audit log; all build-verified, NOT committed yet."
metadata: 
  node_type: memory
  type: project
  originSessionId: d56e65c3-382c-4370-830b-b291c6d449ee
---

2026-06-11 — волна усиления безопасности, всё реализовано и build-verified (199 unit-тестов зелёные, assembleDebug OK, tsc clean). **Закоммичено+запушено: 705e0a0** (feat/anti-re-hardening). Апгрейд LinkScanner закоммитила параллельная сессия отдельно (85fef8c, red-team hardening). Первый прогон нового CI на GitHub не проверен (репо приватный, gh CLI нет) — глянуть Actions:

1. **MlRiskModel подключён к ApkScanner** — advisory-only (золотое правило: вердикт НЕ меняет). Поле `ScanResult.mlRisk` (-1 = не считалось), строка в details при p≥0.35, строка "AI xavf bahosi" в Telegram-отчёте. Признаки собираются из готовых выводов детекторов в scored-пути (ранние DANGER-выходы без ML).
2. **Карантин зашифрован**: .quar теперь AES-CTR (per-install ключ в prefs `kiberqalqon_quarantine/enc_key_v1`, IV = token). `QuarCrypto` (top-level в Quarantine.kt, чистый JVM) + QuarCryptoTest (6 тестов). Старые записи enc=0 восстанавливаются как раньше; fail-soft на ошибке шифрования → plain copy.
3. **VPN C2-фильтр достроен**: сервис раскомментирован в манифесте, тумблер в Settings (`rowVpnFilter`, строки kq4_set_row_vpn в uz+ru), Config.isVpnFilterEnabled (default OFF), VpnService.prepare-флоу через ActivityResultLauncher, автозапуск в App.onCreate если включён+разрешён. Домены: baked IOC + облачный фид через ThreatDb.domainFamily (с обходом поддоменов). НЕ проверен на реальном устройстве!
4. **CI**: .github/workflows/ci.yml (android: testDebugUnitTest+assembleDebug на ubuntu, JDK17; cloud: npm run typecheck) + .github/dependabot.yml (gradle/npm/pip/actions weekly). В cloud/package.json добавлен скрипт "typecheck".
5. **Журнал админ-действий**: миграция [[reference-cloud-supabase-ops]] `15_admin_audit_log.sql` (hand-run!), cloud/lib/audit.ts (fail-soft), события: login/login_fail (owner+admin), news_create/delete/pin. Просмотр ТОЛЬКО владельцу: `GET /api/stats?audit=1` (ветка, не новая функция — лимит Hobby 12 функций, их ровно 12).
6. **RUCHNYE_SHAGI_VLADELCA.md** в корне — чек-лист владельца: миграции 11–15 по порядку (12 = rate-limit логина!), redeploy Vercel, удалить тест-устройство, проверить секреты.

Отложено (нужны аккаунты владельца): FCM (Firebase project + google-services.json), Play Integrity (Play Console).

ДЕПЛОЙ/МИГРАЦИИ (позже 2026-06-11): владелец прогнал миграции 14+15 в SQL Editor (видел скрин «Success»); Vercel prod redeploy сделан мной (dpl_5EfwMe9b5D8myTn5KVsu3GV5rvvK, READY, alias live, /api/stats?public=1 = 200). Supabase-org владельца: normal135792468b@gmail.com. ВНИМАНИЕ: threat_domains пока ПУСТАЯ — фид доменов ничего не отдаёт, пока владелец не вставит IOC-домены (дал ему paste-ready INSERT с 4 C2-доменами кейс-стади). Статус миграций 11–13 не подтверждён.
