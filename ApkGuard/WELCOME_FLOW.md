# Welcome Flow — Поток входа в приложение

## 📱 Созданные экраны

### 1. Welcome Screen (Экран входа)
**Файл:** `activity_welcome.xml`

**Дизайн:**
- Полноэкранный градиентный фон (голубой → зеленый)
- Логотип KiberQalqon (120dp)
- Название приложения (36sp, bold, белый)
- Слоган: "Telefoningizni himoya qiling"
- Белая карточка с формой (24dp radius, 12dp elevation)

**Форма:**
- Заголовок: "Xush kelibsiz! 👋"
- Поле "Ismingiz" (имя) с иконкой person
- Поле "Familiyangiz" (фамилия) с иконкой person
- Чекбокс согласия с условиями
- Ссылки: "Foydalanish shartlari" • "Maxfiylik siyosati"
- Кнопка "Davom etish" (64dp, disabled пока не заполнено)
- Ссылка "ℹ️ Loyiha haqida"
- Версия внизу

**UX логика:**
1. Пользователь вводит имя и фамилию
2. Ставит галочку согласия
3. Кнопка "Davom etish" становится активной
4. После нажатия → переход к Onboarding или Dashboard

---

### 2. Terms of Use (Условия использования)
**Файл:** `activity_terms.xml`

**Содержание:**
1. **Kirish** — введение
2. **Ilova maqsadi** — цель приложения
3. **Foydalanuvchi majburiyatlari** — обязанности пользователя
4. **Cheklovlar va javobgarlik** — ограничения и ответственность
5. **Kerakli ruxsatlar** — необходимые разрешения
6. **Shartlardagi o'zgarishlar** — изменения в условиях
7. **Aloqa** — контакты

**Дизайн:**
- Градиентная шапка с кнопкой назад
- Белая карточка с текстом
- Разделы с голубыми заголовками
- Кнопка "✓ Qabul qilaman" внизу

---

### 3. Privacy Policy (Политика конфиденциальности)
**Файл:** `activity_privacy.xml`

**Содержание:**
1. **Yig'iladigan ma'lumotlar** — собираемые данные
   - ✅ Что собирается
   - ❌ Что НЕ собирается
2. **Ma'lumotlardan foydalanish** — использование данных
3. **Ma'lumotlar saqlash** — хранение данных
4. **Ruxsatlar va ularning maqsadi** — разрешения и их цель
5. **Xavfsizlik choralari** — меры безопасности
6. **Sizning huquqlaringiz** — ваши права
7. **Bolalar maxfiyligi** — конфиденциальность детей
8. **Siyosatdagi o'zgarishlar** — изменения в политике
9. **Aloqa** — контакты

**Дизайн:**
- Градиентная шапка
- Белая карточка с иконкой 🔒
- Подробное описание
- Кнопка "✓ Tushundim" внизу (зеленая)

---

### 4. About Project (О проекте)
**Файл:** `activity_about.xml`

**Содержание:**
- **Логотип и версия** — в градиентной карточке
- **📱 Loyiha haqida** — описание проекта
- **✨ Asosiy imkoniyatlar** — основные возможности
- **🛠️ Texnologiyalar** — используемые технологии
- **👨‍💻 Muallif** — автор (Muhammadali)
- **📄 Litsenziya** — лицензия

**Дизайн:**
- Градиентная карточка с логотипом вверху
- Несколько белых карточек с разделами
- Каждая карточка с иконкой-эмодзи

---

## 🔄 Поток входа (Flow)

```
1. Splash Screen (если есть)
   ↓
2. Welcome Screen
   - Ввод имени и фамилии
   - Согласие с условиями
   ↓
3. Onboarding (3 экрана)
   - Знакомство с функциями
   ↓
4. Dashboard
   - Главный экран приложения
```

### Альтернативные переходы:

**Из Welcome Screen:**
- Клик на "Foydalanish shartlari" → Terms Activity
- Клик на "Maxfiylik siyosati" → Privacy Activity
- Клик на "Loyiha haqida" → About Activity

**Из любого экрана:**
- Кнопка "Назад" → возврат к Welcome Screen

---

## 💾 Сохранение данных

После заполнения Welcome Screen сохраняется:

```kotlin
SharedPreferences:
- "user_first_name" → String (имя)
- "user_last_name" → String (фамилия)
- "terms_accepted" → Boolean (согласие)
- "onboarding_completed" → Boolean (прошел onboarding)
```

При следующем запуске:
- Если `terms_accepted == true` → сразу Dashboard
- Если `false` → Welcome Screen

---

## 🎨 Дизайн особенности

### Welcome Screen:
- **Градиент:** голубой (#00BCD4) → зеленый (#4CAF50)
- **Карточка:** белая, 24dp radius, 28dp padding
- **Поля ввода:** 12dp radius, иконка person
- **Кнопка:** 64dp высота, 16dp radius, disabled state

### Terms & Privacy:
- **Заголовки разделов:** голубой цвет, 18sp, bold
- **Текст:** 15sp, 4dp line spacing
- **Списки:** с bullet points (•)
- **Кнопка принятия:** 56dp высота

### About:
- **Логотип карточка:** градиентный фон, 100dp иконка
- **Секции:** отдельные белые карточки
- **Иконки:** эмодзи для каждой секции

---

## 📝 Тексты (Узбекский)

Все тексты в `values-uz/strings.xml`:

**Welcome:**
- "Xush kelibsiz! 👋"
- "Davom etish uchun ma'lumotlaringizni kiriting"
- "Ismingiz" / "Familiyangiz"
- "Men shartlar va maxfiylik siyosati bilan tanishdim"

**Terms:**
- "Foydalanish shartlari"
- "Qabul qilaman"

**Privacy:**
- "Maxfiylik siyosati"
- "Tushundim"

**About:**
- "Loyiha haqida"
- "Asosiy imkoniyatlar"
- "Texnologiyalar"

---

## ✅ Готовые файлы

### Layouts:
- ✅ `activity_welcome.xml` — Экран входа
- ✅ `activity_terms.xml` — Условия использования
- ✅ `activity_privacy.xml` — Политика конфиденциальности
- ✅ `activity_about.xml` — О проекте

### Drawables:
- ✅ `ic_person.xml` — Иконка человека

### Strings:
- ✅ Все тексты в `values-uz/strings.xml`

---

## 🚀 Следующие шаги

Для полной реализации нужно создать Activity классы:

1. **WelcomeActivity.kt:**
   - Валидация полей (имя, фамилия)
   - Проверка чекбокса
   - Сохранение в SharedPreferences
   - Переход к Onboarding

2. **TermsActivity.kt:**
   - Отображение условий
   - Кнопка "Qabul qilaman"

3. **PrivacyActivity.kt:**
   - Отображение политики
   - Кнопка "Tushundim"

4. **AboutActivity.kt:**
   - Отображение информации о проекте

---

**🎉 Welcome Flow готов!**

Красивый, современный дизайн с полной информацией о проекте, условиях и конфиденциальности на узбекском языке!
