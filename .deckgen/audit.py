"""Geometry/text-fit audit of the actual generated PPTX (zero-install).
Parses each slide's XML, extracts true EMU geometry, and flags:
 - out-of-bounds (with allowance for intentional full-bleed bars)
 - content colliding with the footer band
 - significant overlaps between text/shape boxes
 - text likely to overflow its box (heuristic)
"""
import sys, zipfile, re, xml.etree.ElementTree as ET

PPTX = sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\BHATTACHARYAMrSUBRAT\AVD5\vam-portal\Aperture-ABN-AMRO-Cash-Management.pptx"
EMU = 914400.0
SW, SH = 13.333, 7.5
A = "{http://schemas.openxmlformats.org/drawingml/2006/main}"
P = "{http://schemas.openxmlformats.org/presentationml/2006/main}"

def inch(v): return float(v) / EMU

def shapes_of(root):
    out = []
    for tag in (P + "sp", P + "pic", P + "graphicFrame", P + "cxnSp"):
        for sp in root.iter(tag):
            xfrm = sp.find("." + "/" + A + "xfrm")
            if xfrm is None:
                # search nested spPr/xfrm
                for x in sp.iter(A + "xfrm"):
                    xfrm = x; break
            if xfrm is None:
                continue
            off = xfrm.find(A + "off"); ext = xfrm.find(A + "ext")
            if off is None or ext is None:
                continue
            x, y = inch(off.get("x")), inch(off.get("y"))
            w, h = inch(ext.get("cx")), inch(ext.get("cy"))
            texts, maxsz = [], 0
            for para in sp.iter(A + "p"):
                runs = "".join(t.text or "" for t in para.iter(A + "t"))
                if runs:
                    texts.append(runs)
                for rpr in para.iter(A + "rPr"):
                    if rpr.get("sz"):
                        maxsz = max(maxsz, int(rpr.get("sz")) / 100.0)
            kind = sp.tag.split("}")[1]
            out.append(dict(kind=kind, x=x, y=y, w=w, h=h,
                            text=" / ".join(texts), tlen=sum(len(t) for t in texts),
                            paras=texts, sz=maxsz or 14))
    return out

def overlap(a, b):
    ox = max(0, min(a["x"]+a["w"], b["x"]+b["w"]) - max(a["x"], b["x"]))
    oy = max(0, min(a["y"]+a["h"], b["y"]+b["h"]) - max(a["y"], b["y"]))
    return ox * oy

issues = 0
with zipfile.ZipFile(PPTX) as z:
    slides = sorted([n for n in z.namelist() if re.match(r"ppt/slides/slide\d+\.xml$", n)],
                    key=lambda n: int(re.search(r"(\d+)", n).group(1)))
    for n in slides:
        num = int(re.search(r"slide(\d+)", n).group(1))
        root = ET.fromstring(z.read(n))
        sh = shapes_of(root)
        has_footer = any("Confidential — prepared" in (s["text"] or "") for s in sh)
        for i, s in enumerate(sh):
            tag = f"  [s{num}] {s['kind']} ({s['x']:.2f},{s['y']:.2f} {s['w']:.2f}x{s['h']:.2f}) '{(s['text'] or '')[:42]}'"
            # out of bounds (allow full-bleed bars: x<=0.02 or spans full width, thin h)
            fullbleed = (s["x"] <= 0.03 and s["w"] >= SW - 0.06) or s["h"] <= 0.2 and s["w"] >= SW - 0.06
            if not fullbleed:
                if s["x"] < 0.25 or s["y"] < 0.12 or s["x"]+s["w"] > SW-0.20 or s["y"]+s["h"] > SH-0.12:
                    print("OOB ", tag); issues += 1
            # footer-band collision: non-footer content dipping below y=6.95 when footer exists
            if has_footer and "Confidential — prepared" not in (s["text"] or "") and "APERTURE" not in (s["text"] or ""):
                if s["text"] and s["y"] < 7.05 and s["y"]+s["h"] > 6.98 and s["w"] < SW-0.1:
                    print("FOOT", tag, f" bottom={s['y']+s['h']:.2f}"); issues += 1
            # text-fit heuristic
            if s["text"] and s["kind"] == "sp" and s["w"] > 0.5:
                cpl = max(6, (s["w"]*72.0) / (s["sz"]*0.52))
                lines = sum(max(1, -(-len(p)//int(cpl))) for p in s["paras"]) or 1
                need = lines * s["sz"] * 1.28 / 72.0
                if need > s["h"] + 0.18:
                    print("TXT ", tag, f" need~{need:.2f} > h {s['h']:.2f} (sz{s['sz']:.0f}, {lines}L)"); issues += 1
        # significant overlaps between text-bearing boxes (ignore tiny + intentional layering)
        txt = [s for s in sh if s["text"]]
        for i in range(len(txt)):
            for j in range(i+1, len(txt)):
                a, b = txt[i], txt[j]
                ar = overlap(a, b)
                if ar > 0.18:  # >0.18 sq-in overlap of two text boxes is suspicious
                    print(f"OVLP[s{num}] {ar:.2f}in²  A='{a['text'][:28]}'  B='{b['text'][:28]}'"); issues += 1
print(f"\n=== AUDIT {'CLEAN' if issues==0 else str(issues)+' ISSUE(S)'} over {len(slides)} slides ===")
sys.exit(1 if issues else 0)
