import re
emb = open('family7_embleem.svg').read()
defs = re.search(r'<defs>(.*?)</defs>', emb, re.S).group(1)
paths = dict(re.findall(r'<path fill="([^"]+)" d="([^"]+)"/>', emb)[i][::-1] for i in range(4))
green = re.findall(r'<path fill="url\(#green\)" d="([^"]+)"', emb)[0]
red = re.findall(r'<path fill="url\(#red\)" d="([^"]+)"', emb)[0]
blue = re.findall(r'<path fill="url\(#blue\)" d="([^"]+)"', emb)[0]
white = re.findall(r'<path fill="#FFFFFF" d="([^"]+)"', emb)[0]

bounds, text = open('family_text.txt').read().split('\n', 1)
bx, by, bw, bh = map(float, bounds.split())
# Maat en plaats afgeleid van het officiële woordmerk (family7_logo.png op family7.nl):
# de letters lopen van x=-691 tot 219 en staan op een basislijn van y=390.
left, right, baseline = -691, 219, 392
s = (right - left) / bw
tx = left - bx * s

X0 = -800  # linkerrand van de pil
svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="{X0} 0 {774 - X0} 689" width="{774 - X0}" height="689">
<title>Family7</title>
<!-- Family7-woordmerk: het embleem (vectorversie van het officiële embleem van family7.nl)
     met een pil in hetzelfde rood. Letters: Nunito Black (SIL Open Font License), omgezet naar contouren. -->
<defs>{defs}
<linearGradient id="pillShine" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.28"/><stop offset="0.45" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>
</defs>
<path fill="url(#green)" d="{green}"/>
<rect x="{X0}" y="143" width="1110" height="322" rx="150" fill="url(#red)"/>
<rect x="{X0}" y="143" width="1110" height="322" rx="150" fill="url(#pillShine)"/>
<path fill="url(#red)" d="{red}"/>
<path fill="url(#blue)" d="{blue}"/>
<path fill="#FFFFFF" d="{white}"/>
<path fill="#FFFFFF" transform="translate({tx:.2f} {baseline}) scale({s:.5f})" d="{text.strip()}"/>
</svg>
'''
open('family7_logo.svg', 'w').write(svg)
print('ok', len(svg))
