package app.lumen.player.playback

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.C
import app.lumen.player.data.EqPreset
import app.lumen.player.diag.RingLog
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The equaliser, bound to the player's audio session id. DynamicsProcessing first (five pre-EQ
 * bands, one MBC band used as a gentle compressor for Speech, limiter on); if the device refuses
 * it, android.media.audiofx.Equalizer with its nearest bands; if that fails too, nothing, and the
 * Settings screen hides the EQ section (engine "none").
 */
class AudioEffects {

    companion object {
        const val ENGINE_DP = "dp"
        const val ENGINE_EQ = "eq"
        const val ENGINE_NONE = "none"

        /** Upper cut-off per band for DynamicsProcessing, which defines bands by their top edge. */
        private val cutoffHz = floatArrayOf(150f, 500f, 2000f, 6000f, 20000f)
        private const val LOUDNESS_SPEECH_MB = 300
        private const val PRIORITY = 0
    }

    private var sessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var dp: DynamicsProcessing? = null
    private var eq: Equalizer? = null
    private var loudness: LoudnessEnhancer? = null

    var engine: String = ""
        private set

    private var lastPreset: String = EqPreset.FLAT
    private var lastGains: List<Float> = EqPreset.gains(EqPreset.FLAT, emptyList())

    /** Creates the effects on [newSessionId]; re-applies the last preset. Returns the engine. */
    fun bind(newSessionId: Int): String {
        if (newSessionId == C.AUDIO_SESSION_ID_UNSET || newSessionId == 0) return engine
        if (newSessionId == sessionId && engine.isNotEmpty() && engine != ENGINE_NONE) return engine
        release()
        sessionId = newSessionId
        engine = createDynamicsProcessing(newSessionId) ?: createEqualizer(newSessionId) ?: ENGINE_NONE
        createLoudness(newSessionId)
        RingLog.i("fx", "bound to session $newSessionId engine=$engine")
        apply(lastPreset, lastGains)
        return engine
    }

    private fun createDynamicsProcessing(id: Int): String? = try {
        val builder = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,
            true,
            cutoffHz.size,
            true,
            1,
            false,
            0,
            true,
        )
        builder.setPreferredFrameDuration(10f)
        val effect = DynamicsProcessing(PRIORITY, id, builder.build())
        for (band in cutoffHz.indices) {
            effect.setPreEqBandAllChannelsTo(band, DynamicsProcessing.EqBand(true, cutoffHz[band], 0f))
        }
        effect.setMbcBandAllChannelsTo(0, idleMbcBand())
        effect.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -2f, 0f))
        effect.setEnabled(true)
        dp = effect
        ENGINE_DP
    } catch (t: Throwable) {
        RingLog.w("fx", "DynamicsProcessing unavailable: $t")
        dp = null
        null
    }

    private fun createEqualizer(id: Int): String? = try {
        val effect = Equalizer(PRIORITY, id)
        effect.setEnabled(true)
        eq = effect
        ENGINE_EQ
    } catch (t: Throwable) {
        RingLog.w("fx", "Equalizer unavailable: $t")
        eq = null
        null
    }

    private fun createLoudness(id: Int) {
        loudness = try {
            LoudnessEnhancer(id).also { it.setTargetGain(0); it.setEnabled(false) }
        } catch (t: Throwable) {
            RingLog.w("fx", "LoudnessEnhancer unavailable: $t")
            null
        }
    }

    private fun idleMbcBand() = DynamicsProcessing.MbcBand(false, 20000f, 3f, 80f, 1f, -45f, 0f, -90f, 1f, 0f, 0f)

    /** Speech: threshold -24 dB, ratio 3, attack 10 ms, release 150 ms, post gain +4 dB. */
    private fun speechMbcBand() = DynamicsProcessing.MbcBand(true, 20000f, 10f, 150f, 3f, -24f, 6f, -90f, 1f, 0f, 4f)

    /** Applies the five band gains (dB) with the effect's own setters, so changes do not click. */
    fun apply(preset: String, gains: List<Float>) {
        lastPreset = preset
        lastGains = gains
        val g = if (gains.size == 5) gains else listOf(0f, 0f, 0f, 0f, 0f)
        val speech = preset == EqPreset.SPEECH
        dp?.let { effect ->
            try {
                for (band in cutoffHz.indices) {
                    effect.setPreEqBandAllChannelsTo(band, DynamicsProcessing.EqBand(true, cutoffHz[band], g[band].coerceIn(-12f, 12f)))
                }
                effect.setMbcBandAllChannelsTo(0, if (speech) speechMbcBand() else idleMbcBand())
            } catch (t: Throwable) {
                RingLog.w("fx", "DynamicsProcessing apply failed: $t")
            }
        }
        eq?.let { effect ->
            try {
                val range = effect.bandLevelRange
                val bands = effect.numberOfBands.toInt()
                for (b in 0 until bands) {
                    val centreHz = effect.getCenterFreq(b.toShort()) / 1000
                    var nearest = 0
                    var best = Int.MAX_VALUE
                    EqPreset.bandHz.forEachIndexed { i, hz ->
                        val d = abs(hz - centreHz)
                        if (d < best) {
                            best = d
                            nearest = i
                        }
                    }
                    val level = (g[nearest] * 100).roundToInt().coerceIn(range[0].toInt(), range[1].toInt())
                    effect.setBandLevel(b.toShort(), level.toShort())
                }
            } catch (t: Throwable) {
                RingLog.w("fx", "Equalizer apply failed: $t")
            }
        }
        loudness?.let { effect ->
            try {
                effect.setTargetGain(if (speech) LOUDNESS_SPEECH_MB else 0)
                effect.setEnabled(speech)
            } catch (t: Throwable) {
                RingLog.w("fx", "LoudnessEnhancer apply failed: $t")
            }
        }
    }

    fun release() {
        try {
            dp?.release()
        } catch (t: Throwable) {
            RingLog.w("fx", "release dp: $t")
        }
        try {
            eq?.release()
        } catch (t: Throwable) {
            RingLog.w("fx", "release eq: $t")
        }
        try {
            loudness?.release()
        } catch (t: Throwable) {
            RingLog.w("fx", "release loudness: $t")
        }
        dp = null
        eq = null
        loudness = null
        sessionId = C.AUDIO_SESSION_ID_UNSET
    }
}
