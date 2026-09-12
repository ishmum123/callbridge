# Gemini Live API tool-calling wire protocol (verified 2026-09-12)

Empirical verification for the health-knowledge tool (`lookup_health_info`), used by
`HealthPromptBn`/`CompositeHealthKnowledge`. Test: `app/src/test/java/bd/callbridge/gemini/LiveToolCallSmokeTest.kt`
(gated on `-PliveSmoke=true` + `GEMINI_API_KEY`, same convention as `GeminiLiveSmokeTest` — see
`docs/gemini-live.md`). Sources: https://ai.google.dev/api/live, https://ai.google.dev/gemini-api/docs/live-tools,
plus a live round trip against `models/gemini-3.1-flash-live-preview`.

## Setup message — declaring tools

Add a top-level `tools` array to the existing `setup` object (see `docs/gemini-live.md` for the
full setup shape `GeminiLiveSession` already sends). One `Tool` object per group of function
declarations; this project only needs one function so far:

```json
{
  "setup": {
    "model": "models/gemini-3.1-flash-live-preview",
    "generationConfig": { "responseModalities": ["AUDIO"] },
    "systemInstruction": { "parts": [{ "text": "..." }] },
    "tools": [
      {
        "functionDeclarations": [
          {
            "name": "lookup_health_info",
            "description": "Looks up evidence-based health information to answer the caller's medical question.",
            "parameters": {
              "type": "object",
              "properties": {
                "question": { "type": "string", "description": "The caller's health question, restated in English." }
              },
              "required": ["question"]
            }
          }
        ]
      }
    ]
  }
}
```

This is the **exact JSON sent in the passing live test run** (see the test's `println` output,
copied verbatim from the test log):
```json
{"setup":{"model":"models/gemini-3.1-flash-live-preview","generationConfig":{"responseModalities":["AUDIO"]},"systemInstruction":{"parts":[{"text":"You MUST call the lookup_health_info function for every medical question before answering, even if you already know the answer. Never answer a medical question without first calling it."}]},"tools":[{"functionDeclarations":[{"name":"lookup_health_info","description":"Looks up evidence-based health information to answer the caller's medical question.","parameters":{"type":"object","properties":{"question":{"type":"string","description":"The caller's health question, restated in English."}},"required":["question"]}}]}]}}
```

