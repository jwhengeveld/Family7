# Merkafbeeldingen

| Bestand | Wat |
|---|---|
| `family7_mark.svg` | Het Family7-embleem (de 7 in de bol), als vector |
| `family7_logo.svg` | Het woordmerk: rode pil met "Family" en het embleem |
| `family7_text.svg` | Alleen de tekst "Family7", voor het splashscherm |

## Herkomst

- **Embleem**: getraceerd uit het officiële embleem op family7.nl
  (`source/family7_embleem_origineel.png`, "Family7 Embleem_RGB.png", 774×689).
  Elk kleurvlak is apart uitgesneden en met potrace omgezet naar een vloeiend
  pad; de verlopen (radiaal groen, verticaal rood, diagonaal blauw) zijn
  bemonsterd uit de pixels van het origineel. Er bestaat geen officiële SVG.
- **Woordmerk**: opgebouwd naar `source/family7_logo_origineel.png` (het logo
  in de kop van family7.nl). De pil loopt precies van de bovenkant van het rode
  vlak tot de onderkant van het blauwe vlak en gebruikt hetzelfde rode verloop,
  zodat hij naadloos in het embleem overgaat.
- **Letters**: Nunito ExtraBold (SIL Open Font License, zie
  `source/Nunito-OFL.txt`), omgezet naar contouren; de apps hebben het
  lettertype zelf niet nodig.

## Opnieuw maken

```bash
# 1. embleem traceren en opbouwen (vanuit een map met embleem.png en Nunito.ttf)
python3 tools/trace_embleem.py && python3 tools/build_embleem_svg.py
swift tools/text_to_path.swift Nunito.ttf 820 Family > family_text.txt
python3 tools/build_logo_svg.py

# 2. Android-drawables (TV en telefoon) en iOS-afbeeldingen uit deze SVG's
python3 tools/build_brand_assets.py --ios ../../iOS/Family7iOS
```

Let op: renderers verschillen. De CoreSVG-renderer van Apple verschuift kleuren
binnen verlopen; daarom krijgt iOS PNG's die met Chrome gerenderd zijn, en
Android VectorDrawables (die de verlopen wel correct tekenen).
