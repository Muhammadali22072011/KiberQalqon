# -*- coding: utf-8 -*-
"""
Создание тестовых APK файлов для проверки KiberQalqon
Это просто копии вашего app-debug.apk с разными именами
"""
import os
import shutil

def create_test_files():
    # Путь к вашему собранному APK
    source_apk = os.path.join(os.path.dirname(__file__), '..', 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
    
    # Папка для тестовых файлов
    test_dir = os.path.join(os.path.dirname(__file__), 'test_apk_files')
    os.makedirs(test_dir, exist_ok=True)
    
    # Проверяем наличие исходного APK
    if not os.path.exists(source_apk):
        print("❌ Файл app-debug.apk не найден!")
        print(f"   Ожидается: {source_apk}")
        print()
        print("Сначала соберите проект: запустите ZAPUSK_S_JAVA21.bat")
        return False
    
    # Список тестовых файлов
    test_files = [
        'dangerous_malware.apk',
        'suspicious_app.apk',
        'fake_bank.apk',
        'spyware_test.apk',
        'safe_app.apk'
    ]
    
    print("=" * 60)
    print("  СОЗДАНИЕ ТЕСТОВЫХ APK ФАЙЛОВ")
    print("=" * 60)
    print()
    
    for filename in test_files:
        dest = os.path.join(test_dir, filename)
        shutil.copy2(source_apk, dest)
        size = os.path.getsize(dest) / (1024 * 1024)
        print(f"✓ Создан: {filename} ({size:.2f} MB)")
    
    print()
    print("=" * 60)
    print("✓ Все тестовые файлы созданы!")
    print("=" * 60)
    print()
    print("Теперь запустите: python test_apk_server.py")
    print()
    return True

if __name__ == '__main__':
    create_test_files()
