# KiberQalqon — Design System

## 🎨 Цветовая палитра

### Light Theme
```
Primary: #00BCD4 (Голубой)
Primary Variant: #0097A7
Secondary: #4CAF50 (Зеленый)
Secondary Variant: #388E3C

Background: #F5F9FA
Surface: #FFFFFF
Card: #FFFFFF

Status Colors:
- Safe: #4CAF50 (Зеленый)
- Warning: #FF9800 (Оранжевый)
- Danger: #F44336 (Красный)

Status Backgrounds:
- Safe BG: #E8F5E9
- Warning BG: #FFF3E0
- Danger BG: #FFEBEE

Text:
- Primary: #212121
- Secondary: #757575
- Hint: #9E9E9E
- On Primary: #FFFFFF
```

### Dark Theme
```
Primary: #00BCD4
Primary Variant: #00ACC1
Secondary: #4CAF50
Secondary Variant: #66BB6A

Background: #121212
Surface: #1E1E1E
Card: #2C2C2C

Status Colors: (same)

Text:
- Primary: #FFFFFF
- Secondary: #B0B0B0
- Hint: #808080
- On Primary: #000000
```

## 📐 Типографика

```
Display Large: 57sp, Bold
Display Medium: 45sp, Bold
Display Small: 36sp, Bold

Headline Large: 32sp, Bold
Headline Medium: 28sp, Bold
Headline Small: 24sp, Bold

Title Large: 22sp, Medium
Title Medium: 16sp, Medium
Title Small: 14sp, Medium

Body Large: 16sp, Regular
Body Medium: 14sp, Regular
Body Small: 12sp, Regular

Label Large: 14sp, Medium
Label Medium: 12sp, Medium
Label Small: 11sp, Medium
```

## 🔲 Компоненты

### Карточки (Cards)
- Corner Radius: 16dp (обычные), 20dp (большие)
- Elevation: 4dp (light), 8dp (hover/focus)
- Padding: 16dp (маленькие), 20dp (средние), 24dp (большие)

### Кнопки
- Primary: Filled, Corner Radius 12dp, Height 48dp
- Secondary: Outlined, Corner Radius 12dp, Height 48dp
- Text: No background, Height 40dp
- Icon: 48x48dp, Corner Radius 24dp

### Иконки
- Small: 20dp
- Medium: 24dp
- Large: 32dp
- Extra Large: 48dp

### Отступы
- XS: 4dp
- S: 8dp
- M: 12dp
- L: 16dp
- XL: 20dp
- XXL: 24dp
- XXXL: 32dp

## 🌊 Градиенты

### Header Gradient
```xml
Start: #00BCD4 (Голубой)
End: #4CAF50 (Зеленый)
Angle: 135°
```

### Status Gradients
```xml
Safe: #4CAF50 → #66BB6A
Warning: #FF9800 → #FFB74D
Danger: #F44336 → #EF5350
```

## 🎭 Анимации

- Duration Short: 150ms
- Duration Medium: 300ms
- Duration Long: 500ms
- Easing: Cubic Bezier (0.4, 0.0, 0.2, 1)

## 📱 Экраны

1. Splash Screen
2. Onboarding (3 экрана)
3. Dashboard (Главный)
4. APK List (Список)
5. Auto Scan Alert (Полноэкранное)
6. Scan Result (Результат)
7. Settings (Настройки)
8. Statistics (Статистика)
