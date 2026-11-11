Debug build for the phone (D-24), signed with the stable key: it **installs over v0.1.3 / v0.1.4** (tap **Update**), no uninstall, the app's data stays.

New (PLAN 1F, D-27):

- **Projects on the phone.** Dashboard → **New Music Video** → type a name → **Create**. There is no folder to choose any more: projects are saved in the app's own storage.
- **Settings → Backup → Back up all projects.** Puts every project into one zip in **Download/AutoFlow** (`AutoFlow-backup-<date>-<time>.zip`). That folder stays when the app is uninstalled. While it runs you see "Backing up… N %" and **Cancel**. When it is done, **Share…** sends the zip to Drive, Gmail, Telegram, Quick Share and so on. (Restoring from a backup comes later, together with the file picker.)

Install:

1. Tap the `autoflow-debug-….apk` file below to download it.
2. Open it from the download notification and tap **Update** (or **Install**). If Android asks, allow "Install unknown apps" for your browser first.
3. Open **Auto Flow**.

Please check:

1. Dashboard → **New Music Video** → name it (Burmese is fine) → **Create**. The Input screen opens.
2. In the sidebar tap **Dashboard**: the project is in the list.
3. **Settings** → scroll to **Backup** → it says "1 project" → **Back up all projects**.
4. When it says "Saved Download/AutoFlow/…zip", open the **Files** app → **Downloads** → **AutoFlow**: the zip should be there.
5. Back in Auto Flow tap **Share…**: the share sheet should open (you don't have to send it).
6. Tell me what you saw, with a screenshot of the Backup card. If something fails, copy the red/blue message text.

If the app ever stops, the next launch shows the error with **Copy report**: send that back as well.

The Android APK bundles a GPL ffmpeg build (see the README).
