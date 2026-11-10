# Spec: Workflow & Approval

## Pipeline steps
INPUT → STORYBOARD → PROMPTS → REFERENCES → FLOW_VIDEO → ASSEMBLY

## Gates (WorkflowRules — pure functions)
- `canGenerateStoryboard(p)`: settings valid, lyrics non-empty, durationMs > 0.
- `canCompilePrompt(shot)`: shot.spec != null && approvals.storyboard.
- `canGenerateRef(shot)`: promptIr != null && approvals.prompt && !isStale(promptIr).
- `canHandoff(shot)`: chosenRef != null && approvals.ref (ref may be user-uploaded).
- `canAccept(shot)`: videos non-empty.
- `canRender(p)`: ≥1 shot with acceptedVideo. Missing shots render as placeholder cards (user confirms).

## Approval flags
Per shot: `storyboard`, `prompt`, `ref`, `video` (booleans) + artifact pointers. "Approve all" sets the flag for every shot whose required artifact exists and is not stale.

## Stale
```
staleSpec     = artifact(spec).inputs.storyboard != currentStoryboard
stalePrompt   = artifact(promptIr).inputs.spec != shot.spec || inputs.globalStyle != globalStyle.hash
staleRef      = artifact(ref).inputs.promptIr != shot.promptIr
staleVideo    = artifact(video).inputs.promptIr != shot.promptIr || inputs.ref != shot.chosenRef
```
Each comparison applies only when the input was recorded: `stale(artifact, key, current) = artifact.inputs[key] != null && artifact.inputs[key] != current`. User-provided artifacts record no inputs and are therefore never stale; they may still be superseded by the user choosing another artifact. A pointer whose sidecar is missing counts as stale.

Stale never blocks viewing or exporting; it blocks *downstream generation* and shows an amber badge. Approvals are not auto-cleared; the badge is the signal.

## Handoff state (per shot)
`NONE → SENT_TO_FLOW → IMPORTED → ACCEPTED`. `SENT_TO_FLOW` set on Copy Prompt / Export Package / Open Flow. `IMPORTED` on attach. `ACCEPTED` when acceptedVideo set. Re-sending from ACCEPTED is allowed (new pass) and keeps the accepted video until a new one is accepted.

## Pass
`DRAFT` (Veo Fast) / `FINAL` (Veo Quality) — label only, user-set; affects shot package text and credit log default.
