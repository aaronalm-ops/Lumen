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
    val night: Boolean = false,
    val skipBackSeconds: Int = 10,
    val skipForwardSeconds: Int = 30,
    /** uri to "speech" or "music". */
    val layoutOverrides: Map<String, String> = emptyMap(),
) {
    val sleepActive: Boolean get() = sleepEndAt > System.currentTimeMillis()
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
        const val KEY_NIGHT = "night"
        const val KEY_SKIP_BACK = "skip_back_s"
        const val KEY_SKIP_FORWARD = "skip_fwd_s"
        const val KEY_SEEDED = "labels_seeded"
        const val LAYOUT_PREFIX = "layout:"

        val speeds = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val sleepChoices = listOf(0, 15, 30, 45, 60)

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
                night = map[KEY_NIGHT] == "1",
                skipBackSeconds = map[KEY_SKIP_BACK]?.toIntOrNull()?.coerceIn(5, 60) ?: 10,
                skipForwardSeconds = map[KEY_SKIP_FORWARD]?.toIntOrNull()?.coerceIn(5, 120) ?: 30,
                layoutOverrides = map.filterKeys { it.startsWith(LAYOUT_PREFIX) }
                    .mapKeys { it.key.removePrefix(LAYOUT_PREFIX) },
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

    suspend fun clearSleep() {
        put(KEY_SLEEP_MINUTES, "0")
        put(KEY_SLEEP_END_AT, "0")
    }

    suspend fun setNight(on: Boolean) = put(KEY_NIGHT, if (on) "1" else "0")

    suspend fun setSkips(backSeconds: Int, forwardSeconds: Int) {
        put(KEY_SKIP_BACK, backSeconds.coerceIn(5, 60).toString())
        put(KEY_SKIP_FORWARD, forwardSeconds.coerceIn(5, 120).toString())
    }

    suspend fun setLayoutOverride(uri: String, layout: String) = put(LAYOUT_PREFIX + uri, layout)
}
