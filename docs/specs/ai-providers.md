# Spec: AI Providers

```kotlin
interface TextProvider  { suspend fun json(prompt: String, schema: JsonSchema, system: String?): AiResult<JsonElement> }
interface ImageProvider { suspend fun image(prompt: String, ratio: Ratio, refImages: List<ByteArray> = emptyList()): AiResult<ByteArray> }
interface TtsProvider   { suspend fun speak(text: String, voice: VoiceRef, lang: String, style: String?): AiResult<ByteArray>
                          val capabilities: TtsCapabilities /* replication, burmese, designedVoices */ }
interface TranscribeProvider { suspend fun transcribe(audio: ByteArray, lang: String, mime: String = "audio/wav", lines: List<String> = emptyList()): AiResult<Transcript> } // lines = known lyrics to align (D-31)

sealed interface AiResult<T> { data class Ok<T>(val value:T, val usage:Usage?):AiResult<T>
  data class RateLimited(val retryAfterMs:Long?):AiResult<Nothing>; data class Failed(val code:String,val msg:String):AiResult<Nothing>
  /** Manual handoff started; the result arrives later as an imported artifact (D-14). */
  data class Deferred(val instruction:String):AiResult<Nothing> }
```
- `Fake*`: deterministic from input hash; configurable delay; produce valid Burmese/English fixture content and solid-colour PNGs with the shot number drawn.
- `Gemini*`: REST via Ktor, API key from SecureStorage, structured output. Per D-15: model chain = the stage's `candidates` list in order (first = default), tried at every stage call; exponential backoff (1s,2s,4s… max 5 tries) on 500/503, and on 429 only when `retryDelay` ≤ 60 s (else next candidate / `RateLimited`); timeout 120 s per attempt; every running call shows a Cancel button (coroutine cancellation → no partial artifact).
- `ManualImageProvider` (default `ImageProvider`, D-14): puts the ImageDialect prompt on the clipboard, optionally opens AI Studio, and returns `AiResult.Deferred("Generate the image, then Import image (file/inbox)")`. The image enters via Flow Handoff → Import Image / Inbox as kind `im`.
- `GeminiImageProvider`: deferred (D-14). The `ImageProvider` interface stays so it can be added later, capability-gated by a quota probe (429 with `limit: 0` → unavailable).
- `UsageCounter`: per-model requests/day persisted in `~/.autoflow/usage.json`; UI shows "today: n requests".
- `config/models.json`:
```json
{ "text":  { "candidates": ["gemini-3.5-flash", "..."] },
  "image": { "provider": "manual", "ratios": ["16:9","9:16","1:1","4:5"] },
  "tts":   { "candidates": ["gemini-3.8-flash-tts", "..."], "burmese": true, "replication": "unavailable" },
  "flow":  { "creditsHint": { "fast": 20, "quality": 100 }, "url": "https://labs.google/fx/tools/flow" } }
```
Values are hints; the app must behave correctly if they are wrong.
- `GeminiTtsProvider` (D-34): `generateContent` with `responseModalities: ["AUDIO"]`, one prebuilt voice
  (`speechConfig.voiceConfig.voice` on Gemini 3.8 TTS with `speech_metadata.style`; `prebuiltVoiceConfig.voiceName`
  on older models), `tts.candidates` tried in order (old `tts.default` when there is no list). The answer is always
  a WAV: 3.8 returns one (RIFF, 24 kHz mono 16-bit); headerless `audio/L16` PCM from older models is wrapped.
- Edge voices (D-86, D-90, D-92): `EdgeTtsProvider` over the free read-aloud WebSocket, no key; voice jobs with an Edge
  lock keep `tts.edge.rpm` (20 a minute) and an HTTP 429 is a rate limit (the job pauses, Resume retries).
- Chatbox AI (F-4, D-93): a second service for **text** (`TextProvider`: Script, Translate, Shorten, Storyboard) and
  **Transcribe** (`TranscribeProvider`), chosen per kind in Settings → AI & keys (`textService` / `transcribeService`,
  default `gemini`). Same providers and prompts, another `JsonWire`: `ChatCompletionsWire` posts OpenAI-compatible
  `{chatbox.baseUrl}/chat/completions` with the Chatbox AI license key as `Authorization: Bearer` (SecureStorage
  `chatbox.licenseKey`), the JSON Schema in the prompt and `response_format: json_object` (sent once more without it
  when refused), audio as `input_audio` (base64 + `wav` / `mp3` / `m4a` …), no temperature. Models are
  `chatbox.text` / `chatbox.transcribe` chains in `config/models.json`. 401 = bad key, 402 / out of points = stop,
  404 or a refused model = next model, 429 = rate limit, 5xx = retry. Chatbox calls are not counted in Gemini's
  daily usage. TTS stays Gemini / Edge.
```json
"chatbox": { "baseUrl": "https://ai.chatboxai.app/v1", "text": ["gemini-2.5-flash", "gpt-5-mini", "deepseek-chat"],
             "transcribe": ["gemini-2.5-flash"], "keyUrl": "https://chatboxai.app/" }
```
