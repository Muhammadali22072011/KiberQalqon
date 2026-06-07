# FCM push-уведомления — готовый патч (требует твоего Firebase-проекта)

**Статус:** код готов ниже, но НЕ применён в сборке. Причина: FCM требует файла
`google-services.json` из Firebase Console. Если добавить плагин/зависимость без него — сборка
упадёт у любого, кто собирает без этого файла. Поэтому ниже — пошагово: сначала создаёшь проект
Firebase, кладёшь `google-services.json`, потом применяешь патч. Сборка остаётся зелёной, потому что
плагин применяется **только если файл существует**.

> Зачем вообще: владелец уже получает алерты в Telegram (через `api/scan/upload` →
> `ADMIN_CHAT_IDS`). FCM добавляет **нативный push на телефон** (работает, когда Telegram не
> открыт/не настроен). Это дополнение, а не замена.

---

## Шаг 1. Firebase Console (один раз)
1. https://console.firebase.google.com → **Add project** → имя любое.
2. **Add app → Android**. Package name: `com.kiberqalqon`. Добавь **второй** Android-app с
   `com.kiberqalqon.debug` (debug-вариант ставится рядом).
3. Скачай `google-services.json` (он содержит ОБА app-id) → положи в `ApkGuard/app/google-services.json`.
   Файл уже в `.gitignore`-логике секретов — не коммить его (содержит project/sender id).
4. В **Project Settings → Cloud Messaging** включи **Firebase Cloud Messaging API (V1)**.

## Шаг 2. `app/build.gradle.kts`
```kotlin
// plugins { } блокида — google-services плагин ПРИМЕНЯЕМ УСЛОВНО (build остаётся зелёным без json):
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // НЕ добавляй google-services сюда статически.
}
// ...в самом низу файла:
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

dependencies {
    // ...существующие...
    implementation("com.google.firebase:firebase-messaging:24.0.0")
}
```
И в **корневой** `ApkGuard/build.gradle.kts` (или settings) добавь classpath плагина:
```kotlin
// build.gradle.kts (project) → plugins { } или buildscript:
// id("com.google.gms.google-services") version "4.4.2" apply false
```

## Шаг 3. `AndroidManifest.xml`
Внутри `<application>`:
```xml
<service
    android:name=".FirebasePushService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

## Шаг 4. `app/src/main/java/com/kiberqalqon/FirebasePushService.kt`
```kotlin
package com.kiberqalqon

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM push. Owner телефонга tahdid alert + (ixtiyoriy) C2/blacklist yangilanish signali.
 * onNewToken → tokenni bulutga yuboramiz (CloudTelemetry orqali yangi endpoint).
 */
class FirebasePushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Tokenni saqlaymiz; keyingi CloudTelemetry POST bilan serverga boradi.
        getSharedPreferences("kiberqalqon_fcm", MODE_PRIVATE)
            .edit().putString("fcm_token", token).apply()
        try { CloudTelemetry.sendFcmToken(this, token) } catch (_: Throwable) {}
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val data = msg.data
        when (data["type"]) {
            // Markaz blacklist yangilandi → darhol qayta yuklab olamiz.
            "blacklist" -> try { CloudBlacklist.refresh(applicationContext) } catch (_: Throwable) {}
            // Tahdid alert → mahalliy bildirishnoma.
            else -> {
                val title = msg.notification?.title ?: data["title"] ?: "KiberQalqon"
                val body = msg.notification?.body ?: data["body"] ?: ""
                try { NotificationHelper.showSimpleAlert(applicationContext, title, body) } catch (e: Throwable) {
                    Log.w("FCM", "alert failed", e)
                }
            }
        }
    }
}
```
> Нужно добавить `CloudTelemetry.sendFcmToken(ctx, token)` (POST `{device_token, fcm_token}` на новый
> `/api/device/fcm`) и `NotificationHelper.showSimpleAlert(ctx, title, body)` — обе тривиальные,
> по образцу существующих методов.

## Шаг 5. Cloud (отправка push)
- Таблица `devices`: добавь колонку `fcm_token text`.
- Новый эндпоинт-ветка в `api/device/[id].ts` (или `scan/upload.ts`): принять `fcm_token`, сохранить.
- В `api/scan/upload.ts` рядом с Telegram-алертом: если у owner-устройств есть `fcm_token`, послать
  FCM V1 push (через `googleapis`/REST с service-account ключом в `FCM_SERVICE_ACCOUNT` env).
  Это **серверный** ключ — только в Vercel env, НИКОГДА в APK.

## Проверка
`./gradlew.bat assembleDebug` — без `google-services.json` собирается как раньше (плагин не
применяется, `firebase-messaging` просто лежит как либа). С `google-services.json` — push работает.
```
