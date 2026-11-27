# Auto Flow — UI concepts

AI-generated concept images for the Auto Flow redesign. They are visual references only, not final specs and not screenshots of working features.
Project names, timecodes, counts and file sizes are examples. Labels are English; Burmese UI text must be checked in real builds.

## Android

| File | Screen | Notes |
|---|---|---|
| 01_Home.png | Home | Quick Recap, Continue editing, Create project (Recap / Music Video / Dubbing PLANNED), Quick tools (Voice & Audio, Subtitles), Running job. Tabs: Home / Projects / Library / Jobs. |
| 02_Recap_Step1_Source.png | Recap · Source (1/5) | Split choice Fewer / Normal / More (existing scene density). New work: scene thumbnails. |
| 03_Recap_Step3_Voice_List.png | Recap · Voice (3/5), all blocks | Per-block status, listen, Fix timing / Re-voice / Generate voice. "Voice 1 remaining" shows the request count. |
| 04_Recap_Step3_Voice_Block.png | Recap · Voice (3/5), one block | Source vs output time, script edit, voice lock, Smart Fit warning, selected take + history. New work: take waveform. |
| 05_Recap_Step4_Style_Layers.png | Recap · Style (4/5), Layers | Move/resize layers on the video preview, layer toggles. Captions always on top; Background locked as base. New work: editing on the real preview. |
| 06_Recap_Step4_Style_Captions.png | Recap · Style (4/5), Captions | Presets (Clean / Bold / Boxed / Outline), size, position, color. Check: presets must map to existing caption settings. |
| 07_Recap_Step5_Export.png | Recap · Export (5/5) | Quality chips, pre-export checks (must be real checks; no ETA/quota promises), exact-frame check. |
| 08_Jobs.png | Jobs | Running / Needs you / Finished. Model busy -> Paused -> Resume remaining. Retry, Play / Share / Save. Badge = number of "Needs you" jobs. |
| 09_MusicVideo_Shot_Editor.png | Music Video · Shot sheet | Storyboard, prompt + Ref A/B, versions, manual Flow handoff (Copy prompt / Open Flow / Import video), Accept. |
| 10_Settings.png | Settings | App language (does not change project text or voices), API keys, pronunciation, export defaults, background jobs, storage, backup, privacy, diagnostics. |

## Desktop

| File | Screen | Notes |
|---|---|---|
| 11_Desktop_Recap_Editor.png | Desktop Recap editor | Block list / preview / inspector. The multi-track timeline shown is a separate feature, not part of the first desktop pass. |

## Not covered yet

Story step, Projects, Library, Music Video shot list and assemble, Quick tools screens, empty/error states. Dubbing is planned as a separate scope.

## Patterns from reference apps

- Studio99: on-video layer handles, layer toggles + sliders, Ratio / Fit / Quality chips, caption style controls, resume remaining work.
- Second app: up-front split choice, per-part listen / retry, progress -> result -> share.
- Not borrowed: tool-tile grids, VIP / ads / account walls, "Method 1/2/3" names, exposed worker counts, extreme speed-up sync, copyright-safety claims.

## Repository copies

The `.webp` files retain the supplied concept numbers and are compressed to a maximum long side of 1280 px. Original PNGs are intentionally not committed. These are references, not build evidence; see `../../specs/ui-v3.md` for the actual contract.
