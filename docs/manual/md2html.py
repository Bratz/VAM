"""Render USER_MANUAL.md to print-ready HTML for headless-Chrome PDF export.

Usage:  python docs/manual/md2html.py [in.md] [out.html]
Defaults: docs/USER_MANUAL.md -> docs/manual/USER_MANUAL.html

Images stay as relative paths; run Chrome with the HTML's directory as cwd
context (file:// URL) so `manual/img/...` resolves. h2 sections start on a
new printed page; images are capped to page width.
"""
import sys, pathlib, markdown
from PIL import Image

HERE = pathlib.Path(__file__).parent
src = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else HERE.parent / 'USER_MANUAL.md'
out = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else HERE / 'USER_MANUAL.html'

# PNGs embed losslessly and blow the PDF up ~3x past typical mail limits.
# Convert to JPEG (q78, max 1400px wide) into img_jpg/ — regenerated only
# when the source PNG is newer, so rebuilds stay fast.
JPG = HERE / 'img_jpg'
JPG.mkdir(exist_ok=True)
for png in (HERE / 'img').glob('*.png'):
    jpg = JPG / (png.stem + '.jpg')
    if jpg.exists() and jpg.stat().st_mtime >= png.stat().st_mtime:
        continue
    im = Image.open(png).convert('RGB')
    if im.width > 1200:
        im = im.resize((1200, round(im.height * 1200 / im.width)), Image.LANCZOS)
    im.save(jpg, 'JPEG', quality=68, optimize=True)

body = markdown.markdown(
    src.read_text(encoding='utf-8'),
    extensions=['tables', 'toc', 'fenced_code'],
)
# The manual lives one level above this folder; its image paths are
# 'manual/img/x.png'. The HTML lives inside manual/ and uses the JPEGs.
body = body.replace('src="manual/img/', 'src="img_jpg/').replace('.png"', '.jpg"')

CSS = """
@page { size: A4; margin: 18mm 15mm; }
* { box-sizing: border-box; }
body { font-family: Georgia, 'Times New Roman', serif; color: #1a2332; font-size: 10.5pt; line-height: 1.55; max-width: 100%; }
h1 { font-size: 20pt; color: #0f1b2d; border-bottom: 3px solid #c9a227; padding-bottom: 6px; page-break-before: always; }
h1:first-of-type { page-break-before: avoid; }
h2 { font-size: 14pt; color: #0f1b2d; margin-top: 1.6em; page-break-after: avoid; }
h3 { font-size: 11.5pt; color: #33415c; page-break-after: avoid; }
img { max-width: 100%; border: 1px solid #d8dee9; border-radius: 4px; margin: 8px 0; page-break-inside: avoid; }
table { border-collapse: collapse; width: 100%; font-size: 9pt; margin: 10px 0; page-break-inside: avoid; }
th, td { border: 1px solid #cbd5e1; padding: 4px 8px; text-align: left; vertical-align: top; }
th { background: #f1f5f9; font-family: Helvetica, Arial, sans-serif; font-size: 8.5pt; text-transform: uppercase; letter-spacing: .03em; }
code { font-family: Consolas, monospace; font-size: 9pt; background: #f4f4f5; padding: 1px 4px; border-radius: 3px; }
a { color: #1d4ed8; text-decoration: none; }
blockquote { border-left: 3px solid #c9a227; margin-left: 0; padding-left: 12px; color: #475569; }
hr { border: none; border-top: 1px solid #d8dee9; margin: 18px 0; }
li { margin: 3px 0; }
"""

out.write_text(
    f"<!doctype html><html><head><meta charset='utf-8'>"
    f"<title>Aperture — User Manual</title><style>{CSS}</style></head>"
    f"<body>{body}</body></html>",
    encoding='utf-8',
)
print(f"wrote {out}")
