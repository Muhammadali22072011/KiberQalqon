# KiberQalqon — UI/UX Design Guide

## 📱 Созданные Экраны

### ✅ 1. Dashboard (Главный экран)
**Файл:** `activity_dashboard.xml`

**Компоненты:**
- Градиентная шапка (голубой → зеленый)
- Кнопки: Язык, Настройки
- Большая карточка статуса с иконкой (80dp)
- Статус: XAVFSIZ / SHUBHALI / XAVFLI
- Время последней проверки
- Кнопка "Tezkor skanerlash" (64dp высота)
- 3 карточки статистики (Tekshirildi, Bloklandi, Oxirgi tekshiruv)
- Быстрые разделы: APK ro'yxati, Sozlamalar

**UX принципы:**
- Первые 3 секунды понятно: защищён ли телефон
- Большая кнопка CTA для быстрого сканирования
- Статистика в 3 колонки для быстрого обзора

---

### ✅ 2. Onboarding (3 экрана)
**Файлы:** `activity_onboarding.xml`, `item_onboarding.xml`

**Экраны:**
1. "Zararli APK'lardan himoya"
2. "Avtomatik tekshirish"
3. "Xavfsiz qoling"

**Компоненты:**
- ViewPager2 для свайпа
- Большая иконка (200dp)
- Заголовок (28sp, bold)
- Описание (16sp, center)
- Индикаторы точек
- Кнопки: "O'tkazib yuborish", "Keyingi", "Boshlash"

---

### ✅ 3. APK List (Список APK)
**Файлы:** `activity_apk_list.xml`, `item_apk.xml`

**Компоненты:**
- Градиентная шапка с кнопкой назад
- Фильтры (Chips): Barchasi, Xavfli, Shubhali, Xavfsiz
- RecyclerView со списком APK
- Empty State: "APK fayllar topilmadi"

**Карточка APK:**
- Иконка APK (48dp)
- Название файла (bold, 16sp)
- Размер файла (13sp)
- Путь к файлу (12sp, hint color)
- Chip статуса (если проверен)
- Кнопка "Tekshirish" (48dp)

---

### ✅ 4. Auto Scan Alert (Полноэкранное окно)
**Файл:** `activity_auto_scan.xml` (уже создан ранее)

**Компоненты:**
- Полноэкранный overlay
- Анимация сканирования (2 секунды)
- Результат с цветной карточкой:
  - 🔴 XAVFLI (красный фон)
  - 🟠 SHUBHALI (оранжевый фон)
  - 🟢 XAVFSIZ (зеленый фон)
- Кнопка "Darhol o'chirish" (68dp)
- Автоудаление через 5 секунд

---

### ✅ 5. Settings (Настройки)
**Файл:** `activity_settings_new.xml`

**Секции:**

**🛡️ Himoya:**
- Fonda himoya (Switch)
- Описание: "Har 15 daqiqada avtomatik tekshirish"

**📡 Server:**
- Serverga yuklash (Switch)
- Server manzili (TextInput)

**🚫 Bildirishnomalar:**
- Fishing bildirishnomalarini bloklash (Switch)
- Кнопка "Ruxsat berish"

**🌐 Til:**
- Radio buttons: O'zbekcha / Русский

**ℹ️ Ilova haqida:**
- Versiya: 2.0

---

## 🎨 Design System

### Цвета

**Light Theme:**
```
Primary: #00BCD4 (Голубой)
Secondary: #4CAF50 (Зеленый)
Background: #F5F9FA
Surface/Card: #FFFFFF

Status:
- Safe: #4CAF50
- Warning: #FF9800
- Danger: #F44336

Text:
- Primary: #212121
- Secondary: #757575
- Hint: #9E9E9E
```

**Dark Theme:**
```
Background: #121212
Surface: #1E1E1E
Card: #2C2C2C
Text Primary: #FFFFFF
Text Secondary: #B0B0B0
```

### Типографика

```
Display: 32-57sp, Bold
Headline: 24-32sp, Bold
Title: 14-22sp, Medium
Body: 12-16sp, Regular
Label: 11-14sp, Medium
```

### Компоненты

