package net.extrawdw.apps.locationhistory.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Several Chinese OEM Android skins (MIUI/HyperOS, EMUI/HarmonyOS, ColorOS, Funtouch/OriginOS,
 * OxygenOS, Magic UI, ...) add an "auto-start" / "auto-launch" denylist on top of stock Android.
 * When an app isn't on that list the system blocks its background entry points — broadcast receivers
 * (so BOOT_COMPLETED and MY_PACKAGE_REPLACED never fire), services and WorkManager — and may
 * force-stop it outright. That silently kills location recording and cannot be worked around from
 * inside the app: only the user can grant auto-start, in a vendor settings screen. Stock Android
 * (Pixel, Motorola, Nokia, Sony, ...) ships no such list.
 *
 * This detects those devices by manufacturer/brand (the restriction is a property of the OEM skin,
 * not a specific model or Android version) and deep-links into the relevant vendor screen.
 */
object OemAutostart {

    /**
     * @param component   the vendor screen to open; null means "the standard system App-info page"
     *                    ([appDetails] targets).
     * @param appDetails  when true, open Android's per-app App-info page (the one reached by
     *                    long-pressing the launcher icon -> App info) instead of a [component]. On
     *                    MIUI/HyperOS that page is per-app and hosts the Autostart toggle, so it's a
     *                    better landing than the global auto-start list.
     */
    private data class Target(
        val brands: Set<String>,
        val component: ComponentName? = null,
        val appDetails: Boolean = false,
    )

    private fun cn(pkg: String, cls: String) = ComponentName(pkg, cls)

    // Known auto-start screens, listed best-first per brand. Component names are stable across many
    // releases but not guaranteed, so each is resolved before use (and we fall back to the app's own
    // system settings page when none on a matching device can be reached).
    private val TARGETS = listOf(
        // MIUI / HyperOS: the global auto-start list forces the user to find Pathline in it. The
        // standard App-info page is per-app and carries the Autostart toggle, so land there instead.
        Target(setOf("xiaomi", "redmi", "poco"), appDetails = true),
        Target(
            setOf("huawei"),
            cn(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
        ),
        Target(
            setOf("huawei"),
            cn(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            ),
        ),
        Target(
            setOf("honor"),
            cn(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
        ),
        Target(
            setOf("oppo", "realme"),
            cn(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
        ),
        Target(
            setOf("oppo", "realme"),
            cn(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity"
            ),
        ),
        Target(
            setOf("oppo"),
            cn("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ),
        Target(
            setOf("vivo", "iqoo"),
            cn(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ),
        ),
        Target(
            setOf("vivo", "iqoo"),
            cn("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ),
        Target(
            setOf("oneplus"),
            cn(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
            ),
        ),
        Target(
            setOf("letv", "leeco"),
            cn("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"),
        ),
        Target(
            setOf("meizu"),
            cn("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
        ),
        Target(
            setOf("asus"),
            cn("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
        ),
    )

    /** Brand tokens for OEMs that ship an auto-start denylist. Gates the onboarding screen. */
    private val RESTRICTED_BRANDS: Set<String> = TARGETS.flatMap { it.brands }.toSet()

    private fun deviceBrands(): Set<String> =
        setOf(Build.MANUFACTURER, Build.BRAND).mapTo(mutableSetOf()) { it.lowercase() }

    private fun matches(target: Target): Boolean =
        deviceBrands().any { device -> target.brands.any { device.contains(it) } }

    /** True on Chinese-OEM skins known to block background auto-start; false on stock Android. */
    fun isRestrictedDevice(): Boolean =
        deviceBrands().any { device -> RESTRICTED_BRANDS.any { device.contains(it) } }

    /** This app's standard system App-info page — always resolvable, used directly and as fallback. */
    private fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))

    /** First auto-start settings Intent that resolves on this device, or null if none is reachable. */
    private fun autostartIntent(context: Context): Intent? {
        val pm = context.packageManager
        for (target in TARGETS) {
            if (!matches(target)) continue
            val intent = if (target.appDetails) {
                appDetailsIntent(context)
            } else {
                Intent().setComponent(target.component)
            }
            if (pm.resolveActivity(intent, 0) != null) return intent
        }
        return null
    }

    /**
     * Open the device's auto-start screen (the App-info page on MIUI/HyperOS), falling back to this
     * app's system settings page when the specific vendor screen can't be reached. Returns true if
     * some settings screen was launched.
     */
    fun openSettings(context: Context): Boolean {
        autostartIntent(context)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        val fallback = appDetailsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(fallback) }.isSuccess
    }
}
