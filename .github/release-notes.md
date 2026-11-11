Debug build for the phone (D-24), signed with the stable key: it **installs over v0.1.3 – v0.1.5** (tap **Update**), no uninstall, the app's data stays.

New (PLAN 1F):

- **Phone layout.** No more desktop sidebar on the phone. The screen now has a top bar (project name, AI status) and tabs at the bottom: **Projects · Storyboard · Shots · Inbox · More**. **More** holds Input, References, Flow Handoff, Timeline, Export and Settings. A locked tab (🔒) says what to do first when you tap it.
- **Back button** works: from a More screen it goes back to More, from another tab to Projects, from Projects it leaves the app.
- Buttons that do not fit go to the next line instead of squeezing the text.
- **Settings → Tools** now just says "ffmpeg: bundled with the app." (no ffmpeg path, no VLC on the phone).
- Screens marked "This screen is not built yet." (Shots, Inbox, References, Flow Handoff, Timeline, Export) come in the next updates.

Install:

1. Tap the `autoflow-debug-….apk` file below to download it.
2. Open it from the download notification and tap **Update** (or **Install**). If Android asks, allow "Install unknown apps" for your browser first.
3. Open **Auto Flow**.

Please check (the v0.1.5 checklist again, now that the buttons fit):

1. **Projects** tab → **New Music Video** → name it (Burmese is fine) → **Create**. The Input screen opens (the **More** tab is highlighted).
2. Press the phone's **Back** button twice: you are on **Projects** and the project is in the list.
3. **More** → **Settings** → scroll to **Backup** → it says "1 project" → **Back up all projects**.
4. When it says "Saved Download/AutoFlow/…zip", open the **Files** app → **Downloads** → **AutoFlow**: the zip should be there.
5. Back in Auto Flow tap **Share…**: the share sheet should open (you don't have to send it).
6. Send screenshots of the Projects screen, the Input screen and the Backup card. If something fails, copy the red/blue message text.

If the app ever stops, the next launch shows the error with **Copy report**: send that back as well.

The Android APK bundles a GPL ffmpeg build (see the README).
