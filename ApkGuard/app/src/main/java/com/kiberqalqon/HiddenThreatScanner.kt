package com.kiberqalqon

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * УЖЕ УСТАНОВЛЕННЫЕ скрытые / неудаляемые угрозы.
 *
 * Дропперы/банкеры после установки прячутся и сопротивляются удалению. Этот сканер
 * перечисляет НЕсистемные приложения (нужен QUERY_ALL_PACKAGES, он у нас есть) и помечает те,
 * что ведут себя как малварь по ПЕРСИСТЕНТНОСТИ/СКРЫТНОСТИ:
 *  - [Trait.HIDDEN_ICON]   — нет launcher-иконки + опасные права/полномочия (setComponentEnabledSetting trick);
 *  - [Trait.DEVICE_ADMIN]  — активный администратор устройства → блокирует кнопку «Удалить»;
 *  - [Trait.ACCESSIBILITY] — включён Accessibility (читает экран, жмёт кнопки, блокирует uninstall-диалог);
 *  - [Trait.NOTIF_ACCESS]  — доступ к уведомлениям (крадёт OTP без RECEIVE_SMS);
 *  - [Trait.BLACKLISTED]   — пакет в нашем чёрном списке ([MaliciousPackages]).
 *
 * Это РЕВЬЮ-инструмент: сам не удаляет (Android без root не даёт сносить чужие приложения),
 * а даёт пользователю список + гид. Никогда не бросает исключение.
 */
object HiddenThreatScanner {

    enum class Trait { HIDDEN_ICON, DEVICE_ADMIN, ACCESSIBILITY, NOTIF_ACCESS, BLACKLISTED }

    data class Finding(
        val pkg: String,
        val label: String,
        val traits: List<Trait>,
        val dangerousPerms: List<String>,
        val fromPlay: Boolean,
        val family: String?,
    ) {
        /** Сортировочный вес: больше признаков + чёрный список = выше. */
        val score: Int get() = traits.size * 2 + (if (family != null) 5 else 0) + dangerousPerms.size
    }

    private val DANGEROUS = listOf(
        "android.permission.RECEIVE_SMS",
        "android.permission.READ_SMS",
        "android.permission.SEND_SMS",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.READ_PHONE_STATE",
        "android.permission.PROCESS_OUTGOING_CALLS",
        "android.permission.READ_CONTACTS",
        "android.permission.READ_CALL_LOG",
    )

    fun scan(context: Context): List<Finding> {
        val pm = context.packageManager
        val ownPkgs = setOf(context.packageName, "${context.packageName}.debug")
        val admins = activeAdminPackages(context)
        val a11y = enabledServicePackages(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val notif = enabledServicePackages(context, "enabled_notification_listeners")

        val out = ArrayList<Finding>()
        val apps = try { pm.getInstalledApplications(0) } catch (_: Throwable) { return emptyList() }
        for (app in apps) {
            val pkg = app.packageName
            if (pkg in ownPkgs) continue
            if (isSystem(app)) continue

            val dangerous = dangerousPermsOf(pm, pkg)
            val hasLauncher = try { pm.getLaunchIntentForPackage(pkg) != null } catch (_: Throwable) { true }
            val isAdmin = pkg in admins
            val isA11y = pkg in a11y
            val isNotif = pkg in notif
            val family = try { MaliciousPackages.maliciousFamily(pkg) } catch (_: Throwable) { null }

            val traits = ArrayList<Trait>()
            // «Нет иконки» само по себе бывает у легитимных сервис-приложений → требуем ещё опасный признак.
            if (!hasLauncher && (dangerous.isNotEmpty() || isAdmin || isA11y || isNotif)) traits.add(Trait.HIDDEN_ICON)
            if (isAdmin) traits.add(Trait.DEVICE_ADMIN)
            if (isA11y) traits.add(Trait.ACCESSIBILITY)
            if (isNotif) traits.add(Trait.NOTIF_ACCESS)
            if (family != null) traits.add(Trait.BLACKLISTED)

            if (traits.isNotEmpty()) {
                out.add(Finding(pkg, labelOf(pm, app), traits, dangerous, fromPlay(pm, pkg), family))
            }
        }
        return out.sortedByDescending { it.score }
    }

    private fun isSystem(app: ApplicationInfo): Boolean =
        (app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

    private fun labelOf(pm: PackageManager, app: ApplicationInfo): String =
        try { pm.getApplicationLabel(app).toString() } catch (_: Throwable) { app.packageName }

    private fun dangerousPermsOf(pm: PackageManager, pkg: String): List<String> = try {
        val reqs = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions
        if (reqs == null) emptyList() else DANGEROUS.filter { it in reqs }
    } catch (_: Throwable) { emptyList() }

    private fun activeAdminPackages(context: Context): Set<String> = try {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.activeAdmins?.map { it.packageName }?.toSet() ?: emptySet()
    } catch (_: Throwable) { emptySet() }

    /** "pkg/cls:pkg2/cls2" yoki "pkg/cls" — paket nomlarini ajratib oladi. */
    private fun enabledServicePackages(context: Context, key: String): Set<String> = try {
        val raw = Settings.Secure.getString(context.contentResolver, key) ?: ""
        if (raw.isBlank()) emptySet()
        else raw.split(':').mapNotNull { entry ->
            entry.substringBefore('/').takeIf { it.isNotBlank() }
        }.toSet()
    } catch (_: Throwable) { emptySet() }

    private fun fromPlay(pm: PackageManager, pkg: String): Boolean = try {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
        }
        installer == "com.android.vending"
    } catch (_: Throwable) { false }
}
