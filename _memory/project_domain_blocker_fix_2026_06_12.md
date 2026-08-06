---
name: project-domain-blocker-fix-2026-06-12
description: "User report 'added domain in panel but it still opens' — 3 root causes found+fixed 2026-06-12: feed only on cold start, exact-match-only cloud domains in link checker, panel 'O'rta' severity never distributed."
metadata: 
  node_type: memory
  type: project
  originSessionId: 122b8a39-beb9-4466-9530-42a9146beac1
---

2026-06-12, баг-репорт владельца: домен добавлен в панели (Threats → domen qora ro'yxati), но на телефоне открывается. Три причины, все исправлены:

1. **Свежесть фида**: телефон забирал blacklist-фид ТОЛЬКО при холодном старте (App.onCreate → CloudBlacklist.refresh). Фикс: GuardWorker (каждые 15 мин) теперь зовёт новый `CloudBlacklist.refreshIfStale(ctx, 30 мин)` ДО проверки isBackgroundEnabled (щит ссылок работает и при выключенном фоне). Итог: домен доезжает ≤30 мин без перезапуска приложения.
2. **Поддомены**: облачные домены в MaliciousDomains матчились только точно (`www.evil.com` проскакивал, если добавлен `evil.com`) — VPN-фильтр умел suffix-walk, link checker нет. Фикс: тот же walk (отбрасывание меток до eTLD, голый TLD не запрашивается) + юнит-тест MaliciousDomainsCloudTest. ВАЖНО: добавлен PUBLIC_SUFFIXES-гард (30 зон: co.uz/com.uz/netlify.app/github.io/vercel.app/telegra.ph/…) в MaliciousDomains+VpnFilterService+threats.ts — иначе один ошибочный zone-entry блокировал бы всю зону (*.netlify.app → DANGER). Клиент- и сервер-сеты должны совпадать (сейчас byte-identical, теста на parity нет — следить при правках).
3. **Панель «O'rta»**: select severity имел medium, а фид (/api/threats?feed=1) раздаёт ТОЛЬКО high/critical → домен с «O'rta» «блокировался» лишь на словах. Фикс: опция удалена (остались Kritik/Yuqori). Если юзер ранее добавил домен как O'rta — нужно пере-добавить (upsert перепишет severity).

**Статус: COMMITTED+PUSHED d17449e (ветка feat/anti-re-hardening) + панель задеплоена в прод 2026-06-12** (вместе с фичей новостей — один коммит). Также исправлены (в том же ревью): refreshIfStale throttle по попытке (не по успеху) — на лежащем сервере не долбит сеть каждые 15 мин; GuardWorker realtime-путь (KEY_APK_PATHS) пропускает сетевой refresh (скан остаётся offline); CancellationException пробрасывается. Build+207 тестов+tsc зелёные, 4-агентная верификация чистая. На реальном устройстве НЕ проверено (эмулятор/устройство в этой сессии недоступны — adb пуст).

ВАЖНО объяснять юзеру где вообще блокируется домен: (а) ручная проверка ссылки, (б) Havola qalqoni если app = default link handler (Telegram in-app browser мимо), (в) VPN-фильтр (DNS sinkhole; мимо — DoH/private DNS и уже закэшированный DNS). Просто открытие в Chrome без (б)/(в) НЕ блокируется — это не баг.

Сопутствующее: диск C: был заполнен на 100% (деплой падал ENOSPC) — с разрешения юзера удалены npm-cache (0.66GB), ~/.gradle/caches (1.65GB), ApkGuard/app/build (0.18GB), Temp (~1GB). Кэш Gradle качается заново при первой сборке. Диск хронически почти полон — учитывать.

Related: [[project-panel-anor-wave-2026-06-12]] (domain manager в панели), [[project-feature-wave-2026-06-11]] (link checker + домен-фид), [[project-security-wave-2026-06-11]] (VPN C2-фильтр).
