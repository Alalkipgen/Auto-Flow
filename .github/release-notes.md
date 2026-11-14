Installs over v0.1.14 — tap **Update**, no data lost.

## Auto Flow v0.2.0 — Studio v2

### New UI (Studio v2)
- Bottom nav: **Home · Projects · Library · Jobs**
- **Home** — Quick Recap wizard (ရုပ်ရှင် အချိန်ကန့်သတ် + Style ရွေး → project အလိုအလျောက် ဆောက်)
- **Library** — Brand Kit, Saved Voice, Music, Font, Preset သိမ်းပြီး project ကို Apply လုပ်
- **Jobs** — AI job list, ETA, Cancel

### Movie Recap improvements
- SubtitleTrack picker (.srt embed track ရွေး)
- Voice & Audio block — SPEAK / RECORD / CLEAN / CLONE tabs + Teleprompter
- Smart Fit chips — EXTEND / NEXT\_RANGE / SPEED / SLOW\_VIDEO / FREEZE
- Preflight check (Export မတိုင်ခင် ပြဿနာ စစ်)
- Publish Kit (platform, format, aspect ratio ရွေး)

### Under the hood
- `AiJob` pipeline + Desktop runner (RPM pacing, cancel, resume)
- `LibraryStore` / `LibraryModel`
- `PreviewGeometry` (drag/pinch/clamp)
- `SmartFitConfig` model
- `QuickRecap` model (stage tracking)
- Feature flags: `studioV2`, `quickRecap`, `libraryScreen`, `jobsTab` on

