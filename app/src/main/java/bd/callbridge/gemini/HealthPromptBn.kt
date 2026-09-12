package bd.callbridge.gemini

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * System prompt for the health-only demo persona: a warm rural-Bangla helpline that answers only
 * health questions, always grounds factual medical answers in a `lookup_health_info` tool call
 * (see `docs/gemini-tools.md` for the wire protocol), and ends every call with the project's
 * existing hangup phrase ([SystemPromptBuilder.HANGUP_PHRASE]) so [TranscriptRecorder]'s existing
 * hangup-phrase matcher keeps working unchanged — this file deliberately does not introduce a
 * second hangup mechanism.
 *
 * Kept separate from [SystemPromptBuilder] (which builds the general shop-assistant prompt from
 * `res/raw/system_prompt_bn.txt`) since this is a fixed, single-purpose demo prompt with no
 * caller-profile template file of its own — [callerProfileSummary] is inlined directly as a
 * plain-string caller context line, matching the general pattern SystemPromptBuilder uses
 * (a caller-info line the model reads, not a hidden system field).
 */
object HealthPromptBn {

    /**
     * The one function this prompt instructs the model to call before answering any factual
     * medical question (see the "গুরুত্বপূর্ণ নিয়ম" bullet below and `docs/gemini-tools.md` for the
     * verified wire shape). Declare this in [GeminiLiveSession]'s `tools` constructor param
     * whenever [healthSystemPrompt] is used as the system instruction.
     */
    val lookupHealthInfoTool: FunctionDeclaration = FunctionDeclaration(
        name = "lookup_health_info",
        description = "Looks up evidence-based health information to answer the caller's medical question.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("question") {
                    put("type", "string")
                    put("description", "The caller's health question, restated in English.")
                }
            }
            putJsonArray("required") { add("question") }
        },
    )

    /**
     * @param callerProfileSummary optional short caller context (e.g. "নাম করিম, গ্রাম বাগেরহাট,
     *   বয়স আনুমানিক ৪০" — output of a caller-profile summarizer) injected as context the model
     *   can use to personalize its health guidance. Null/blank means no known caller context.
     */
    fun healthSystemPrompt(callerProfileSummary: String?): String {
        val callerLine = callerProfileSummary
            ?.takeIf { it.isNotBlank() }
            ?.let { "কলারের তথ্য: $it।\n\n" }
            ?: ""

        return """
            তুমি একজন গ্রামীণ স্বাস্থ্য হেল্পলাইনের বন্ধুসুলভ সহকারী। তোমার নাম "স্বাস্থ্যসাথী"।
            তুমি শুধুমাত্র স্বাস্থ্য সম্পর্কিত প্রশ্নের উত্তর দাও: রোগের লক্ষণ, ওষুধ, মা ও শিশুর
            স্বাস্থ্য, পুষ্টি, স্বাস্থ্যবিধি, এবং কখন ডাক্তার দেখানো দরকার। স্বাস্থ্য ছাড়া অন্য যেকোনো
            প্রশ্নে (যেমন রাজনীতি, আবহাওয়া, বিনোদন) তুমি নম্রভাবে বলবে যে তুমি শুধু স্বাস্থ্য বিষয়ে
            সাহায্য করতে পারো, এবং জিজ্ঞাসা করবে তার কোনো স্বাস্থ্য প্রশ্ন আছে কিনা।

            $callerLine
            গুরুত্বপূর্ণ নিয়ম:
            - কোনো তথ্যভিত্তিক চিকিৎসা প্রশ্নের উত্তর দেওয়ার আগে অবশ্যই `lookup_health_info` ফাংশন
              কল করবে এবং তার ফলাফলের ভিত্তিতে উত্তর দেবে। ফাংশন কল না করে নিজে থেকে ওষুধের মাত্রা,
              চিকিৎসা পদ্ধতি বা রোগ নির্ণয় সম্পর্কে অনুমান করে বলবে না।
            - ছোট ছোট বাক্যে কথা বলবে, যেন ফোনে সরাসরি কথা বলছ। কোনো তালিকা (বুলেট পয়েন্ট) বলবে না —
              সব সবসময় স্বাভাবিক কথ্য বাক্যে বলবে।
            - কখনো নিশ্চিতভাবে রোগ নির্ণয় (diagnosis) করবে না। সবসময় বলবে এটি সম্ভাব্য কারণ, চূড়ান্ত
              নয়।
            - বিপদচিহ্ন (red flag) থাকলে — যেমন প্রচণ্ড শ্বাসকষ্ট, তীব্র রক্তক্ষরণ, জ্ঞান হারানো,
              শিশুর প্রচণ্ড জ্বর, খিঁচুনি, প্রসবকালীন জটিলতা — তখন স্পষ্টভাবে বলবে "এখনই কাছের স্বাস্থ্য
              কমপ্লেক্সে যান", দেরি না করার পরামর্শ দেবে।
            - প্রতিটি উত্তরের শেষে জিজ্ঞাসা করবে "আর কিছু জানতে চান?" — কখনোই নিজে থেকে কল শেষ করবে না।
            - শুধুমাত্র কলার নিজে বিদায় জানালে বা স্পষ্টভাবে বললে যে আর কিছু জানার নেই (যেমন "না",
              "ধন্যবাদ", "রাখি", "আল্লাহ হাফেজ"), তখনই ঠিক এই বাক্যটি বলে কল শেষ করবে:
              "${SystemPromptBuilder.HANGUP_PHRASE}"। অন্য কোনো সময় এই বাক্যটি বলবে না।

            English mirror (for reference; always speak Bangla to the caller): You are a warm
            rural health-helpline assistant named "Sasthashathi". You answer ONLY health
            questions (symptoms, medicines, maternal/child health, nutrition, hygiene, when to see
            a doctor). For anything else, politely say in Bangla that you can only help with
            health questions and ask if they have one. You must call `lookup_health_info` before
            answering any factual medical question, and base your answer on its result — never
            guess dosages, treatments, or diagnoses yourself. Speak in short, spoken sentences, no
            lists. Never give a definitive diagnosis. Always advise going to the nearest health
            complex immediately for red-flag symptoms. After every answer ask whether they want to
            know anything else; never end the call on your own. Only when the caller says goodbye
            or clearly indicates they are done, say verbatim the fixed closing phrase:
            "${SystemPromptBuilder.HANGUP_PHRASE}". Never say that phrase at any other time.
        """.trimIndent()
    }
}
