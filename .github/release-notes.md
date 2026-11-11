Debug build for the phone (D-24), signed with the stable key: it **installs over v0.1.3 – v0.1.6** (tap **Update**), no uninstall, the app's data stays.

New (PLAN 1F):

- **The Gemini API key can be saved on the phone.** It is encrypted with a key in the Android Keystore that never leaves the phone (D-25), and it is not copied into Google backups. So **Real AI** mode now works on Android.
- The key box uses a password keyboard: no suggestions, the keyboard does not learn the key.
- Bottom tab names shrink to fit when the phone's font size is large (no more "Storyboa…").
- Backup sizes under 0.1 MB show as B / KB instead of "0.0 MB".

Install:

1. Tap the `autoflow-debug-….apk` file below to download it.
2. Open it from the download notification and tap **Update** (or **Install**). If Android asks, allow "Install unknown apps" for your browser first.
3. Open **Auto Flow**.

You need a Gemini API key. If you don't have one: open https://aistudio.google.com/apikey in the phone's browser → **Create API key** → copy it.

Please check:

1. **More → Settings → AI providers**: turn **off** "Use fake AI providers". The top bar should say "Gemini API ● No key".
2. Paste the key into "Paste a Gemini API key" → **Save**. It should say "Gemini API key: saved ••••" plus the last 4 characters.
3. Tap **Test** (uses 1 AI request). It should say "Key works (gemini-…)".
4. Close Auto Flow completely (swipe it away from the recent apps), open it again → **More → Settings**: the key should still say "saved ••••…".
5. Optional, the first real AI storyboard on the phone (2 AI requests): **Projects** → tap your project (Input opens) → paste a few lines of Burmese lyrics, Duration e.g. `0:30` → **Storyboard ထုတ်မယ်** → **Generate**. When it finishes, the **Storyboard** tab shows the shots.
6. Send screenshots of the AI providers card (after Test) and, if you tried it, the Storyboard. If something fails, copy the red/blue message text.

If the app ever stops, the next launch shows the error with **Copy report**: send that back as well.

The Android APK bundles a GPL ffmpeg build (see the README).
