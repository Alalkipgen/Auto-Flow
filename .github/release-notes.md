Debug build for the phone (D-24), signed with the stable key: if you have v0.1.3 installed, this one **installs over it** (tap **Update**), no uninstall, the app's data stays.

Fix: the Self-test **Render job (WorkManager)** failed on the phone with "Unknown input format: 'lavfi'" (ffmpeg exit 234). The phone's ffmpeg has no `lavfi` input device, so the dark placeholder card and the silence for clips without sound are now made inside the filter graph instead (PLAN 1D/1F).

Install:

1. Tap the `autoflow-debug-….apk` file below to download it.
2. Open it from the download notification and tap **Update** (or **Install**). If Android asks, allow "Install unknown apps" for your browser first.
3. Only if you still have v0.1.2 or older: Android refuses the update ("App not installed" / "conflicts with an existing package"). Back up, uninstall the old Auto Flow, then install this one.
4. Open **Auto Flow**.

Self-test (checks video rendering on this phone):

1. In the left sidebar tap **Settings**, scroll to the bottom to **About**.
2. Long-press the line **Auto Flow 0.1.4 (…)**. The Self-test screen opens.
3. Tap **Render job (WorkManager)** (allow notifications if asked). Turn the screen off for a while during the render; it should keep going, with a "Rendering Self-test job" notification.
4. When it finishes, tap **Copy report** and paste it back into the chat. A screenshot of the frames helps too.
5. Optional: tap **Render job (WorkManager)** again and press **Cancel** in the notification halfway. The report should say **CANCELLED** and "Half-written … left behind: no".

If the app ever stops, the next launch shows the error with **Copy report**: send that back as well.

The Android APK bundles a GPL ffmpeg build (see the README).
