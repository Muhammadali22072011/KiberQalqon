package com.uzguard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * OEM autostart / battery-saver yo'naltirgich.
 *
 * Xitoy ishlab chiqaruvchilarining (Xiaomi/Redmi, Huawei, Oppo, Vivo, OnePlus,
 * Realme, Meizu) mahalliy "battery saver" va "autostart" sozlamalari Android'ning
 * standart [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS] dialogidan
 * keskin farq qiladi:
 *
 *  - Foydalanuvchi Android settings'da "Don't optimize" tanlasa ham, MIUI/EMUI
 *    o'zining qo'shimcha "Autostart" va "Background activity" sozlamalarini
 *    mustaqil ushlab turadi.
 *  - WorkManager 15-daq periodic skanni ishga tushira olmaydi (jarayon
 *    OEM tomonidan o'ldiriladi).
 *  - Foreground service ham ba'zi MIUI versiyalarida o'chirilishi mumkin.
 *
 * Bu yordamchi:
 *  1) Qurilma ishlab chiqaruvchisini aniqlaydi (Build.MANUFACTURER + MIUI
 *     uchun maxsus `ro.miui.ui.version.code` props).
 *  2) Mos OEM Security Center / Phone Manager ekranini ochadi.
 *  3) Activity topilmasa (eski telefonda Activity nomi boshqacha bo'lsa),
 *     standart Android battery optimization screeniga tushib qoladi.
 *
 * Foydalanuvchi MIUI Security Center → Autostart'ga tushganida, "UZGUARD"
 * ro'yxatidan topib qo'lda yoqishi kerak. Buni avtomatik qila olmaymiz — Android
 * OEM'ga sigorta sifatida bunday API bermagan.
 */
object OemAutostartGuide {

    private const val TAG = "OemAutostart"

    enum class Oem(val displayName: String) {
        XIAOMI_MIUI("Xiaomi / Redmi (MIUI)"),
        HUAWEI("Huawei (EMUI / HarmonyOS)"),
        HONOR("Honor"),
        OPPO("Oppo / Realme (ColorOS)"),
        VIVO("Vivo (Funtouch OS)"),
        ONEPLUS("OnePlus (OxygenOS)"),
        SAMSUNG("Samsung (One UI)"),
        MEIZU("Meizu (Flyme)"),
        ASUS("Asus (ZenUI)"),
        NOKIA("Nokia"),
        OTHER("Standart Android"),
    }

    /** Qurilma ishlab chiqaruvchisini aniqlash. */
    fun detect(): Oem {
        val m = (Build.MANUFACTURER ?: "").lowercase()
        val br = (Build.BRAND ?: "").lowercase()
        val product = (Build.PRODUCT ?: "").lowercase()

        // Xiaomi MIUI'ni alohida tekshiramiz — `ro.miui.ui.version.code` bor.
        if (isMiui()) return Oem.XIAOMI_MIUI
        if (m.contains("xiaomi") || br.contains("xiaomi") ||
            br.contains("redmi") || br.contains("poco") ||
            product.contains("redmi") || product.contains("poco")) return Oem.XIAOMI_MIUI

        if (m.contains("huawei") || br.contains("huawei")) return Oem.HUAWEI
        if (m.contains("honor") || br.contains("honor")) return Oem.HONOR
        if (m.contains("oppo") || br.contains("oppo")) return Oem.OPPO
        if (m.contains("realme") || br.contains("realme")) return Oem.OPPO
        if (m.contains("vivo") || br.contains("vivo")) return Oem.VIVO
        if (m.contains("oneplus") || br.contains("oneplus")) return Oem.ONEPLUS
        if (m.contains("samsung") || br.contains("samsung")) return Oem.SAMSUNG
        if (m.contains("meizu") || br.contains("meizu")) return Oem.MEIZU
        if (m.contains("asus") || br.contains("asus")) return Oem.ASUS
        if (m.contains("nokia") || br.contains("nokia") || br.contains("hmd")) return Oem.NOKIA
        return Oem.OTHER
    }

    /**
     * Berilgan OEM uchun bu qurilmada qo'shimcha autostart sozlamasi bormi yoki yo'qmi.
     * Standart Android (Pixel, Nokia, Sony) uchun `false` — odatdagi battery
     * optimization dialogi yetarli.
     */
    fun hasOemRestrictions(oem: Oem = detect()): Boolean = when (oem) {
        Oem.XIAOMI_MIUI, Oem.HUAWEI, Oem.HONOR, Oem.OPPO,
        Oem.VIVO, Oem.ONEPLUS, Oem.MEIZU -> true
        Oem.SAMSUNG -> true // Battery → Background usage limits
        Oem.ASUS -> true    // ASUS Mobile Manager → Auto-start
        else -> false
    }

    /**
     * Foydalanuvchiga ko'rsatiladigan o'zbekcha qisqa yo'riqnoma.
     * Har bir OEM uchun shartlar farq qiladi.
     */
    fun instructions(oem: Oem = detect()): String = when (oem) {
        Oem.XIAOMI_MIUI ->
            "MIUI Security Center ochiladi:\n" +
            "1. Ro'yxatda \"UZGUARD\"ni toping\n" +
            "2. Avtoyoqishni (Autostart) YOQING\n" +
            "3. Orqaga qaytib, Battery sozlamasida \"No restrictions\" tanlang\n" +
            "4. Recents ekrandan UzGuard kartochkasini past tortib qulflang"
        Oem.HUAWEI, Oem.HONOR ->
            "Huawei/Honor Phone Manager ochiladi:\n" +
            "1. \"App launch\" ro'yxatida UZGUARD'ni toping\n" +
            "2. Auto-managed'ni o'chirib, Manual rejimga o'tkazing\n" +
            "3. Auto-launch + Secondary launch + Run in background — UCHALA SI YOQILGAN bo'lsin"
        Oem.OPPO ->
            "Oppo/Realme Security ochiladi:\n" +
            "1. \"Auto-launch\" ro'yxatida UZGUARD'ni YOQING\n" +
            "2. Settings → Battery → UzGuard → \"Allow background activity\""
        Oem.VIVO ->
            "Vivo iManager ochiladi:\n" +
            "1. \"Auto-start manager\"da UZGUARD YOQILGAN bo'lsin\n" +
            "2. \"High background power consumption\"da ham yoqing"
        Oem.ONEPLUS ->
            "OnePlus battery sozlamasi ochiladi:\n" +
            "1. \"Battery optimization\"da UZGUARD \"Don't optimize\"\n" +
            "2. Recent apps'da UzGuard'ni qulflang"
        Oem.SAMSUNG ->
            "Samsung battery sozlamasi ochiladi:\n" +
            "1. \"Background usage limits\"da UZGUARD'ni \"Never sleeping apps\" ro'yxatiga qo'shing\n" +
            "2. App Power Management'da \"Adaptive battery\"dan istisno qiling"
        Oem.MEIZU ->
            "Meizu Security ochiladi:\n" +
            "1. \"Background management\"da UZGUARD'ni \"Allow\""
        Oem.ASUS ->
            "ASUS Mobile Manager:\n" +
            "1. \"Auto-start manager\"da UZGUARD YOQILGAN bo'lsin"
        Oem.NOKIA, Oem.OTHER ->
            "Standart Android sozlamasida \"Don't optimize\" tanlang."
    }

    /**
     * OEM-specific autostart/battery ekranini ochishga harakat qiladi.
     * Activity topilmasa, [openSystemBatterySettings]'ga tushib qoladi.
     *
     * Qaytarilgan qiymat: muvaffaqiyatli ochilgan bo'lsa `true`.
     */
    fun openAutostartSettings(context: Context, oem: Oem = detect()): Boolean {
        val candidates = intentsFor(oem)
        for ((pkg, cls) in candidates) {
            try {
                val intent = Intent().apply {
                    component = ComponentName(pkg, cls)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return true
                }
            } catch (e: Throwable) {
                Log.w(TAG, "intent failed: $pkg/$cls", e)
            }
        }
        // Hech narsa topilmasa — standart Android battery optimization'ga tushamiz.
        return openSystemBatterySettings(context)
    }

    /** Android standart "ignore battery optimizations" ekrani. */
    fun openSystemBatterySettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                true
            } else {
                // Fallback — to'liq ilova ma'lumotlari sahifasiga.
                val info = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(info)
                true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "system battery settings failed", e)
            false
        }
    }

    /** Har bir OEM uchun Activity'lar ro'yxati — tartib bo'yicha tanlanadi. */
    private fun intentsFor(oem: Oem): List<Pair<String, String>> = when (oem) {
        Oem.XIAOMI_MIUI -> listOf(
            // Eng yangi MIUI 12+ — Autostart management
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            // Power keeper (battery saver)
            "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            // Old MIUI fallback — Power Settings
            "com.miui.securitycenter" to "com.miui.powercenter.PowerSettings",
        )
        Oem.HUAWEI, Oem.HONOR -> listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
        )
        Oem.OPPO -> listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.coloros.oppoguardelf" to "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
        )
        Oem.VIVO -> listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.PurviewTabActivity",
        )
        Oem.ONEPLUS -> listOf(
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        )
        Oem.SAMSUNG -> listOf(
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
        )
        Oem.MEIZU -> listOf(
            "com.meizu.safe" to "com.meizu.safe.security.SHOW_APPSEC",
            "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity",
        )
        Oem.ASUS -> listOf(
            "com.asus.mobilemanager" to "com.asus.mobilemanager.entry.FunctionActivity",
            "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
        )
        else -> emptyList()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  OEM-SPECIFIC OVERLAY / BACKGROUND-POPUP / LOCK-SCREEN PERMISSIONS
    //
    //  Standart Android [Settings.canDrawOverlays] yetarli emas:
    //  - MIUI'ning "Other permissions" ekranida 2 ta alohida toggle:
    //      "Display pop-up window while running in background"
    //      "Show on lock screen"
    //    Bularsiz fon'dan AutoScanActivity ochilmaydi.
    //  - EMUI/HarmonyOS'da "App control" → "Lock screen display" + "Pop-up"
    //  - ColorOS/Funtouch'da o'xshash sozlamalar yashirilgan.
    //
    //  Bu API'lar Android'da ochiq emas — biz faqat foydalanuvchini to'g'ri
    //  ekranga olib boramiz va o'zbekcha aniq yo'riqnoma beramiz.
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Berilgan OEM'da standart overlay'dan tashqari qo'shimcha sozlama kerakmi?
     */
    fun needsExtraOverlayPermissions(oem: Oem = detect()): Boolean = when (oem) {
        Oem.XIAOMI_MIUI, Oem.HUAWEI, Oem.HONOR,
        Oem.OPPO, Oem.VIVO, Oem.MEIZU -> true
        else -> false
    }

    /** Foydalanuvchiga ko'rsatiladigan o'zbekcha yo'riqnoma — overlay/popup uchun. */
    fun overlayInstructions(oem: Oem = detect()): String = when (oem) {
        Oem.XIAOMI_MIUI ->
            "MIUI \"Other permissions\" ekrani ochiladi:\n" +
            "1. \"Display pop-up windows while running in background\" — YOQING\n" +
            "2. \"Show on Lock screen\" — YOQING\n" +
            "3. \"Display pop-up window\" — YOQING\n\n" +
            "Bularsiz fon'da virus topilganda popup chiqmaydi."
        Oem.HUAWEI, Oem.HONOR ->
            "Huawei \"App control\":\n" +
            "1. \"Lock screen display\" — YOQING\n" +
            "2. \"Pop-up\" / \"Floating window\" — YOQING\n" +
            "3. \"Notification on lock screen\" — YOQING"
        Oem.OPPO ->
            "ColorOS \"Floating windows\":\n" +
            "1. UZGUARD uchun \"Floating window\"ni YOQING\n" +
            "2. \"Display on Lock screen\" — YOQING"
        Oem.VIVO ->
            "Vivo \"Floating window\":\n" +
            "1. UZGUARD uchun \"Floating window\" — YOQING\n" +
            "2. \"Display on lock screen\" ham YOQING"
        Oem.MEIZU ->
            "Meizu \"Background floating window\":\n" +
            "1. UZGUARD — YOQING"
        else ->
            "Standart Android \"Display over other apps\" ruxsatini bering."
    }

    /**
     * OEM-specific permissions editor sahifasini ochish. MIUI'ning "Other
     * permissions" yoki EMUI'ning "App control" ekrani — standart Android'da
     * yo'q toggle'larni boshqaradi.
     */
    fun openOemAppPermissions(context: Context, oem: Oem = detect()): Boolean {
        val candidates = oemPermissionsIntents(oem, context.packageName)
        for (intent in candidates) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return true
                }
            } catch (e: Throwable) {
                Log.w(TAG, "oem permission intent failed", e)
            }
        }
        // Fallback — ilova ma'lumotlari ekrani. Foydalanuvchi u yerdan
        // "Other permissions"ga o'tishi mumkin.
        return try {
            val info = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(info)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "app details fallback failed", e)
            false
        }
    }

    private fun oemPermissionsIntents(oem: Oem, pkg: String): List<Intent> = when (oem) {
        Oem.XIAOMI_MIUI -> listOf(
            // MIUI 11+ — app permissions editor (Other permissions ichida).
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity",
                )
                putExtra("extra_pkgname", pkg)
            },
            // Eski MIUI versiyalari uchun AppPermissionsEditor.
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.AppPermissionsEditorActivity",
                )
                putExtra("extra_pkgname", pkg)
            },
            // Java action approach for very old MIUI.
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.AppPermissionsEditorActivity",
                )
                putExtra("extra_pkgname", pkg)
            },
        )
        Oem.HUAWEI, Oem.HONOR -> listOf(
            // Huawei "App control" — lock screen, pop-up, background activity.
            Intent().apply {
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.permissionmanager.ui.MainActivity",
                )
            },
            Intent().apply {
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.notificationmanager.ui.NotificationManagmentActivity",
                )
            },
        )
        Oem.OPPO -> listOf(
            // ColorOS "Floating windows" / "Permission center".
            Intent().apply {
                component = ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.privacypermissionsentry.PermissionTopActivity",
                )
            },
            Intent().apply {
                component = ComponentName(
                    "com.coloros.notificationmanager",
                    "com.coloros.notificationmanager.NotificationCenterActivity",
                )
            },
        )
        Oem.VIVO -> listOf(
            Intent().apply {
                component = ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity",
                )
                putExtra("packagename", pkg)
            },
            Intent().apply {
                component = ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.safeguard.SoftPermissionDetailActivity",
                )
                putExtra("packagename", pkg)
            },
        )
        Oem.MEIZU -> listOf(
            Intent("com.meizu.safe.security.SHOW_APPSEC").apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                putExtra("packageName", pkg)
            },
        )
        else -> emptyList()
    }

    /** MIUI'ni aniqlash uchun system property tekshiramiz. */
    private fun isMiui(): Boolean {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java)
            val version = get.invoke(null, "ro.miui.ui.version.code") as? String
            version != null && version.isNotBlank()
        } catch (_: Throwable) {
            false
        }
    }

    // wasShown/markShown/isDismissed/setDismissed olib tashlandi (2026-08-13):
    // hech qayerdan chaqirilmaydigan o'lik kod edi.
}
