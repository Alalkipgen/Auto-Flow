Debug build for the phone (D-24). v0.1.2 adds the real render queue (PLAN 1F, D-26): renders run one at a time in the background with a progress notification and Cancel, and the Self-test gets a **Render job (WorkManager)** check that renders one 21 s 1080p video (crossfades, a placeholder card, the song and Burmese text) through that queue.

Install:

1. **Back up first if you need anything from the old app.** This build is still signed with a temporary key (the stable key is not set up yet), so Android may say "App not installed" or "conflicts with an existing package". Then uninstall the old Auto Flow first — uninstalling deletes the app's data — and try again.
2. Tap the `autoflow-debug-….apk` file below to download it.
3. Open it from the download notification. If Android asks, allow "Install unknown apps" for your browser, then go back and tap **Install** (or **Update**).
4. Open **Auto Flow**.

Self-test (checks video rendering on this phone):

1. In the left sidebar tap **Settings**, scroll to the bottom to **About**.
2. Long-press the line **Auto Flow 0.1.2 (…)**. The Self-test screen opens.
3. Tap **Run all** (allow notifications if asked). It takes about 2–10 minutes; the screen may turn off, it keeps running.
4. Then tap **Render job (WorkManager)**. Turn the screen off for a while during the render; it should keep going, with a "Rendering Self-test job" notification.
5. When it finishes, tap **Copy report** and paste it back into the chat. A screenshot of the frames helps too.
6. Optional: tap **Render job (WorkManager)** again and press **Cancel** in the notification halfway. The report should say **CANCELLED** and "Half-written … left behind: no".

If the app ever stops, the next launch shows the error with **Copy report**: send that back as well.

The Android APK bundles a GPL ffmpeg build (see the README).
