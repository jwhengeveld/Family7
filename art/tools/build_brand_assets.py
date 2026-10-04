#!/usr/bin/env python3
"""Maakt alle merkafbeeldingen van de apps uit de SVG's in art/.

Android (TV-app en telefoonapp, via de module :brand): VectorDrawables met echte verlopen.
iOS (met --ios <pad naar Family7iOS>): PNG's, gerenderd met headless Chrome,
omdat de CoreSVG-renderer van iOS kleuren binnen verlopen verschuift.

    python3 art/tools/build_brand_assets.py [--ios ../iOS/Family7iOS]
"""
import argparse
import json
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ART = ROOT / "art"
SVG_NS = "{http://www.w3.org/2000/svg}"
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

# Achtergrondkleuren van de apps.
BLUE_TOP = "#0A3F86"
BLUE = "#03326C"
BLUE_DARK = "#031A38"
BLUE_DEEP = "#020D24"


# ---------------------------------------------------------------- SVG lezen

class Art:
    """Een van onze eigen, eenvoudige SVG's: paden, afgeronde rechthoeken, verlopen."""

    def __init__(self, name):
        self.path = ART / name
        root = ET.parse(self.path).getroot()
        self.vb = [float(v) for v in root.get("viewBox").split()]
        self.gradients = {}
        for g in root.iter():
            tag = g.tag.replace(SVG_NS, "")
            if tag in ("linearGradient", "radialGradient"):
                stops = []
                for s in g.findall(f"{SVG_NS}stop"):
                    color = s.get("stop-color")
                    opacity = float(s.get("stop-opacity", "1"))
                    stops.append((float(s.get("offset")), color, opacity))
                self.gradients[g.get("id")] = (tag, dict(g.attrib), stops)
        self.shapes = []
        for el in root:
            tag = el.tag.replace(SVG_NS, "")
            if tag == "path":
                self.shapes.append(("path", el.get("d"), el.get("fill"), el.get("transform")))
            elif tag == "rect":
                x, y, w, h = (float(el.get(k)) for k in ("x", "y", "width", "height"))
                rx = float(el.get("rx", "0"))
                d = (f"M{x + rx},{y}H{x + w - rx}A{rx},{rx} 0 0 1 {x + w},{y + rx}V{y + h - rx}"
                     f"A{rx},{rx} 0 0 1 {x + w - rx},{y + h}H{x + rx}A{rx},{rx} 0 0 1 {x},{y + h - rx}"
                     f"V{y + rx}A{rx},{rx} 0 0 1 {x + rx},{y}Z")
                self.shapes.append(("rect", d, el.get("fill"), None, (x, y, w, h)))

    @property
    def width(self):
        return self.vb[2]

    @property
    def height(self):
        return self.vb[3]


def argb(color, opacity=1.0):
    c = color.lstrip("#").upper()
    if len(c) == 3:
        c = "".join(ch * 2 for ch in c)
    return f"#{round(opacity * 255):02X}{c}" if opacity < 1 else f"#{c}"


def vd_fill(art, fill, bbox=None, indent="        "):
    """De vulling van een pad: een kleur, of een verloop als aapt:attr."""
    m = re.match(r"url\(#(.+)\)", fill or "")
    if not m:
        return f'android:fillColor="{argb(fill or "#000")}"', ""
    kind, attrs, stops = art.gradients[m.group(1)]
    items = "".join(
        f'\n{indent}        <item android:offset="{o:g}" android:color="{argb(c, a)}"/>' for o, c, a in stops
    )
    if kind == "radialGradient":
        head = (f'android:type="radial" android:centerX="{attrs["cx"]}" android:centerY="{attrs["cy"]}" '
                f'android:gradientRadius="{attrs["r"]}"')
    else:
        x1, y1, x2, y2 = (float(attrs.get(k, d)) for k, d in (("x1", 0), ("y1", 0), ("x2", 1), ("y2", 0)))
        if attrs.get("gradientUnits") != "userSpaceOnUse" and bbox:
            bx, by, bw, bh = bbox
            x1, x2 = bx + x1 * bw, bx + x2 * bw
            y1, y2 = by + y1 * bh, by + y2 * bh
        head = (f'android:type="linear" android:startX="{x1:g}" android:startY="{y1:g}" '
                f'android:endX="{x2:g}" android:endY="{y2:g}"')
    body = (f'\n{indent}<aapt:attr name="android:fillColor">\n{indent}    <gradient {head}>{items}'
            f'\n{indent}    </gradient>\n{indent}</aapt:attr>')
    return "", body


