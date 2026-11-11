`NotoSansMyanmar-subset.ttf.b64` — base64 of a subset of Noto Sans Myanmar Regular (the font pinned in
`tools/selftest-fixtures.sh`, SHA-256 `fafce4db…cf2fc`): ASCII plus the Burmese letters of the screenshot texts,
made with `fonttools subset --text=… --unicodes=U+0020-007E --layout-features='*' --no-hinting`. Base64 so it can
be committed as text. Copyright 2022 The Noto Project Authors, licensed under the SIL Open Font License 1.1
(https://openfontlicense.org). Used only by `UiFontScreenshotTest`.
