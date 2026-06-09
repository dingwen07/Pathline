package net.extrawdw.apps.locationhistory.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.api.PathlineContract
import net.extrawdw.apps.locationhistory.data.db.ApiAccessDao
import net.extrawdw.apps.locationhistory.data.db.ApiAccessEventEntity
import net.extrawdw.apps.locationhistory.data.db.AppLastAccess
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A scope another app can request, paired with the OS permission that gates it. */
enum class ApiScope(val permission: String, @param:StringRes val labelRes: Int) {
    TIMELINE(PathlineContract.Permissions.READ_TIMELINE, R.string.api_scope_timeline),
    TIMELINE_ROUTE(
        PathlineContract.Permissions.READ_TIMELINE_ROUTE,
        R.string.api_scope_timeline_route
    ),
    LOCATION_HISTORY(
        PathlineContract.Permissions.READ_LOCATION_HISTORY,
        R.string.api_scope_location_history
    ),
    EXTENDED_HISTORY(
        PathlineContract.Permissions.READ_EXTENDED_HISTORY,
        R.string.api_scope_extended_history
    );

    companion object {
        fun forDataType(dataType: String): ApiScope? = when (dataType) {
            PathlineContract.Visits.PATH, PathlineContract.Trips.PATH -> TIMELINE
            PathlineContract.Samples.PATH -> LOCATION_HISTORY
            else -> null
        }
    }
}

/** An installed app that declares one or more Pathline permissions, with its current OS grant state. */
data class DeclaredApp(
    val packageName: String,
    val label: String,
    val declared: List<ApiScope>,
    val granted: List<ApiScope>,
    /** True when the app is still installed (resolvable via PackageManager). */
    val installed: Boolean,
    /** PNG bytes of the icon — live when resolvable, otherwise the last cached copy. */
    val iconPng: ByteArray? = null,
)

/** Auto-cleanup settings for the audit log, surfaced in the access manager. */
data class CleanupConfig(val enabled: Boolean, val retentionDays: Int)

/**
 * Reads the API access audit log and cross-references the installed apps that declare Pathline's
 * permissions. Enforcement still lives entirely in the OS (the custom dangerous permissions checked
 * by [net.extrawdw.apps.locationhistory.api.PathlineProvider]); this repository is read-only
 * observability — it cannot and does not change any grant.
 */
