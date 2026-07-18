# ============================================================
# KiberQalqon — правила обфускации R8/ProGuard для release-сборки.
# Цель: декомпилированный jadx должен показывать классы a, b, c
# с методами a(), b(), c() — никаких осмысленных имён.
# ============================================================

# --- Базовые ---
-dontusemixedcaseclassnames
-verbose
-optimizationpasses 5
-allowaccessmodification
-mergeinterfacesaggressively
-overloadaggressively
-repackageclasses ''

# Удаляем Log.d / Log.v / Log.i / Log.w — взломщику меньше зацепок.
# Log.w тоже убираем: в SecurityGuard предупреждения вида "Frida port open",
# "Magisk-style tmpfs mount", "Signature does not match" выдавали бы в strings
# нашу логику детекта. Log.e оставляем — реальные ошибки, без названий техник.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}

# --- Аннотации, дженерики, исключения — оставляем (нужны рантайму) ---
-keepattributes Signature, Exceptions, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# --- Сохраняем только entry-точки приложения (всё остальное обфусцируем) ---

# Application класс — Android создаёт его рефлексивно по имени из манифеста.
-keep public class com.kiberqalqon.App { public <init>(); }

# Activity/Service/Receiver/Provider — Android тоже инстанцирует их рефлексивно.
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends androidx.appcompat.app.AppCompatActivity
-keep public class * extends androidx.work.Worker
-keep public class * extends androidx.work.ListenableWorker

# WorkManager — конструктор (Context, WorkerParameters) вызывается рефлексивно.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ViewBinding генерится с фиксированным именем, его трогать нельзя.
-keep class com.kiberqalqon.databinding.** { *; }

# SecurityGuard сами обфусцируем — наоборот, чем меньше понятно, тем лучше.
# Если бы оставили имена — Frida-скрипт мог бы по имени класса захукать.

# --- View, через атрибут android:onClick="..." ---
-keepclassmembers class * extends android.view.View {
    void set*(***);
    *** get*();
}
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

# --- Parcelable: CREATOR должен остаться по имени ---
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# --- enum: values()/valueOf() нужны JVM ---
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Serializable: serialVersionUID и поля ---
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# --- Сторонние зависимости ---
# OkHttp
# anti-RE (medium);: `-keep class okhttp3.** { *; }` УДАЛЁН. Он сохранял весь OkHttp с читаемыми
# именами → стандартный Frida-хук `Java.use("okhttp3.Request$Builder").header` снимал ВСЕ исходящие
# заголовки (x-device-secret/HMAC к облаку), не трогая ни Shield, ни нативную либу — самый дешёвый
# обход всей ветки hardening. OkHttp 4.x не требует keep-правил: consumer-rules лежат в jar
# (META-INF/proguard); достаточно -dontwarn ниже.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# AndroidX WorkManager
-dontwarn androidx.work.**
-keep class androidx.work.impl.** { *; }

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# --- Reflection: предупреждения, на которые можно забить ---
-dontwarn java.lang.invoke.**
-dontwarn javax.annotation.**

# --- JNI / Native (libkqguard.so) ---
# JNI_OnLoad ichida RegisterNatives FindClass("com/kiberqalqon/NativeBridge") bo'yicha
# bog'lanadi — shuning uchun klass NOMI R8'dan keyin ham saqlanishi SHART, aks holda
# native binding yiqiladi (UnsatisfiedLinkError → loaded=false → himoya kuchsizlanadi).
-keep class com.kiberqalqon.NativeBridge { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# --- Shizuku ---
# ShizukuDeleter Shizuku.newProcess'ni FAQAT reflection (getDeclaredMethod("newProcess"))
# orqali chaqiradi. Bu metod hech qayerdan to'g'ridan-to'g'ri chaqirilmagani uchun R8 uni
# release build'da o'CHIRADI yoki NOMINI o'zgartiradi → getDeclaredMethod NoSuchMethodException
# tashlaydi → /Android/data virusni haqiqiy o'chirish jim ishlamay qoladi (debug/releasefast'da
# ko'rinmaydi — u yerda minify o'chiq). Shu sabab Shizuku klasslarini butun saqlaymiz.
-keep class rikka.shizuku.Shizuku { *; }
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }
-dontwarn rikka.shizuku.**
