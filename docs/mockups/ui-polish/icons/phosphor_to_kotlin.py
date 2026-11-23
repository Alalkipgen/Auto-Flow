#!/usr/bin/env python3
"""Phosphor SVGs -> one Kotlin file of Compose ImageVectors (no dependency).
Usage: python3 phosphor_to_kotlin.py <kotlin.package> [key ...] > AfIcons.kt
Keys come from MAPPING.md; with no keys every mapped icon is converted."""
import pathlib, re, sys
here = pathlib.Path(__file__).resolve().parent
pkg = sys.argv[1] if len(sys.argv) > 1 else 'autoflow.ui.icons'
rows = re.findall(r'^\| `([a-z0-9]+)` \| `([a-z0-9-]+)` \|', (here / 'MAPPING.md').read_text(encoding='utf-8'), re.M)
want = set(sys.argv[2:])
rows = [r for r in rows if not want or r[0] in want]
if want - {k for k, _ in rows}:
    sys.exit('unknown keys: %s' % sorted(want - {k for k, _ in rows}))

def camel(key):
    return {'checkc': 'CheckCircle', 'folderopen': 'FolderOpen', 'folderminus': 'FolderMinus', 'usersound': 'UserSound', 'textaa': 'TextAa', 'caretdown': 'CaretDown', 'speakerlow': 'SpeakerLow',
            'speakerx': 'SpeakerMuted', 'monitorplay': 'MonitorPlay', 'caretup': 'CaretUp', 'clockccw': 'ClockCounterClockwise', 'clockcw': 'ClockClockwise',
            'cornersout': 'CornersOut', 'cornersin': 'CornersIn', 'x': 'XCircle', 'cc': 'Subtitles', 'chev': 'Chevron', 'vol': 'Volume', 'filmstrip': 'FilmStrip', 'radio': 'RadioButton', 'listsearch': 'ListMagnifyingGlass', 'notepencil': 'NotePencil', 'checksquare': 'CheckSquare', 'caretleft': 'CaretLeft', 'arrowscw': 'ArrowsClockwise', 'drophalf': 'DropHalf', 'textt': 'TextT', 'paintbucket': 'PaintBucket', 'sliders': 'SlidersHorizontal', 'arrowright': 'ArrowRight', 'filevideo': 'FileVideo', 'mic': 'Microphone', 'text': 'FileText'}.get(key, key[:1].upper() + key[1:])

def layers(svg):
    out = []
    for tag, attrs in re.findall(r'<(\w+)\b([^>]*)>', svg):
        if tag == 'svg':
            continue
        if tag != 'path' or 'fill-rule' in attrs:
            sys.exit('unsupported element: <%s%s>' % (tag, attrs[:60]))
        d = re.search(r'\sd="([^"]+)"', attrs).group(1)
        op = re.search(r'opacity="([0-9.]+)"', attrs)
        out.append('"%s" to %sf' % (d, op.group(1) if op else '1'))
    return out

print('// Generated from Phosphor Icons 2.1 (MIT, https://phosphoricons.com) by phosphor_to_kotlin.py. Do not edit.')
print('package %s\n' % pkg)
for imp in ['ui.graphics.Color', 'ui.graphics.SolidColor', 'ui.graphics.vector.ImageVector',
            'ui.graphics.vector.addPathNodes', 'ui.unit.dp']:
    print('import androidx.compose.' + imp)
print('''
private fun ph(name: String, vararg layers: Pair<String, Float>): ImageVector = ImageVector.Builder(
    name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 256f, viewportHeight = 256f,
).apply {
    layers.forEach { (d, alpha) ->
        addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black), fillAlpha = alpha)
    }
}.build()

object AfIcons {''')
for weight, folder in [('Regular', 'regular'), ('Fill', 'fill'), ('Duotone', 'duotone')]:
    print('    object %s {' % weight)
    for key, name in rows:
        svg = (here / 'phosphor' / folder / ('%s.svg' % name)).read_text(encoding='utf-8')
        print('        val %s: ImageVector by lazy { ph("%s.%s", %s) }' % (camel(key), weight, camel(key), ', '.join(layers(svg))))
    print('    }')
print('}')
