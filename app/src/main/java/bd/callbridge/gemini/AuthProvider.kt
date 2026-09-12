package bd.callbridge.gemini

/**
 * Supplies the credential used to open a Live API WebSocket. The pilot uses a raw API key
 * (spec §4.4: "ephemeral token minted by the backend (or API key on the phone for the pilot —
 * rotate it)"); a future backend-minted ephemeral token is the production path.
 */
interface AuthProvider {
    /** Value appended as the `key` query parameter (or as an ephemeral token) on the WS URL. */
    suspend fun token(): String
}

/** Pilot auth: the static API key from `local.properties` -> `BuildConfig.GEMINI_API_KEY`. */
class ApiKeyAuth(private val apiKey: String) : AuthProvider {
    override suspend fun token(): String = apiKey
}

/**
 * Stub for the production path: a short-lived token minted by a backend service so the raw API
 * key never ships on-device. Not implemented for the pilot — CallBridge runs standalone on the
 * phone with no backend (spec §4.4 pilot note). Wire this up once a token-minting endpoint
 * exists.
 */
class EphemeralTokenAuth(
    private val mintToken: suspend () -> String,
) : AuthProvider {
    override suspend fun token(): String = mintToken()

    companion object {
        fun notImplemented(): EphemeralTokenAuth = EphemeralTokenAuth {
            throw NotImplementedError(
                "EphemeralTokenAuth requires a backend token-minting endpoint; not built for the pilot."
            )
        }
    }
}