**Карточки:**
- Corner Radius: 16dp (обычные), 20dp (большие)
- Elevation: 4dp (обычные), 8dp (важные)
- Padding: 16-24dp

**Кнопки:**
- Primary: Height 48-68dp, Corner Radius 12-16dp
- Icon Size: 24-28dp
- Text Size: 15-17sp, Bold

**Иконки:**
- Small: 24dp
- Medium: 32dp
- Large: 48dp
- Extra Large: 80dp (статус)

**Отступы:**
- XS: 4dp
- S: 8dp
- M: 12dp
- L: 16dp
- XL: 20dp
- XXL: 24dp

---

## 🎯 UX Принципы

### 1. Первые 3 секунды
Пользователь сразу видит:
- ✅ Статус защиты (большая карточка)
- ✅ Что делать дальше (кнопка сканирования)
- ✅ Статистику (3 карточки)

### 2. Минимум текста
- Короткие заголовки
- Иконки вместо слов где возможно
- Цветовые индикаторы статуса

### 3. Одна кнопка = одно действие
- "Tekshirish" — проверить
- "O'chirish" — удалить
- "Batafsil" — подробнее

### 4. Дружелюбные ошибки
- Не паника, а помощь
- "Ruxsat kerak" вместо "Permission denied"
- Кнопка решения проблемы сразу

---

## 📦 Созданные Файлы

### Layouts (Экраны)
- ✅ `activity_dashboard.xml` — Главный экран
- ✅ `activity_onboarding.xml` — Onboarding
- ✅ `item_onboarding.xml` — Страница onboarding
- ✅ `activity_apk_list.xml` — Список APK
- ✅ `item_apk.xml` — Карточка APK
- ✅ `activity_auto_scan.xml` — Автосканирование (ранее)
- ✅ `activity_settings_new.xml` — Настройки

### Drawables (Иконки)
- ✅ `gradient_header.xml` — Градиент шапки
- ✅ `ic_shield_check.xml` — Щит с галочкой
- ✅ `ic_scan.xml` — Сканирование
- ✅ `ic_language.xml` — Язык
- ✅ `ic_settings.xml` — Настройки
- ✅ `ic_apk.xml` — APK файл
- ✅ `ic_arrow_right.xml` — Стрелка вправо
- ✅ `ic_arrow_back.xml` — Стрелка назад
- ✅ `ic_empty_box.xml` — Пустой список

### Values (Ресурсы)
- ✅ `values/colors.xml` — Цвета
- ✅ `values-uz/strings.xml` — Узбекский язык (основной)
- ✅ `DESIGN_SYSTEM.md` — Система дизайна

---

## 🚀 Следующие Шаги

### Для полной реализации нужно:

1. **Создать Activity классы:**
   - `DashboardActivity.kt`
   - `OnboardingActivity.kt`
   - `ApkListActivity.kt`
   - Обновить `SettingsActivity.kt`

2. **Добавить анимации:**
   - Переходы между экранами
   - Анимация сканирования
   - Fade in/out для карточек

3. **Реализовать темную тему:**
   - `values-night/colors.xml`
   - Переключатель темы в настройках

4. **Добавить недостающие иконки:**
   - `ic_settings.xml` (если нет)
   - Иконки для onboarding
   - Иконки статусов

5. **Тестирование:**
   - Проверить на разных размерах экранов
   - Проверить RTL layout (для арабского)
   - Accessibility тестирование

---

## 📝 Тексты (Узбекский)

Все тексты уже в `values-uz/strings.xml`:

**Dashboard:**
- "Telefon holati: XAVFSIZ"
- "Oxirgi tekshiruv: 12 daqiqa oldin"
- "Tezkor skanerlash"

**Auto Scan:**
- "Tekshirilmoqda..."
- "XAVFLI"
- "5 soniyadan keyin avtomatik o'chiriladi"
- "Darhol o'chirish"

**Settings:**
- "Fonda himoya"
- "Har 15 daqiqada avtomatik tekshirish"
- "Serverga yuklash"
- "Fishing bildirishnomalarini bloklash"

---

**🎨 Дизайн готов к реализации!**
