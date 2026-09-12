package bd.callbridge.gemini

/**
 * Live API audio pricing for `gemini-3.1-flash-live-preview` (spec §9), verified 2026-09-12
 * against https://ai.google.dev/gemini-api/docs/pricing : audio input $3.00 / 1M tokens
 * ($0.005/min), audio output $12.00 / 1M tokens ($0.018/min). Same per-token rates apply to the
 * fallback model `gemini-2.5-flash-native-audio-preview-12-2025`. Re-verify before the shop
 * pilot if pricing changes.
 */
object CostModel {
    const val INPUT_USD_PER_MINUTE: Double = 0.005
    const val OUTPUT_USD_PER_MINUTE: Double = 0.018

    fun estimateUsd(inputSeconds: Double, outputSeconds: Double): Double =
        (inputSeconds / 60.0) * INPUT_USD_PER_MINUTE + (outputSeconds / 60.0) * OUTPUT_USD_PER_MINUTE
}