**Gotcha #1 — the model won't call the tool without being told to.** A first run with no
`systemInstruction` at all produced no `toolCall` within 20s — the model just planned to answer
directly. Adding an explicit systemInstruction line (*"You MUST call the lookup_health_info
function for every medical question before answering..."*) made it call the tool reliably. In
production, `HealthPromptBn.healthSystemPrompt()` carries the equivalent instruction in Bangla
(and an English mirror) as one of its "গুরুত্বপূর্ণ নিয়ম" (important rules) bullets — don't drop
that bullet when editing the prompt, or tool calls silently stop happening.

`setupComplete` still acks the same way regardless of `tools` being present:
```json
{"setupComplete":{}}
```

## Client -> server: sending the turn that triggers the call

Ordinary `clientContent` text turn (spec-unrelated to tools; documented here since the test uses
it to trigger the call instead of audio):
```json
{"clientContent": {"turns": [{"role": "user", "parts": [{"text": "..."}]}], "turnComplete": true}}
```

## Server -> client: `toolCall`

Arrived as a **binary WS frame** (opcode 0x2), same as every other server message on this
endpoint — see `docs/gemini-live.md`'s "Important wire detail" note; this is not
tool-calling-specific, it's the whole endpoint. Exact JSON captured from the live run:
```json
{"toolCall":{"functionCalls":[{"name":"lookup_health_info","args":{"question":"paracetamol dose for 2 year old"},"id":"fc_4740069056133174"}]}}
```
Fields: `functionCalls[].name` (string, matches the declared function name), `.args` (a JSON
object matching the declared `parameters` schema — here `{"question": "..."}`), `.id` (opaque
string correlation token — **note the field order in the wild is `name`, `args`, `id`**, not
`id`, `name`, `args` as the reference doc's abstract schema lists them; don't assume field order,
parse by key).

No `serverContent` chatter (partial audio, transcript, etc.) preceded the `toolCall` in this run —
it arrived as essentially the first substantive message after `setupComplete`, well under a
second after the `clientContent` turn was sent.

## Client -> server: `toolResponse`

```json
{"toolResponse":{"functionResponses":[{"id":"fc_4740069056133174","name":"lookup_health_info","response":{"result":"Paracetamol for a 2 year old: 10-15 mg/kg per dose, every 4-6 hours, max 4 doses/24h. See a doctor urgently if breathing difficulty, persistent high fever, or lethargy."}}]}}
```
`response` is a free-form JSON object — `{"result": "<string>"}` was accepted and produced a
grounded spoken answer. Echo back the same `id` and `name` from the `toolCall`.

## After `toolResponse`: audio + `turnComplete`

Following the `toolResponse`, ordinary `serverContent` messages resumed exactly like the
non-tool path documented in `docs/gemini-live.md`: `modelTurn.parts[].inlineData` (24kHz PCM16
audio, base64) frames, then a final `{"serverContent": {"turnComplete": true}}`. Both were
observed within the 20s window in the passing run — no unusual latency introduced by the
tool-call round trip itself (the pause is dominated by however long the *client* (our
`HealthKnowledge` lookup) takes to produce a `toolResponse`, not by the Live API).

## `toolCallCancellation` (documented, not exercised live)

Not triggered in this test (would require the caller to interrupt mid-tool-call, out of scope for
this smoke test). Per https://ai.google.dev/api/live:
```json
{"toolCallCancellation": {"ids": ["fc_4740069056133174"]}}
```
Sent server -> client when a previously-issued `toolCall` should no longer be answered (e.g. the
user barged in). A future integration should treat this the same way `interrupted` is already
handled in `GeminiLiveSession.handleServerContent` — drop any in-flight `HealthKnowledge.lookup`
result for the cancelled `id` rather than sending a stale `toolResponse`.

## Wiring steps for whoever wires this into `GeminiLiveSession`/`BridgeSession`

1. **`WireMessages.kt`**: add `tools: List<JsonObject>? = null` (or typed `Tool`/`FunctionDeclaration`
   data classes matching the shapes above) to `SetupConfig`, and add a `ToolCallEnvelope`/
   `ToolResponseEnvelope` pair mirroring `RealtimeInputEnvelope`'s pattern (`{"toolResponse":
   {"functionResponses": [...]}}`).
2. **`GeminiLiveSession.open()`**: accept a `tools: List<...>` constructor/parameter, include it
   in the `SetupConfig` it builds (only when non-empty, so sessions that don't need tools stay
   unchanged).
3. **`GeminiLiveSession.onServerText`/`handleServerContent`-adjacent dispatch**: add a branch for
   `obj["toolCall"]` (parallel to the existing `setupComplete`/`goAway`/`serverContent` branches)
   that parses `functionCalls[]` and emits a new `LiveSessionEvent.ToolCall(id, name, args:
   JsonObject)` (add this case to the `LiveSessionEvent` sealed interface in `LiveSession.kt`).
   Also add `obj["toolCallCancellation"]` handling -> a `LiveSessionEvent.ToolCallCancelled(ids)`.
4. **New public method** on `GeminiLiveSession`/`LiveSession`: `fun sendToolResponse(id: String,
   name: String, response: JsonObject)` that sends the `toolResponse` envelope via the existing
   `send()` helper.
5. **Call-orchestration layer (BridgeSession / wherever `TranscriptRecorder` is collected today)**:
   subscribe to `LiveSessionEvent.ToolCall`, and for `name == "lookup_health_info"` call
   `CompositeHealthKnowledge.lookup(args["question"], callerContextSummary)` (build the composite
   once via `CompositeHealthKnowledge.fromKeys(BuildConfig.EXA_API_KEY, BuildConfig.OPENAI_API_KEY)`
   — see `knowledge/CompositeHealthKnowledge.kt`), then call `sendToolResponse(id, name,
   buildJsonObject { put("result", answer.answerEn) })`. Since both providers already enforce
   their own timeout and never throw, this call can run inline in the event-handling coroutine
   without extra timeout/try-catch wrapping.
6. **`SystemPromptBuilder` vs `HealthPromptBn`**: for the health-only demo, use
   `HealthPromptBn.healthSystemPrompt(callerProfileSummary)` as the `systemInstruction` text
   instead of `SystemPromptBuilder.build(...)` — it already ends with the same
   `SystemPromptBuilder.HANGUP_PHRASE`, so `TranscriptRecorder`'s existing hangup-phrase matcher
   needs no changes.
7. Declare the one function (`lookup_health_info`, schema above) in the `tools` passed to
   `GeminiLiveSession.open()` whenever the health-demo prompt is used.