@Singleton
class ApiAccessRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: ApiAccessDao,
) {
    fun observeEventsSince(sinceMs: Long, limit: Int): Flow<List<ApiAccessEventEntity>> =
        dao.observeSince(sinceMs, limit)

    fun observeAppLastAccess(): Flow<List<AppLastAccess>> = dao.observeAppLastAccess()

    suspend fun readsSince(sinceMs: Long): List<ApiAccessEventEntity> = dao.since(sinceMs)

    /** One app's reads since [sinceMs] — used to summarize what it accessed in the alert. */
    suspend fun readsForPackageSince(
        packageName: String,
        sinceMs: Long
    ): List<ApiAccessEventEntity> =
        dao.sinceForPackage(packageName, sinceMs)

    /** The full audit log, for "export all". */
    suspend fun allEvents(): List<ApiAccessEventEntity> = dao.all()

    /** Every package that has ever read data (the only apps we can resolve without broad querying). */
    suspend fun loggedPackages(): Set<String> = dao.distinctPackages().toSet()

    // ---- Audit-log retention / auto-cleanup. The user picks a retention window in the access manager;
    //      [ApiAccessMaintenanceWorker] applies it on its daily run, and "Delete now" applies it on demand.

    /** Reads the saved auto-cleanup config, defaulting to enabled at [DEFAULT_RETENTION_DAYS]. */
    fun cleanupConfig(): CleanupConfig {
        val p = cleanupPrefs
        return CleanupConfig(
            enabled = p.getBoolean(KEY_CLEANUP_ENABLED, true),
            retentionDays = p.getInt(KEY_CLEANUP_RETENTION_DAYS, DEFAULT_RETENTION_DAYS),
        )
    }

    fun setCleanupConfig(config: CleanupConfig) {
        cleanupPrefs.edit()
            .putBoolean(KEY_CLEANUP_ENABLED, config.enabled)
            .putInt(KEY_CLEANUP_RETENTION_DAYS, config.retentionDays)
            .apply()
    }

    /** Deletes log rows older than [retentionDays]; returns the number removed. */
    suspend fun cleanupOlderThan(
        retentionDays: Int,
        nowMs: Long = System.currentTimeMillis()
    ): Int =
        dao.pruneBefore(nowMs - retentionDays.toLong() * MS_PER_DAY)

    /** The scheduled path: prune per the saved config, but only while auto-cleanup is enabled. */
    suspend fun runScheduledCleanup(nowMs: Long): Int {
        val config = cleanupConfig()
        return if (config.enabled) cleanupOlderThan(config.retentionDays, nowMs) else 0
    }

    private val cleanupPrefs by lazy {
        context.getSharedPreferences(CLEANUP_PREFS, Context.MODE_PRIVATE)
    }

    /**
     * Clear the per-app "recently notified" back-off so the next read from each app alerts immediately
     * again. Backs the access manager's "reset notification timers" action.
     */
    fun resetReadNotificationBackoff() {
        context.getSharedPreferences(READ_NOTIFY_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * Resolves the given [packages] (the apps seen in the audit log) into per-app grant state.
     *
     * It deliberately resolves each package **individually** via [PackageManager.getPackageInfo] and
     * never calls `getInstalledPackages` / requires `QUERY_ALL_PACKAGES` — Google discourages broad
     * package enumeration. Any app that has read our provider is already visible to us under package
     * visibility rules, so a targeted lookup succeeds; an uninstalled / no-longer-visible package
     * falls back to a name-only row.
     */
    suspend fun declaredApps(packages: Set<String>): List<DeclaredApp> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            packages
                .filter { it != context.packageName && it != "unknown" }
                .map { pkg ->
                    val info =
                        runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) }
                            .getOrNull()
                    if (info != null) {
                        // Resolvable: build the live row, refresh the cache, and use the live icon
                        // (falling back to the cached icon only if rendering it failed this time).
                        val app = info.toDeclaredApp(pm)
                        val icon =
                            runCatching { renderIconPng(pm.getApplicationIcon(pkg)) }.getOrNull()
                                ?: readCachedIcon(pkg)
                        writeCache(pkg, app.label, icon)
                        app.copy(iconPng = icon)
                    } else {
                        // Not resolvable (uninstalled, or mid package-replace): show the cached identity.
                        DeclaredApp(
                            packageName = pkg,
                            label = readCachedLabel(pkg) ?: pkg,
                            declared = emptyList(),
                            granted = emptyList(),
                            installed = false,
                            iconPng = readCachedIcon(pkg),
                        )
                    }
                }
                .sortedBy { it.label.lowercase() }
        }

    private fun PackageInfo.toDeclaredApp(pm: PackageManager): DeclaredApp {
        val requested = requestedPermissions ?: emptyArray()
        val flags = requestedPermissionsFlags
        val declared = ApiScope.entries.filter { it.permission in requested }
        val granted = ApiScope.entries.filter { scope ->
            val idx = requested.indexOf(scope.permission)
            idx >= 0 && flags != null &&
                    (flags[idx] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
        }
        val label = applicationInfo
            ?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
            ?: packageName
        return DeclaredApp(packageName, label, declared, granted, installed = true)
    }

    // ---- Persistent name/icon cache, on disk so it survives the audit DB being rebuilt. Labels
    //      live in a prefs file; icons are PNGs under filesDir/app_info_cache/, both keyed by package.
    private val cacheDir: File by lazy {
        File(
            context.filesDir,
            "app_info_cache"
        ).apply { mkdirs() }
    }
    private val cachePrefs by lazy {
        context.getSharedPreferences("app_info_cache", Context.MODE_PRIVATE)
    }

    private fun iconFile(pkg: String) = File(cacheDir, "$pkg.png")
    private fun readCachedLabel(pkg: String): String? = cachePrefs.getString(pkg, null)
    private fun readCachedIcon(pkg: String): ByteArray? =
        iconFile(pkg).takeIf { it.exists() }?.let { runCatching { it.readBytes() }.getOrNull() }

    private fun writeCache(pkg: String, label: String, icon: ByteArray?) {
        cachePrefs.edit().putString(pkg, label).apply()
        if (icon != null) runCatching { iconFile(pkg).writeBytes(icon) }
    }

    private fun renderIconPng(drawable: Drawable): ByteArray {
        val bitmap = drawable.toBitmap(ICON_PX, ICON_PX)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    companion object {
        private const val ICON_PX = 144
        private const val MS_PER_DAY = 24L * 60 * 60 * 1000

        /** Prefs file holding the per-app read-notification back-off (shared with the notify worker). */
        const val READ_NOTIFY_PREFS = "api_access_read_notify"

        /** Auto-cleanup config store. */
        private const val CLEANUP_PREFS = "api_access_cleanup"
        private const val KEY_CLEANUP_ENABLED = "enabled"
        private const val KEY_CLEANUP_RETENTION_DAYS = "retention_days"

        const val DEFAULT_RETENTION_DAYS = 90

        /** The retention windows offered in the access manager. */
        val RETENTION_DAY_OPTIONS = listOf(30, 90, 180)
    }
}
