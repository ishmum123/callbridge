package bd.callbridge.util

/**
 * Shared PII-redaction helpers for anything that ends up in logcat. Phone numbers are the
 * recurring case (call metadata, patient profile numbers, transcript participant info) — never
 * log one verbatim. Applied in [bd.callbridge.profile.ProfileSummarizer]; `BridgeSession`
 * (owned by another worker) applies the same helper separately.
 */
object Redact {
    /**
     * Masks a phone number for logging, e.g. "+8801700000784" -> "+88017…784". Keeps enough of
     * the prefix/suffix to be useful for correlating log lines without exposing the full number.
     * Falls back to full masking for short inputs where prefix+suffix would reveal everything.
     */
    fun phone(raw: String?): String {
        if (raw == null) return "null"
        if (raw.isBlank()) return raw
        if (raw.length <= PREFIX_LEN + SUFFIX_LEN) return "*".repeat(raw.length)
        return raw.take(PREFIX_LEN) + "…" + raw.takeLast(SUFFIX_LEN)
    }

    private const val PREFIX_LEN = 6
    private const val SUFFIX_LEN = 3
}
