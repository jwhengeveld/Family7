#!/usr/bin/env python3
"""Maakt de verplichte Play-afbeeldingen uit de merkbestanden in art/.

    python3 store/tools/build_store_graphics.py

- store/assets/icon-512.png          app-icoon 512x512 (uit het iOS-icoon van 1024)
- store/assets/feature-1024x500.png  feature graphic
- store/assets/tv-banner-1280x720.png banner voor Android TV

Gerenderd met headless Chrome, net als de iOS-merkbeelden (CoreSVG verschuift
de kleuren van de verlopen in het logo).
"""
import pathlib, subprocess, tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
ART = ROOT / "art"
OUT = ROOT / "store" / "assets"
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

NAVY_DARK = "#031A38"
NAVY = "#03326C"
RED = "#E8000F"


def render(html: str, out: pathlib.Path, width: int, height: int) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        page = pathlib.Path(tmp) / "page.html"
        page.write_text(html, encoding="utf-8")
        subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                        f"--window-size={width},{height}", "--force-device-scale-factor=1",
                        f"--screenshot={out}", f"file://{page}"],
                       check=True, capture_output=True)


def page(width: int, height: int, body: str) -> str:
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>
    html,body{{margin:0;width:{width}px;height:{height}px;overflow:hidden}}
    body{{background:radial-gradient(120% 140% at 15% 0%, {NAVY} 0%, {NAVY_DARK} 70%);
         font-family:-apple-system,"Helvetica Neue",Arial,sans-serif;color:#fff;
         display:flex;align-items:center;position:relative}}
    .bar{{position:absolute;left:0;right:0;bottom:0;height:8px;background:{RED}}}
    </style></head><body>{body}<div class="bar"></div></body></html>"""


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    logo = (ART / "family7_logo.svg").resolve().as_uri()
    # De telefoon-schermafbeelding van het startscherm (store/assets/phone-1.png).
    phone = (OUT / "phone-1.png").resolve().as_uri()

    icon = ROOT / "ios/Family7/Resources/Assets.xcassets/AppIcon.appiconset/icon-1024.png"
    subprocess.run(["sips", "-z", "512", "512", str(icon), "--out", str(OUT / "icon-512.png")],
                   check=True, capture_output=True)

    render(page(1024, 500, f"""
      <div style="padding-left:72px;width:560px">
        <img src="{logo}" style="width:400px;display:block">
        <div style="font-size:34px;font-weight:700;margin-top:36px;line-height:1.25">
          Live tv, de programmagids en alle programma's van Family7+</div>
        <div style="font-size:22px;color:#B0C4DE;margin-top:14px">Op je telefoon, tablet en tv</div>
      </div>
      <img src="{phone}" style="position:absolute;right:70px;top:34px;width:300px;border-radius:26px;
           box-shadow:0 18px 50px rgba(0,0,0,.55);border:6px solid #0B2B54">
    """), OUT / "feature-1024x500.png", 1024, 500)

    render(page(1280, 720, f"""
      <div style="width:100%;text-align:center">
        <img src="{logo}" style="width:760px">
      </div>
    """), OUT / "tv-banner-1280x720.png", 1280, 720)


if __name__ == "__main__":
    main()
