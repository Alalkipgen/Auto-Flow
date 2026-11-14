# Auto Flow — Architecture Decisions

Next decision: **D-53**

## D-52 — Bug fixes + 5A-6 three-pane (2025)

### Phone test results (v0.2.4, Redmi Note 13 Pro, Android 16)

| Test | Result |
|---|---|
| Back button navigation | ✅ |
| Speak All ETA card | ✅ |
| Brand Kit save + apply | ✅ |
| Background image pick | ✅ |
| Render Cancel (notification) | ✅ |
| Save to Gallery | ✅ (Movies/AutoFlow/) |
| SecureStorage (key persist) | ✅ |
| Share-sheet → Inbox | ❌ Bug: auto-navigate fails |
| S5 Gemini TTS | ✅ (gemini-3.8-flash-tts) |
| Backup zip | ✅ (1.58 GB, 3 projects, Download/AutoFlow/) |

### Bugs fixed

**Bug 1 — Share-sheet → Inbox auto-navigate (StudioV2App)**
- Root cause: StudioV2App did not observe SharedInbox.arrivals; only App() (classic shell) had the LaunchedEffect.
- Fix: added LaunchedEffect(arrivals.arrivals) in StudioV2App; on any new share it navigates to Projects/Inbox.

**Bug 2 — Home Back exits without dialog**
- Root cause: v2Back() returns null at Home → backHandler(false) = no intercept → system exits immediately.
- Fix: backHandler(true) always; if v2Back is null, sets showExitDialog = true. ExitDialog shows "Exit / Stay". Platform AppExit interface + Android finishAffinity() / desktop no-op.

### 5A-6 three-pane layout (≥840 dp)

StudioV2Shell now has three branches:
- < 600 dp → bottom nav (unchanged)
- 600–839 dp → nav rail 80 dp (unchanged)
- ≥ 840 dp → compact rail 60 dp (icon-only) + content fills rest

V2NavRailCompact uses emoji icons + purple top-line indicator.
