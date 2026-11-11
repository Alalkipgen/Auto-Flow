Debug build for the phone (D-24), signed with the stable key: it **installs over v0.1.3 – v0.1.7** (tap **Update**), no uninstall, the app's data stays.

New in v0.1.8:

- **Shots screen** — Shot grid with status badges (Draft / ✓ Prompt / → Flow / Imported / ✓ Accepted / ⚠ Stale), shot detail panel with storyboard spec fields, **Compile Prompt** (no AI call), **Approve Prompt**.
- **References screen** — Copy image prompt → paste in AI Studio / Flow → **Import Image** (PNG/JPG) → set as reference → **Approve**.
- **Flow Handoff screen** — **Copy Prompt** to clipboard (state → Sent to Flow), **Open Flow ↗**, **Import Video** (MP4/MOV), **Accept Video**, pass toggle (Draft / Final), credit log.
- **Inbox screen** — Drop MP4 or PNG/JPG files into `<project>/inbox/`, then **Attach to Shot** from the app. Attached files move to `.cache/inbox-done/`.
- API call counter now says "API calls (includes retries)" so the number is not surprising.

Install:

1. Tap the `autoflow-debug-….apk` file below to download it.
2. Open it from the download notification and tap **Update**. If Android asks, allow "Install unknown apps" for your browser first.
3. Open **Auto Flow**.

Please check:

1. **More → Shots** — tap the project storyboard shots from the last test; confirm the grid shows the shots with badges, and tapping a shot opens the detail panel with the spec fields.
2. **More → Shots** → tap a shot → **Compile Prompt** → should show the prompt text below.
3. **More → References** — should list the same shots; **Copy Image Prompt** should copy something.
4. **More → Flow Handoff** — should list shots; **Copy Prompt** should copy the Veo prompt; **Open Flow ↗** should open the browser.
5. **Inbox** tab → shows the inbox folder path; "Inbox is empty" if no files there.

Send screenshots or the red message text if something fails. If the app stops, the next launch shows "Copy report" — send that back.

The Android APK bundles a GPL ffmpeg build (see the README).
