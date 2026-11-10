# Spec: Prompt Compiler

## 1. Two-stage storyboard generation
Stage A `analyze(lyricsMy, settings)` → `SongAnalysis`:
```json
{ "themeMy": "...", "arcMy": "...", "sections": [ { "type": "VERSE", "startMs": 0, "endMs": 32000, "lyricsMy": "..." } ],
  "motifsEn": ["rain on glass", "old photograph"], "paletteEn": "...", "charactersMy": [ "..." ] }
```
Stage B `storyboard(analysis, shots[], settings, globalStyle, characters)` → one `ShotSpec` per shot:
```json
{ "shot": 3, "sceneMy": "ကော်ဖီဆိုင် မှန်ပြတင်းနောက်ကနေ...", "lyricsMy": "...",
  "subjectEn": "young Burmese woman", "actionEn": "gazes through rain-streaked café window",
  "settingEn": "night café interior, neon street outside", "cameraEn": "slow push-in, shallow DOF",
  "moodEn": "melancholic", "lightingEn": "warm interior vs cold blue exterior",
  "characters": ["HERO"], "motifEn": "rain on glass", "transitionEn": "cut" }
```
Both calls use structured JSON output. If the model returns invalid JSON, retry once with the error appended; then fail the job (never save partial storyboards).

## 2. PromptIR
```kotlin
data class PromptIR(val shot:Int, val subject:String, val action:String, val setting:String,
  val camera:String, val lighting:String, val mood:String, val style:String /* from GlobalStyle */,
  val characters:List<String> /* expanded descriptions */, val negative:String, val ratio:String, val durationS:Int)
```
Compiler: `compile(spec, globalStyle, characters, ratio, durationMs) : PromptIR` (ratio and shot duration fill `ratio`/`durationS`). Token `{{HERO}}` → character.descriptionEn. Deterministic; no model call.

## 3. Dialects
`VeoFlowDialect.render(ir)`: one or two natural English sentences, order: subject+action, setting, camera, lighting/mood, style; then `Avoid: <negative>`. ≤ 900 characters. No markdown.
`ImageDialect.render(ir)`: same, but drops camera motion verbs (push-in → "medium close-up"), adds "single frame, cinematic still".
Golden tests: `golden/<case>.spec.json` (`{spec, globalStyle, characters, ratio, durationMs}`) + `.veo.txt` + `.image.txt`. Changing a dialect requires updating goldens in the same PR: `UPDATE_GOLDEN=1 ./gradlew :core:desktopTest`, then review the diff.

## 4. Language rule
Model-bound fields end in `En`; human fields end in `My`. The compiler never translates. If a `*En` field is empty, compile fails with a visible error on the shot card.
