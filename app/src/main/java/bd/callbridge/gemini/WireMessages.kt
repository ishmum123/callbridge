package bd.callbridge.gemini

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Outbound/inbound JSON shapes for the Gemini Live API `BidiGenerateContent` WebSocket.
 * Verified 2026-09-12 against https://ai.google.dev/api/live and
 * https://ai.google.dev/gemini-api/docs/live-api/capabilities — see `docs/gemini-live.md`.
 *
 * Inbound server messages are parsed manually (see [GeminiLiveSession]) against a [JsonObject]
 * rather than through these classes, since the server message is a union with several optional
 * top-level keys (`setupComplete`, `serverContent`, `goAway`, ...) that's simpler to inspect than
 * to model as a strict sealed hierarchy.
 */

@Serializable
data class SetupEnvelope(val setup: SetupConfig)

@Serializable
data class SetupConfig(
    val model: String,
    val generationConfig: GenerationConfig,
    val systemInstruction: Content? = null,
    val inputAudioTranscription: JsonObject = JsonObject(emptyMap()),
    val outputAudioTranscription: JsonObject = JsonObject(emptyMap()),
    val realtimeInputConfig: RealtimeInputConfig? = null,
)

@Serializable
data class GenerationConfig(
    val responseModalities: List<String>,
    val speechConfig: SpeechConfig? = null,
)

@Serializable
data class SpeechConfig(
    val voiceConfig: VoiceConfig,
    val languageCode: String,
)

@Serializable
data class VoiceConfig(val prebuiltVoiceConfig: PrebuiltVoiceConfig)

@Serializable
data class PrebuiltVoiceConfig(val voiceName: String)

@Serializable
data class Content(val parts: List<Part>)

@Serializable
data class Part(val text: String? = null, val inlineData: InlineData? = null)

@Serializable
data class InlineData(val mimeType: String, val data: String)

@Serializable
data class RealtimeInputConfig(
    val automaticActivityDetection: AutomaticActivityDetection,
    /** "START_OF_ACTIVITY_INTERRUPTS" (default) or "NO_INTERRUPTION". */
    val activityHandling: String? = null,
)

@Serializable
data class AutomaticActivityDetection(val disabled: Boolean)

@Serializable
data class RealtimeInputEnvelope(val realtimeInput: RealtimeInputPayload)

@Serializable
data class RealtimeInputPayload(
    val audio: AudioBlob? = null,
    val activityStart: JsonObject? = null,
    val activityEnd: JsonObject? = null,
)

@Serializable
data class AudioBlob(val data: String, val mimeType: String)

/** mimeType for 16 kHz PCM16 audio we send to the Live API (spec §4.4). */
const val INPUT_AUDIO_MIME_TYPE = "audio/pcm;rate=16000"
