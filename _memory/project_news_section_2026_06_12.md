---
name: project-news-section-2026-06-12
description: "In-app \"Yangiliklar\" news section (variant A carousel on dashboard + NewsActivity list) implemented 2026-06-12; feeds from existing /api/news with x-device-secret."
metadata: 
  node_type: memory
  type: project
  originSessionId: 122b8a39-beb9-4466-9530-42a9146beac1
---

2026-06-12: добавлена секция новостей в APK (дизайн-вариант A, выбран пользователем из 3 мокапов):

- **Карусель на дашборде** под hero-кольцом: ViewPager2 (240dp, max 5 новостей) + точки-индикатор + «Hammasi» → NewsActivity. Секция GONE, если кэш и сеть пусты (устройство без облака не видит пустой блок).
- **NewsActivity** — полный список: pinned-карточка (primary-soft + primary stroke, значок ic4_star), теги уровней (critical→danger «Muhim», warning→warn «Ogohlantirish», info→soft «Ma'lumot»), картинки, пустое состояние.
- **NewsStore.kt** — GET /api/news c x-device-secret (API уже поддерживал устройство, ничего в облаке менять не пришлось), кэш в SharedPreferences `kiberqalqon_news`, троттлинг сети 10 мин. Без HMAC-подписи (read-only контент, TLS+pin); только https-картинки.
- **NewsImages.kt** — мини-загрузчик картинок OkHttp+BitmapFactory (≤4MB, downsample 1080px, LruCache 12MB) — Glide/Coil сознательно не добавляли.
- **NewsUi.kt** — общие хелперы (теги, даты Bugun/Kecha/N kun avval, загрузка картинок с tag-guard от recycle).
- Строки strings_kq4_news.xml (uz) + values-ru; NewsActivity в манифесте; ic4_star вместо pin-иконки (pin в наборе ic4_* нет).
- **COMMITTED+PUSHED d17449e** (ветка feat/anti-re-hardening) 2026-06-12. NOT device-tested.
- Колокольчик в топбаре по-прежнему ведёт в карантин (решили не трогать); связка с вариантами B/C (баннер critical на дашборде) — возможное продолжение.
- **NB: в проекте УЖЕ был NewsClient.kt + NewsTickerAdapter.kt** — лента новостей на вкладке «Skaner» (MainActivity), не на дашборде. Моя карусель — отдельное размещение на главном (DashboardNewActivity), не дубль. NewsClient.readCapped — эталон безопасного чтения картинок (отзеркалил в NewsImages).
- **19-агентная adversarial-ревизия → 14 находок исправлено → 4-агентная верификация (всё зелёное).** Главное: NewsImages читал ответ через body.bytes() без лимита (OOM при chunked-ответе) → переписан на readCapped(≤4MB)+callTimeout+негативный кэш. Прочее: maxLines по факту загрузки картинки, отмена прошлой джобы списка приложений (дубли строк), humanDate ceil (off-by-one в полночь), серверный https-only для image_url + лимит title(300)/body(8000), NewsStore.take(300/8000). Build+207 тестов+tsc зелёные.
- Реальная новость с картинкой опубликована в прод-панель владельцем (через панель; у Claude нет действующего ADMIN_SECRET — локальный .env.local устарел, прод вернул 401). Картинка-заготовка: Desktop/anor-yangilik-apk-ogohlantirish.png.

Related: [[project-panel-anor-wave-2026-06-12]] (панель публикует новости), [[project-anor-redesign-2026-06-10]] (дизайн v4).
