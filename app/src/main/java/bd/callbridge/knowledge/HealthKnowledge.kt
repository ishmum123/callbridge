package bd.callbridge.knowledge

/**
 * One evidence-backed answer to a caller's health question, used as grounding context that the
 * Gemini Live session's `lookup_health_info` function call responds with (see
 * `docs/gemini-tools.md` and `gemini/HealthPromptBn.kt`).
 *
 * @param answerEn short (a few sentences) plain-English clinical answer, meant to be summarized/
 *   translated into spoken Bangla by the Live model — never spoken verbatim to the caller.
 * @param sources up to 3 URLs (or a short citation string) backing [answerEn]; empty when
 *   [provider] is `"none"`.
 * @param provider which backend produced this answer: `"exa"`, `"openai"`, or `"none"` (both
 *   providers unavailable/disabled/failed).
 */
data class KnowledgeAnswer(
    val answerEn: String,
    val sources: List<String>,
    val provider: String,
)

/** Looks up a health question and returns a grounded, evidence-based answer. Never throws. */
interface HealthKnowledge {
    /**
     * @param question the caller's health question (spoken-language ASR transcript or a
     *   Gemini-summarized English restatement of it).
     * @param callerContext optional extra context (e.g. caller profile summary: age/occupation/
     *   village) to bias the lookup; may be null.
     */
    suspend fun lookup(question: String, callerContext: String?): KnowledgeAnswer
}

/** A [KnowledgeAnswer] used when no provider produced a result. */
fun noInformationAvailable(): KnowledgeAnswer = KnowledgeAnswer(
    answerEn = "No information available. Advise the caller to visit the nearest health complex or clinic for this question.",
    sources = emptyList(),
    provider = "none",
)
