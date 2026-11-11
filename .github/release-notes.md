Patch release — installs over v0.1.8 (tap **Update**), no data lost.

**Bug fix in v0.1.9:**

- **Shots screen: tapping a shot now opens the detail panel.** In v0.1.8 tapping did nothing because the UI state was not refreshing on selection. Fixed.
- On the phone, the detail panel now replaces the grid (swipe or tap "← Back" to return to the list).
- Same fix applied to References and Flow Handoff screens.

Install: tap the `autoflow-debug-….apk` below → **Update**.

Please re-check:
1. **Shots** tab → tap any shot → detail panel should open with spec fields and **Compile Prompt**
2. If storyboard was already generated, tap **Compile Prompt** → prompt text should appear
3. Send a screenshot of the detail panel

The Android APK bundles a GPL ffmpeg build (see the README).
