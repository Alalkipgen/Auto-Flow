# Built-in Burmese fonts (D-49)

Pyidaungsu 2.5.3 (Regular, Bold) and Noto Sans Myanmar (Regular, Bold; SIL Open Font License 1.1, see
`NotoSansMyanmar-OFL.txt`). They are stored as base64 text (`*.ttf.b64`) so they can be committed like any other
file; the `decodeBuiltInFonts` task in `app-android` / `app-desktop` turns them back into `.ttf` files at build time
(Android: assets `fonts/`, desktop: resources `/config/fonts/`).

To add or replace a font: `base64 -w 76 Font.ttf > fonts/Font.ttf.b64` and list it in `BuiltInFonts.FILES`.
