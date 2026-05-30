package net.extrawdw.apps.locationhistory.backup

/**
 * Progress channel for a managed (UI-driven) backup/restore. The engine pushes human-readable log
 * lines and a 0..1 fraction (or a negative value for indeterminate) as it works, so the managed
 * sheet can show a live progress bar and log.
 */
interface BackupReporter {
    fun log(message: String)
    fun progress(fraction: Float)

    companion object {
        /** No-op reporter for unattended (worker) runs. */
        val None: BackupReporter = object : BackupReporter {
            override fun log(message: String) {}
            override fun progress(fraction: Float) {}
        }
    }
}
