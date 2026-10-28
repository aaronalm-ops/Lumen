package app.lumen.player.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Every user setting lives in the Phase 0 `kv` table, so no new dependency is needed. */
data class Settings(
    val speed: Float = 1.0f,
    val eqPreset: String = EqPreset.FLAT,
    val eqPresetUser: String = EqPreset.FLAT,
    val eqCustom: List<Float> = listOf(0f, 0f, 0f, 0f, 0f),
    /** "dp" DynamicsProcessing, "eq" Equalizer fallback, "none" hidden, "" unknown yet. */
    val eqEngine: String = "",
    val sleepEndAt: Long = 0L,
    val sleepMinutes: Int = 0,
    /**
     * 0.4.0: "auto" follows the system dark mode, "light" or "dark" fixes Home, Library and
     * Settings. Replaces the 0.3 Night toggle; a stored night=1 reads as "dark".
     */
    val appearance: String = Appearance.AUTO,
    val skipBackSeconds: Int = 10,
    val skipForwardSeconds: Int = 30,
    /** uri to "speech" or "music". */
    val layoutOverrides: Map<String, String> = emptyMap(),
    /** When the whole-phone (MediaStore) scan last finished, 0 when it never has. */
    val mediaStoreScanAt: Long = 0L,
    /** How many MediaStore rows that scan left in the library. */
    val mediaStoreScanCount: Int = 0,
    // Phase 1b: Google Drive and backup.
    /** The picked Drive folder's id, "" when Drive is not connected. */
    val driveFolderId: String = "",
    val driveFolderName: String = "",
    val driveScanAt: Long = 0L,
    val driveScanCount: Int = 0,
    /** Set after a 401 or 403, or when a silent authorisation needed the user; cleared on sign-in. */
    val driveNeedsSignIn: Boolean = false,
    /**
     * Set when the held grant lacks drive.readonly (a 0.3.0 to 0.3.2 grant, or the scope was
     * unticked on the consent screen); Settings shows "Reconnect Drive to see the files in your folder".
     */
    val driveNeedsReconnect: Boolean = false,
    /** The Google account's email, from Drive's about.get; "" until the first successful scan. */
    val driveAccount: String = "",
    /** "Allow downloads on mobile data", off by default: Keep offline waits for Wi-Fi. */
    val driveMobileDownloads: Boolean = false,
    /** When the last backup file was written (either destination), 0 when never. */
    val backupAt: Long = 0L,
    /** When the last backup reached Drive, 0 when never; drives the weekly automatic upload. */
    val backupDriveAt: Long = 0L,
    // 0.4.0: Sound.
    /** "Level out voices": a gentler, stronger compressor on speech items. Never on music. */
    val levelOutVoices: Boolean = false,
    /** "Trim silences": Media3 silence skipping on speech items. Never on music. */
    val trimSilences: Boolean = false,
    /** "Smooth transitions" between music items: 0 (off), 2, 4 or 6 seconds. */
    val crossfadeSeconds: Int = 0,
) {
    val driveConnected: Boolean get() = driveFolderId.isNotBlank()

    val sleepActive: Boolean get() = sleepEndAt > System.currentTimeMillis()
}

/** The three Appearance choices for Home, Library and Settings. Now Playing is always black. */
object Appearance {
    const val AUTO = "auto"
    const val LIGHT = "light"
    const val DARK = "dark"

    val order = listOf(AUTO, LIGHT, DARK)

    fun label(value: String): String = when (value) {
        LIGHT -> "Light"
        DARK -> "Dark"
        else -> "Automatic"
    }
}

object EqPreset {
    const val FLAT = "Flat"
    const val SPEECH = "Speech"
    const val BASS = "Bass"
    const val WORSHIP = "Worship"
    const val KIDS = "Kids"
    const val CUSTOM = "Custom"

    val order = listOf(FLAT, SPEECH, BASS, WORSHIP, KIDS, CUSTOM)

    /** Band centres in Hz. */
    val bandHz = listOf(80, 250, 1000, 3500, 10000)