def vd_paths(art, only=None, color=None):
    """De vormen van een SVG als VectorDrawable-paden, in de eigen coördinaten van de SVG."""
    out = []
    for index, shape in enumerate(art.shapes):
        if only is not None and index not in only:
            continue
        kind, d, fill, transform = shape[:4]
        bbox = shape[4] if kind == "rect" else None
        attr, body = vd_fill(art, color or fill, bbox)
        path = f'        <path android:pathData="{d}"'
        path += f" {attr}" if attr else ""
        path += f">{body}\n        </path>" if body else "/>"
        if transform:
            tx, ty = re.search(r"translate\(([-\d.]+)[ ,]([-\d.]+)\)", transform).groups()
            s = re.search(r"scale\(([-\d.]+)\)", transform).group(1)
            path = (f'        <group android:translateX="{tx}" android:translateY="{ty}" '
                    f'android:scaleX="{s}" android:scaleY="{s}">\n    {path}\n        </group>')
        out.append(path)
    return "\n".join(out)


def vector(width_dp, height_dp, vw, vh, content, comment):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- {comment}
     Gemaakt door art/tools/build_brand_assets.py; niet met de hand aanpassen. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="{width_dp:g}dp"
    android:height="{height_dp:g}dp"
    android:viewportWidth="{vw:g}"
    android:viewportHeight="{vh:g}">
{content}
</vector>
'''


def placed(art, canvas_w, canvas_h, box_w, box_h, paths, cx=None, cy=None):
    """Een SVG geschaald in een kader en gecentreerd (of op cx, cy) op een canvas."""
    s = min(box_w / art.width, box_h / art.height)
    w, h = art.width * s, art.height * s
    x = (canvas_w - w) / 2 if cx is None else cx - w / 2
    y = (canvas_h - h) / 2 if cy is None else cy - h / 2
    tx = x - art.vb[0] * s
    ty = y - art.vb[1] * s
    return (f'    <group android:translateX="{tx:.3f}" android:translateY="{ty:.3f}" '
            f'android:scaleX="{s:.5f}" android:scaleY="{s:.5f}">\n{paths}\n    </group>')


def background(w, h, stops, start=(0, 0), end=None):
    end = end or (w, h)
    items = "".join(f'\n                <item android:offset="{o:g}" android:color="{c}"/>' for o, c in stops)
    return f'''    <path android:pathData="M0,0h{w}v{h}h-{w}z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="{start[0]}" android:startY="{start[1]}"
                android:endX="{end[0]}" android:endY="{end[1]}">{items}
            </gradient>
        </aapt:attr>
    </path>'''


# Het verste punt van het embleem vanaf zijn midden, in eenheden van de SVG:
# de blauwe punt rechtsboven. Bepaalt hoe groot hij in een rond masker mag.
EMBLEM_RADIUS = 412


def build_android(mark, logo, text):
    # Eén plek voor beide apps: de module :brand.
    modules = [ROOT / "brand/src/main/res/drawable"]
    files = {}

    files["family7_mark.xml"] = vector(
        40 * mark.width / mark.height, 40, mark.width, mark.height,
        f'    <group android:translateX="{-mark.vb[0]:g}" android:translateY="{-mark.vb[1]:g}">\n'
        f'{vd_paths(mark)}\n    </group>',
        "Het Family7-embleem, uit art/family7_mark.svg.")
    files["family7_logo.xml"] = vector(
        40 * logo.width / logo.height, 40, logo.width, logo.height,
        f'    <group android:translateX="{-logo.vb[0]:g}" android:translateY="{-logo.vb[1]:g}">\n'
        f'{vd_paths(logo)}\n    </group>',
        "Het Family7-woordmerk, uit art/family7_logo.svg.")
    files["family7_text.xml"] = vector(
        24 * text.width / text.height, 24, text.width, text.height,
        f'    <group android:translateX="{-text.vb[0]:g}" android:translateY="{-text.vb[1]:g}">\n'
        f'{vd_paths(text)}\n    </group>',
        "De tekst \"Family7\", uit art/family7_text.svg.")

    # Adaptief icoon: alles binnen de veilige cirkel van 66dp (straal 33).
    icon_scale = 31.5 / EMBLEM_RADIUS
    files["ic_launcher_foreground.xml"] = vector(
        108, 108, 108, 108,
        placed(mark, 108, 108, mark.width * icon_scale, mark.height * icon_scale, vd_paths(mark)),
        "Voorgrond van het adaptieve app-icoon: het embleem binnen de veilige zone.")
    # Monochroom (themed icons): alleen de witte 7, het herkenbaarste deel.
    seven = len(mark.shapes) - 1
    files["ic_launcher_monochrome.xml"] = vector(
        108, 108, 108, 108,
        placed(mark, 108, 108, mark.width * icon_scale * 1.15, mark.height * icon_scale * 1.15,
               vd_paths(mark, only={seven}, color="#FFFFFF")),
        "Monochroom app-icoon (Android 13+ themed icons): de 7 van het embleem.")
    files["ic_launcher_background.xml"] = vector(
        108, 108, 108, 108,
        background(108, 108, [(0, BLUE_TOP), (0.55, BLUE), (1, BLUE_DARK)], (0, 0), (108, 108)),
        "Achtergrond van het adaptieve app-icoon.")
    # Systeem-splashscherm (Android 12+): icoon zonder achtergrond, 288dp,
    # moet binnen een cirkel van 192dp passen.
    splash_scale = 88 / EMBLEM_RADIUS
    files["splash_emblem.xml"] = vector(
        288, 288, 288, 288,
        placed(mark, 288, 288, mark.width * splash_scale, mark.height * splash_scale, vd_paths(mark)),
        "Icoon van het systeem-splashscherm: het embleem, passend in de cirkel van 192dp.")

    for directory in modules:
        for name, content in files.items():
            (directory / name).write_text(content)

    # TV-banner (320x180dp) voor het startscherm van Android TV.
    banner = vector(
        320, 180, 320, 180,
        background(320, 180, [(0, BLUE_TOP), (0.55, BLUE_DARK), (1, BLUE_DEEP)]) + "\n" +
        placed(logo, 320, 180, 250, 110, vd_paths(logo)),
        "Android TV-banner: het woordmerk op Family7-blauw.")
    (ROOT / "brand/src/main/res/drawable/family7_tv_banner.xml").write_text(banner)
    print("Android:", ", ".join(sorted(files)), "+ family7_tv_banner.xml")


# --------------------------------------------------------------------- iOS

def chrome_png(svg_path, out, width, height):
    """Rendert een SVG transparant met headless Chrome."""
    with tempfile.TemporaryDirectory() as tmp:
        html = Path(tmp) / "r.html"
        html.write_text(f'<html><body style="margin:0"><img src="file://{svg_path}" '
                        f'style="width:{width}px;height:{height}px;display:block"></body></html>')
        subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                        "--default-background-color=00000000", f"--window-size={width},{height}",
                        f"--screenshot={out}", f"file://{html}"],
                       check=True, capture_output=True)


def imageset(directory, name, svg, base_w):
    target = directory / f"{name}.imageset"
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)
    base_h = round(base_w * svg.height / svg.width)
    images = []
    for scale in (1, 2, 3):
        file = f"{name}@{scale}x.png"
        chrome_png(svg.path, target / file, base_w * scale, base_h * scale)
        images.append({"filename": file, "idiom": "universal", "scale": f"{scale}x"})
    (target / "Contents.json").write_text(json.dumps(
        {"images": images, "info": {"author": "xcode", "version": 1}}, indent=2))


def build_ios(ios_root, mark, logo, text):
    from PIL import Image

    assets = Path(ios_root) / "Family7/Resources/Assets.xcassets"
    imageset(assets, "Family7Logo", logo, 360)
    imageset(assets, "Family7Mark", mark, 165)  # = maat op het opstartscherm
    imageset(assets, "Family7Text", text, 260)

    # App-icoon: het embleem op het blauwe verloop, zonder alfakanaal.
    size = 1024
    with tempfile.TemporaryDirectory() as tmp:
        emblem_w = round(size * 0.72)
        emblem_h = round(emblem_w * mark.height / mark.width)
        png = Path(tmp) / "emblem.png"
        chrome_png(mark.path, png, emblem_w, emblem_h)
        emblem = Image.open(png).convert("RGBA")

    def hex_rgb(c):
        return tuple(int(c.lstrip("#")[i:i + 2], 16) for i in (0, 2, 4))

    top, bottom = hex_rgb(BLUE_TOP), hex_rgb(BLUE_DARK)
    icon = Image.new("RGBA", (size, size))
    for y in range(size):
        t = y / (size - 1)
        row = tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,)
        icon.paste(row, (0, y, size, y + 1))
    icon.alpha_composite(emblem, ((size - emblem_w) // 2, (size - emblem_h) // 2 + 6))
    icon.convert("RGB").save(assets / "AppIcon.appiconset/icon-1024.png")
    print("iOS: Family7Logo, Family7Mark, Family7Text (@1x/2x/3x) en AppIcon")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ios", help="pad naar de Family7iOS-repository")
    args = parser.parse_args()

    mark, logo, text = Art("family7_mark.svg"), Art("family7_logo.svg"), Art("family7_text.svg")
    build_android(mark, logo, text)
    if args.ios:
        build_ios(args.ios, mark, logo, text)


if __name__ == "__main__":
    main()
