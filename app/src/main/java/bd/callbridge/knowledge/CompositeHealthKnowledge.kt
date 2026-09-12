package bd.callbridge.knowledge

import android.util.Log

/**
 * [HealthKnowledge] that prefers [exa], falling back to [openAi] when Exa errors, times out, or
 * returns no usable result (i.e. `provider == "none"`), and finally returns
 * [noInformationAvailable] if both fail. Both underlying providers already enforce their own 6s
 * timeout and never throw, so this class never throws either.
 *
 * Either provider may be `null` (missing API key -> disabled), in which case it's skipped.
 */
class CompositeHealthKnowledge(
    private val exa: HealthKnowledge?,
    private val openAi: HealthKnowledge?,
) : HealthKnowledge {

    override suspend fun lookup(question: String, callerContext: String?): KnowledgeAnswer {
        exa?.let {
            val result = runCatching { it.lookup(question, callerContext) }.getOrNull()
            if (result != null && result.provider != "none") return result
            if (result == null) Log.w(TAG, "Exa provider threw unexpectedly; falling back to OpenAI")
        }

        openAi?.let {
            val result = runCatching { it.lookup(question, callerContext) }.getOrNull()
            if (result != null && result.provider != "none") return result
        }

        return noInformationAvailable()
    }

    companion object {
        private const val TAG = "CompositeHealthKnowledge"

        /**
         * Builds the composite from BuildConfig keys, disabling a provider whose key is blank.
         * Call from wherever the bridge session is assembled (see `docs/gemini-tools.md` wiring
         * notes) — this class has no Android Context dependency itself.
         */
        fun fromKeys(exaApiKey: String, openAiApiKey: String): CompositeHealthKnowledge {
            val exa = if (exaApiKey.isNotBlank()) ExaKnowledge(exaApiKey) else null
            val openAi = if (openAiApiKey.isNotBlank()) OpenAiKnowledge(openAiApiKey) else null
            return CompositeHealthKnowledge(exa, openAi)
        }
    }
}
