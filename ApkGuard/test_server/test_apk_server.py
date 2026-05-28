# -*- coding: utf-8 -*-
"""
Тестовый сервер для скачивания APK файлов
Для тестирования KiberQalqon на эмуляторе
Запуск: python test_apk_server.py
"""
import os
from flask import Flask, send_file, render_template_string

app = Flask(__name__)

# HTML страница со списком тестовых APK
HTML_TEMPLATE = """
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Тестовые APK для проверки KiberQalqon</title>
    <style>
        body {
            font-family: Arial, sans-serif;
            max-width: 800px;
            margin: 50px auto;
            padding: 20px;
            background: #f5f5f5;
        }
        h1 {
            color: #d32f2f;
            text-align: center;
        }
        .warning {
            background: #fff3cd;
            border: 2px solid #ffc107;
            padding: 15px;
            border-radius: 8px;
            margin: 20px 0;
        }
        .apk-list {
            background: white;
            padding: 20px;
            border-radius: 8px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        }
        .apk-item {
            padding: 15px;
            margin: 10px 0;
            border: 1px solid #ddd;
            border-radius: 5px;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        .apk-item.dangerous {
            border-left: 4px solid #d32f2f;
            background: #ffebee;
        }
        .apk-item.suspicious {
            border-left: 4px solid #ff9800;
            background: #fff3e0;
        }
        .apk-item.safe {
            border-left: 4px solid #4caf50;
            background: #e8f5e9;
        }
        .download-btn {
            background: #2196f3;
            color: white;
            padding: 10px 20px;
            text-decoration: none;
            border-radius: 5px;
            font-weight: bold;
        }
        .download-btn:hover {
            background: #1976d2;
        }
        .info {
            background: #e3f2fd;
            padding: 15px;
            border-radius: 8px;
            margin: 20px 0;
        }
    </style>
</head>
<body>
    <h1>🛡️ Тестовые APK для KiberQalqon</h1>
    
    <div class="warning">
        <strong>⚠️ ВНИМАНИЕ:</strong> Это тестовые файлы для проверки работы приложения KiberQalqon.
        Используйте только на эмуляторе или тестовом устройстве!
    </div>

    <div class="info">
        <strong>📱 Как тестировать:</strong>
        <ol>
            <li>Откройте этот сайт в браузере эмулятора Android</li>
            <li>Скачайте тестовый APK</li>
            <li>KiberQalqon должен обнаружить и заблокировать опасные файлы</li>
            <li>Проверьте уведомления и логи в приложении</li>
        </ol>
    </div>

    <div class="apk-list">
        <h2>Доступные тестовые APK:</h2>
        
        <div class="apk-item dangerous">
            <div>
                <strong>🔴 dangerous_malware.apk</strong>
                <p>Имитация опасного вредоносного ПО (тестовый файл)</p>
            </div>
            <a href="/download/dangerous_malware.apk" class="download-btn">Скачать</a>
        </div>

        <div class="apk-item suspicious">
            <strong>🟠 suspicious_app.apk</strong>
            <p>Подозрительное приложение с необычными разрешениями</p>
            <a href="/download/suspicious_app.apk" class="download-btn">Скачать</a>
        </div>

        <div class="apk-item dangerous">
            <strong>🔴 fake_bank.apk</strong>
            <p>Фейковое банковское приложение (фишинг)</p>
            <a href="/download/fake_bank.apk" class="download-btn">Скачать</a>
        </div>

        <div class="apk-item suspicious">
            <strong>🟠 spyware_test.apk</strong>
            <p>Тестовое шпионское ПО</p>
            <a href="/download/spyware_test.apk" class="download-btn">Скачать</a>
        </div>

        <div class="apk-item safe">
            <strong>🟢 safe_app.apk</strong>
            <p>Безопасное приложение (для проверки ложных срабатываний)</p>
            <a href="/download/safe_app.apk" class="download-btn">Скачать</a>
        </div>
    </div>

    <div class="info" style="margin-top: 30px;">
        <strong>💡 Адрес сервера:</strong> {{ server_url }}<br>
        <strong>📡 Для эмулятора используйте:</strong> http://10.0.2.2:8000
    </div>
</body>
</html>
"""

@app.route('/')
def index():
    return render_template_string(HTML_TEMPLATE, server_url=f"http://localhost:8000")

@app.route('/download/<filename>')
def download(filename):
    """Скачивание тестового APK"""
    test_dir = os.path.join(os.path.dirname(__file__), 'test_apk_files')
    filepath = os.path.join(test_dir, filename)
    
    if not os.path.exists(filepath):
        return f"Файл {filename} не найден. Запустите create_test_apk.py для создания тестовых файлов.", 404
    
    return send_file(filepath, as_attachment=True, download_name=filename)

if __name__ == '__main__':
    print("=" * 60)
    print("  ТЕСТОВЫЙ СЕРВЕР APK ДЛЯ KIBERQALQON")
    print("=" * 60)
    print()
    print("Сервер запущен на:")
    print("  - Локально: http://localhost:8000")
    print("  - Для эмулятора: http://10.0.2.2:8000")
    print()
    print("Откройте браузер на эмуляторе и перейдите по адресу выше")
    print()
    print("Для остановки нажмите Ctrl+C")
    print("=" * 60)
    print()
    
    # Default 127.0.0.1 — faqat lokal mashina (emulyator localhost orqali ulanadi).
    # LAN'ga ochish kerak bo'lsa env: KIBERQALQON_TEST_BIND=0.0.0.0
    bind = os.environ.get("KIBERQALQON_TEST_BIND", "127.0.0.1")
    app.run(host=bind, port=8000, debug=False)