    /** Gains in dB per band, by preset. Custom comes from settings. */
    fun gains(preset: String, custom: List<Float>): List<Float> = when (preset) {
        SPEECH -> listOf(-4f, -1f, 1f, 3f, 1f)
        BASS -> listOf(5f, 2f, 0f, 0f, 1f)
        WORSHIP -> listOf(2f, -1f, 0f, 1.5f, 2.5f)
        KIDS -> listOf(1f, 0f, 1f, 2f, -2f)
        CUSTOM -> if (custom.size == 5) custom else listOf(0f, 0f, 0f, 0f, 0f)
        else -> listOf(0f, 0f, 0f, 0f, 0f)
    }

    fun next(preset: String): String {
        val i = order.indexOf(preset)
        return order[(i + 1).mod(order.size)]
    }
}

class SettingsRepository(private val dao: ProgressDao) {

    companion object {
        const val KEY_SPEED = "speed"
        const val KEY_EQ_PRESET = "eq_preset"
        const val KEY_EQ_PRESET_USER = "eq_preset_user"
        const val KEY_EQ_CUSTOM = "eq_custom"
        const val KEY_EQ_ENGINE = "eq_engine"
        const val KEY_SLEEP_END_AT = "sleep_end_at"
        const val KEY_SLEEP_MINUTES = "sleep_minutes"
        /** 0.3 Night toggle; read only to migrate it to [KEY_APPEARANCE]. */
        const val KEY_NIGHT = "night"
        const val KEY_APPEARANCE = "appearance"
        const val KEY_LEVEL_OUT = "level_out"
        const val KEY_TRIM_SILENCES = "trim_silences"
        const val KEY_CROSSFADE = "crossfade_s"
        const val KEY_SKIP_BACK = "skip_back_s"
        const val KEY_SKIP_FORWARD = "skip_fwd_s"
        const val KEY_SEEDED = "labels_seeded"
        const val KEY_MS_SCAN_AT = "ms_scan_at"
        const val KEY_MS_SCAN_COUNT = "ms_scan_count"
        const val LAYOUT_PREFIX = "layout:"
        const val KEY_DRIVE_FOLDER_ID = "drive_folder_id"
        const val KEY_DRIVE_FOLDER_NAME = "drive_folder_name"
        const val KEY_DRIVE_SCAN_AT = "drive_scan_at"
        const val KEY_DRIVE_SCAN_COUNT = "drive_scan_count"
        const val KEY_DRIVE_NEEDS_SIGN_IN = "drive_needs_sign_in"
        const val KEY_DRIVE_NEEDS_RECONNECT = "drive_needs_reconnect"
        const val KEY_DRIVE_ACCOUNT = "drive_account"
        const val KEY_DRIVE_MOBILE_DOWNLOADS = "drive_mobile_downloads"
        const val KEY_BACKUP_AT = "backup_at"
        const val KEY_BACKUP_DRIVE_AT = "backup_drive_at"

        /** Keys that describe this device rather than the listener; never written by a restore. */
        val deviceOnlyKeys = setOf(
            KEY_EQ_ENGINE, KEY_SEEDED, KEY_MS_SCAN_AT, KEY_MS_SCAN_COUNT, KEY_SLEEP_END_AT, KEY_SLEEP_MINUTES,
            KEY_DRIVE_FOLDER_ID, KEY_DRIVE_FOLDER_NAME, KEY_DRIVE_SCAN_AT, KEY_DRIVE_SCAN_COUNT,
            KEY_DRIVE_NEEDS_SIGN_IN, KEY_DRIVE_NEEDS_RECONNECT, KEY_DRIVE_ACCOUNT, KEY_BACKUP_AT, KEY_BACKUP_DRIVE_AT, PositionStore.KEY_LAST_ITEM,
        )

        val speeds = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val sleepChoices = listOf(0, 15, 30, 45, 60)

        /** 0.7.0: the sleep sheet's list (minutes); the end of the recording is offered beside it. */
        val sleepSheetChoices = listOf(15, 30, 45, 60, 90, 120)
        val crossfadeChoices = listOf(0, 2, 4, 6)

        fun parse(entries: List<KvEntity>): Settings {
            val map = entries.associate { it.k to it.v }
            val custom = map[KEY_EQ_CUSTOM]?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }
            return Settings(
                speed = map[KEY_SPEED]?.toFloatOrNull()?.takeIf { it in 0.5f..3.0f } ?: 1.0f,
                eqPreset = map[KEY_EQ_PRESET]?.takeIf { it in EqPreset.order } ?: EqPreset.FLAT,
                eqPresetUser = map[KEY_EQ_PRESET_USER]?.takeIf { it in EqPreset.order } ?: EqPreset.FLAT,
                eqCustom = if (custom != null && custom.size == 5) custom else listOf(0f, 0f, 0f, 0f, 0f),
                eqEngine = map[KEY_EQ_ENGINE] ?: "",
                sleepEndAt = map[KEY_SLEEP_END_AT]?.toLongOrNull() ?: 0L,
                sleepMinutes = map[KEY_SLEEP_MINUTES]?.toIntOrNull() ?: 0,
                appearance = map[KEY_APPEARANCE]?.takeIf { it in Appearance.order }
                    ?: if (map[KEY_NIGHT] == "1") Appearance.DARK else Appearance.AUTO,
                skipBackSeconds = map[KEY_SKIP_BACK]?.toIntOrNull()?.coerceIn(5, 60) ?: 10,
                skipForwardSeconds = map[KEY_SKIP_FORWARD]?.toIntOrNull()?.coerceIn(5, 120) ?: 30,
                layoutOverrides = map.filterKeys { it.startsWith(LAYOUT_PREFIX) }
                    .mapKeys { it.key.removePrefix(LAYOUT_PREFIX) },
                mediaStoreScanAt = map[KEY_MS_SCAN_AT]?.toLongOrNull() ?: 0L,
                mediaStoreScanCount = map[KEY_MS_SCAN_COUNT]?.toIntOrNull() ?: 0,
                driveFolderId = map[KEY_DRIVE_FOLDER_ID].orEmpty(),
                driveFolderName = map[KEY_DRIVE_FOLDER_NAME].orEmpty(),
                driveScanAt = map[KEY_DRIVE_SCAN_AT]?.toLongOrNull() ?: 0L,
                driveScanCount = map[KEY_DRIVE_SCAN_COUNT]?.toIntOrNull() ?: 0,
                driveNeedsSignIn = map[KEY_DRIVE_NEEDS_SIGN_IN] == "1",
                driveNeedsReconnect = map[KEY_DRIVE_NEEDS_RECONNECT] == "1",
                driveAccount = map[KEY_DRIVE_ACCOUNT].orEmpty(),
                driveMobileDownloads = map[KEY_DRIVE_MOBILE_DOWNLOADS] == "1",
                backupAt = map[KEY_BACKUP_AT]?.toLongOrNull() ?: 0L,
                backupDriveAt = map[KEY_BACKUP_DRIVE_AT]?.toLongOrNull() ?: 0L,
                levelOutVoices = map[KEY_LEVEL_OUT] == "1",
                trimSilences = map[KEY_TRIM_SILENCES] == "1",
                crossfadeSeconds = map[KEY_CROSSFADE]?.toIntOrNull()?.takeIf { it in crossfadeChoices } ?: 0,
            )
        }
    }

    val settings: Flow<Settings> = dao.allKvFlow().map { parse(it) }.distinctUntilChanged()

    suspend fun put(key: String, value: String) = dao.putKv(KvEntity(key, value))

    suspend fun get(key: String): String? = dao.kv(key)

    suspend fun setSpeed(speed: Float) = put(KEY_SPEED, speed.toString())

    /** User-chosen preset: becomes both the active one and the remembered default. */
    suspend fun setPresetByUser(preset: String) {
        put(KEY_EQ_PRESET_USER, preset)
        put(KEY_EQ_PRESET, preset)
    }

    /** Label-driven preset chosen by the service on item change. */
    suspend fun setActivePreset(preset: String) = put(KEY_EQ_PRESET, preset)

    suspend fun setCustomBands(gains: List<Float>) =
        put(KEY_EQ_CUSTOM, gains.joinToString(",") { String.format(java.util.Locale.ROOT, "%.1f", it) })

    suspend fun setEqEngine(engine: String) = put(KEY_EQ_ENGINE, engine)

    suspend fun setSleep(minutes: Int) {
        val end = if (minutes <= 0) 0L else System.currentTimeMillis() + minutes * 60_000L
        put(KEY_SLEEP_MINUTES, minutes.toString())
        put(KEY_SLEEP_END_AT, end.toString())
    }

    /** 0.7.0: "End of this recording": the timer runs to a moment, shown as "End" (minutes -1). */
    suspend fun setSleepUntil(endAt: Long) {
        put(KEY_SLEEP_MINUTES, "-1")
        put(KEY_SLEEP_END_AT, endAt.toString())
    }

    suspend fun clearSleep() {
        put(KEY_SLEEP_MINUTES, "0")
        put(KEY_SLEEP_END_AT, "0")
    }

    suspend fun setAppearance(value: String) {
        if (value in Appearance.order) put(KEY_APPEARANCE, value)
    }

    /**
     * 0.4.0, once on start: a phone that had the 0.3 Night toggle on gets Appearance "dark"
     * written, so the choice survives even if the old key is ever cleared. Nothing is written
     * when Appearance already has a value or Night was off (Automatic is the default).
     */
    suspend fun migrateNightToAppearance() {
        if (get(KEY_APPEARANCE) != null) return
        if (get(KEY_NIGHT) == "1") put(KEY_APPEARANCE, Appearance.DARK)
    }

    suspend fun setLevelOutVoices(on: Boolean) = put(KEY_LEVEL_OUT, if (on) "1" else "0")

    suspend fun setTrimSilences(on: Boolean) = put(KEY_TRIM_SILENCES, if (on) "1" else "0")

    suspend fun setCrossfadeSeconds(seconds: Int) {
        val value = crossfadeChoices.minByOrNull { kotlin.math.abs(it - seconds) } ?: 0
        put(KEY_CROSSFADE, value.toString())
    }

    suspend fun setSkips(backSeconds: Int, forwardSeconds: Int) {
        put(KEY_SKIP_BACK, backSeconds.coerceIn(5, 60).toString())
        put(KEY_SKIP_FORWARD, forwardSeconds.coerceIn(5, 120).toString())
    }

    suspend fun setLayoutOverride(uri: String, layout: String) = put(LAYOUT_PREFIX + uri, layout)

    /** Written by the repository when a whole-phone scan finishes. */
    suspend fun setMediaStoreScan(at: Long, count: Int) {
        put(KEY_MS_SCAN_COUNT, count.toString())
        put(KEY_MS_SCAN_AT, at.toString())
    }

    // Phase 1b.

    suspend fun setDriveFolder(id: String, name: String) {
        put(KEY_DRIVE_FOLDER_ID, id)
        put(KEY_DRIVE_FOLDER_NAME, name)
    }

    suspend fun setDriveScan(at: Long, count: Int) {
        put(KEY_DRIVE_SCAN_COUNT, count.toString())
        put(KEY_DRIVE_SCAN_AT, at.toString())
    }

    suspend fun setDriveNeedsSignIn(needs: Boolean) = put(KEY_DRIVE_NEEDS_SIGN_IN, if (needs) "1" else "0")

    suspend fun setDriveNeedsReconnect(needs: Boolean) = put(KEY_DRIVE_NEEDS_RECONNECT, if (needs) "1" else "0")

    suspend fun setDriveAccount(email: String) = put(KEY_DRIVE_ACCOUNT, email)

    suspend fun setDriveMobileDownloads(allow: Boolean) = put(KEY_DRIVE_MOBILE_DOWNLOADS, if (allow) "1" else "0")

    suspend fun setBackupAt(at: Long) = put(KEY_BACKUP_AT, at.toString())

    suspend fun setBackupDriveAt(at: Long) = put(KEY_BACKUP_DRIVE_AT, at.toString())

    /** Every row of the kv table, for the backup file. */
    suspend fun allEntries(): List<KvEntity> = dao.allKv()
}
