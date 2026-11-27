# Render package (F-7, D-96)

Make a project's video on another device: the phone (or PC) packs **one project with only what its render needs**,
and another phone, a Windows PC or a Linux server renders it — in the app (Open package) or on the command line
(`AutoFlow --render`), for example by an agent such as Muse.

## Make a package

On every export screen — Movie Recap / Music Video **Export**, Dubbing **Export**, Subtitles **Export ▸ Video with
subtitles** — open **📦 Render package** and tap **Make render package**.

- The zip is written to the project's `exports/<project>-render-package.zip`. On a phone a copy goes to
  `Download/AutoFlow/` (it stays after uninstall and shows in Files); **Share** sends it (Telegram, Drive…). The
  desktop shows **Open folder**.
- It holds `project.json`, `source/` (movie, song, video), the artifacts the project points at (current takes,
  current subtitle version, story, storyboard…), `render.json` and `fonts/<font>`.
- It leaves out `exports/`, `inbox/`, every `.cache/`, `project.json` backups and old versions or takes nothing points at.
- `render.json` keeps what the export screen shows but the project does not save: Dubbing / Subtitles look, size,
  lines on screen, line length, text (Original / Burmese / Both), `.wav`, the subtitle version shown, Movie Recap /
  Music Video quality, the pronunciation fixes (Library) the takes were made with, and the app version.
- The Burmese font the render used is inside (`fonts/`), so the video looks the same everywhere.

The zip is an Auto Flow backup of one project (`autoflow-backup.json`, `kind: render-package`), so **Settings ▸
Restore** also opens it.

## Render in the app (phone, Windows, Linux desktop)

**Projects ▸ Open package** → pick the zip. It is unpacked into the projects folder (never over an existing folder:
`Name (restored).autoflow`), its pronunciation fixes missing from the Library are added (your own fixes stay), and
the project opens. Then use Export as usual.

## Render on a Linux server (no window)

Download `autoflow-linux-x64-<sha>` from the CI run (Actions ▸ the run ▸ Artifacts; desktop-smoke job) — it holds
`AutoFlow-linux-x64-<sha>.tar.gz` with its own Java runtime.

```bash
sudo apt install ffmpeg          # ffmpeg on PATH is the only requirement
tar xzf AutoFlow-linux-x64-*.tar.gz
./AutoFlow/bin/AutoFlow --render Film-render-package.zip --out out/
# … PROGRESS 45% …
# OUTPUT out/Film-…mp4
```

```
AutoFlow --render <package.zip | project folder> [--out <folder>] [--quality preview|export] [--home <folder>]
```

- A zip is unpacked into `--out` (default: the zip's folder); a project folder renders in place. The finished video is
  copied into `--out` and printed as `OUTPUT <path>`; every line also goes to `<out>/autoflow-render.log`.
- `--quality`: Movie Recap / Music Video only (`export` = 1080p, default; `preview` = 540p).
- It runs with its own app folder `<out>/.autoflow-render-home` (no settings, keys or Library of yours are used or
  changed); `--home` picks another.
- Exit codes: **0** done · **1** the render failed (the reason is printed as `FAILED …`) · **2** bad arguments, a
  missing file or a zip that is not a package.
- Nothing is sent anywhere: rendering needs no AI key. Voice takes must already be in the package (made on the phone).

On Windows the same command works (`AutoFlow.exe --render …`), but the app has no console window, so read
`autoflow-render.log` in the output folder — or use Open package in the app.

## For an agent (e.g. Muse)

1. Install: `sudo apt install -y ffmpeg && tar xzf AutoFlow-linux-x64-*.tar.gz`.
2. Render: `./AutoFlow/bin/AutoFlow --render <zip> --out <dir>`; wait for the exit code.
3. Exit 0 → send back the file named on the `OUTPUT` line. Exit 1 → send back the `FAILED` line (e.g. a missing
   take or font). Exit 2 → the zip or the arguments are wrong.
