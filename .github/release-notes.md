Debug build for the phone (D-24). v0.1.1 fixes the crash when tapping **Run all** on Android 15 / 16 (the foreground service type was dropped).

Install:

1. If an older Auto Flow is installed and Android says "App not installed" or "conflicts with an existing package", uninstall the old Auto Flow first (each build is signed with a different key until the stable key is set up), then try again.
2. Tap the `autoflow-debug-….apk` file below to download it.
3. Open it from the download notification. If Android asks, allow "Install unknown apps" for your browser, then go back and tap **Install** (or **Update**).
4. Open **Auto Flow**.

Self-test (PLAN S6, checks that video rendering works on this phone):

1. In the left sidebar tap **Settings**, scroll to the bottom to **About**.
2. Long-press the line **Auto Flow 0.1.1 (…)**. The Self-test screen opens.
3. Tap **Run all** (allow notifications if asked). It takes about 2–10 minutes; the screen may turn off, it keeps running.
4. When it finishes, tap **Copy report** and paste it back into the chat. A screenshot of the frames helps too.

If the app ever stops, the next launch shows the error with **Copy report**: send that back as well.

The Android APK bundles a GPL ffmpeg build (see the README).
