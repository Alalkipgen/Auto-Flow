Big release — installs over v0.1.9 (tap **Update**), no data lost.

## What's new in v0.1.10

### ExoPlayer video preview (Shots screen)
- After you accept a video from Flow Handoff, open that shot in the **Shots** tab → the video now plays in a 16:9 player inside the detail panel
- Use the standard play/pause/seek controls

### Storyboard approve gate fixed
- **Compile Prompt** now requires the storyboard to be approved first
- If not approved you'll see: _"Approve the storyboard first"_ instead of a silently compiled (wrong) prompt

### Android FilePicker + Restore from backup (from PR #67)
- Settings → Backup → **Restore from backup…** → pick a `.zip` made by "Back up all projects"
- Project is extracted back into the projects folder; it appears on the Dashboard on next open

---

## Full end-to-end checklist (please test all steps)

**A. Create project & storyboard**
1. Dashboard → **New project** → give it a name
2. Input tab → paste Burmese lyrics, set duration (m:ss) and clip length → **Generate Storyboard**
3. Storyboard tab → review shots → **Approve storyboard**

**B. Shots / prompts**
4. Shots tab → tap a shot → spec fields visible
5. **Compile Prompt** → prompt text appears
6. **✓ Approve Prompt**
7. Try tapping a shot _before_ approving storyboard → should see "Approve the storyboard first"

**C. References**
8. References tab → **Copy image prompt** for a shot → paste in Gemini / Imagen → download PNG
9. **Import image** → select the PNG → **Approve**

**D. Flow Handoff**
10. Flow Handoff tab → pick a shot with approved prompt + ref → **Copy prompt** → open Flow
11. In Flow: paste prompt, run generation → download the video → bring it back to the phone
12. **Import video** → select the `.mp4` → **Accept**
13. Back on Shots tab → tap that shot → video player should appear and play

**E. Backup & Restore**
14. Settings → Backup → **Back up all projects** → check Download/AutoFlow zip
15. Settings → Backup → **Restore from backup…** → pick the zip → project re-appears on Dashboard

**F. Export** (if time allows)
16. Export tab → **Start export** → check the mp4 in Files / Gallery

Send screenshots or a short screen-record of any step that fails.

The Android APK bundles a GPL ffmpeg build (see the README).
