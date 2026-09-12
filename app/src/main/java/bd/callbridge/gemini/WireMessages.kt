package bd.callbridge.gemini

import kotlinx.serialization.EncodeDefault
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
    /**
     * Function-calling tools declared for this session (`docs/gemini-tools.md`). Omitted from the
     * wire message entirely when empty (`@EncodeDefault(NEVER)` overrides the class's own
     * `encodeDefaults = true`) so sessions that don't need tools send exactly the same setup shape
     * as before this field existed.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val tools: List<Tool> = emptyList(),
)

/** One group of function declarations in a `setup.tools[]` entry (spec: `docs/gemini-tools.md`). */
@Serializable
data class Tool(val functionDeclarations: List<FunctionDeclaration>)

/**
 * One callable function the model may invoke via a server `toolCall` message. [parameters] is a
 * JSON-Schema-shaped object (`{"type":"object","properties":{...},"required":[...]}`) — modeled as
 * a raw [JsonObject] rather than a strict class since the schema shape varies per function.
 */
@Serializable
data class FunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: JsonObject,
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
data class AutomaticActivityDetection(
    val disabled: Boolean,
    /** Demo tuning 2026-09-12: LOW start sensitivity so line noise / echo of our own injected
     *  audio doesn't register as caller speech (observed: greeting interrupted 640 ms in). */
    val startOfSpeechSensitivity: String = "START_SENSITIVITY_HIGH",
    val endOfSpeechSensitivity: String = "END_SENSITIVITY_HIGH",
    val prefixPaddingMs: Int = 300,
    val silenceDurationMs: Int = 600,
)

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

/**
 * A client-sent text turn (`clientContent`), used for [LiveSession.sendTextTurn] — the M3
 * greeting kick, since the audio-output session has no other "speak first" mechanism (see
 * `docs/gemini-live.md`). Valid alongside an AUDIO `responseModalities` setup: a text *input*
 * turn producing an audio *output* turn is a normal Live API shape, distinct from
 * `responseModalities` (which only constrains what the model replies with).
 */
@Serializable
data class ClientContentEnvelope(val clientContent: ClientContentPayload)

@Serializable
data class ClientContentPayload(
    val turns: List<ClientTurn>,
    val turnComplete: Boolean = true,
)

@Serializable
data class ClientTurn(val role: String, val parts: List<Part>)

/** mimeType for 16 kHz PCM16 audio we send to the Live API (spec §4.4). */
const val INPUT_AUDIO_MIME_TYPE = "audio/pcm;rate=16000"

/**
 * Client -> server reply to a server `toolCall` (`docs/gemini-tools.md`). [id] and [name] must
 * echo the values from the `toolCall.functionCalls[]` entry being answered; [response] is a
 * free-form JSON object (e.g. `{"result": "...", "sources": [...]}`).
 */
@Serializable
data class ToolResponseEnvelope(val toolResponse: ToolResponsePayload)

@Serializable
data class ToolResponsePayload(val functionResponses: List<FunctionResponse>)

@Serializable
data class FunctionResponse(val id: String, val name: String, val response: JsonObject)
