═══════════════════════════════════════════════════════════════
  РАСПАКОВКА И РЕВЕРС APK
═══════════════════════════════════════════════════════════════

■ ШАГ 1 — РАСПАКОВАТЬ
  Дважды нажми:  raspakovka_i_revers.bat

  Появится папка  apk_unpacked  и файл  unpacked_files.txt  (список файлов).

  Что внутри APK (после распаковки):
    • AndroidManifest.xml   — манифест (бинарный, нужен apktool для чтения)
    • classes.dex           — код приложения (нужен jadx для реверса в Java)
    • res/                  — ресурсы (картинки, строки, разметка)
    • META-INF/             — подписи
    • resources.arsc        — скомпилированные ресурсы

■ ШАГ 2 — РЕВЕРС (читаемый манифест и код)
  На ПК вирус не опасен — можно спокойно изучать.

  1) Читаемый манифест и smali:
     apktool d "⬅️TAKLIFNOMA TOY AVGUST 1325478967.apk" -o apk_decoded
     Тогда в apk_decoded будет AndroidManifest.xml в виде текста + папка smali с кодом.

  2) Код в виде Java (удобно искать логику):
     jadx-gui "⬅️TAKLIFNOMA TOY AVGUST 1325478967.apk"
     Или: jadx -d apk_jadx "⬅️TAKLIFNOMA TOY AVGUST 1325478967.apk"

  Скачать: apktool — apktool.org, jadx — github.com/skylot/jadx

■ ИТОГО
  • Распаковать:  raspakovka_i_revers.bat   → папка apk_unpacked
  • Полный реверс: apktool + jadx по командам выше
