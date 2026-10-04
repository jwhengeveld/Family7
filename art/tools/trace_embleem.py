import numpy as np, subprocess, re
from PIL import Image, ImageFilter

SCALE = 3
src = Image.open('embleem.png').convert('RGBA')
W, H = src.size
big = src.resize((W*SCALE, H*SCALE), Image.LANCZOS)
a = np.asarray(big).astype(int)
r, g, b, al = a[...,0], a[...,1], a[...,2], a[...,3]
opaque = al > 128
mx = np.maximum(np.maximum(r,g),b); mn = np.minimum(np.minimum(r,g),b)
sat = (mx - mn)
masks = {
    'white': opaque & (mn > 190) & (sat < 45),
    'red':   opaque & (r > g + 50) & (r > b + 50),
    'blue':  opaque & (b > r + 40) & (b > g + 20),
    'green': opaque & (g > r + 30) & (g > b + 30),
}
# Onderliggende lagen iets groter, zodat er geen haarlijntjes tussen de vlakken vallen.
grow = {'green': 5, 'red': 3, 'blue': 3, 'white': 0}

def trace(name, mask):
    img = Image.fromarray(np.where(mask, 0, 255).astype('uint8'))
    if grow[name]: img = img.filter(ImageFilter.MinFilter(grow[name]))
    img.convert('1').save(f'm_{name}.pbm')
    subprocess.run(['potrace', '-b', 'svg', '--flat', '-t', '20', '-a', '1.0', '-O', '0.4',
                    '-u', '10', '-o', f't_{name}.svg', f'm_{name}.pbm'], check=True)
    svg = open(f't_{name}.svg').read()
    d = ' '.join(re.findall(r' d="([^"]+)"', svg))
    tf = re.search(r'<g transform="([^"]+)"', svg).group(1)
    return d, tf

paths = {n: trace(n, m) for n, m in masks.items()}

# Kleuren bemonsteren uit het origineel (zonder schaal).
o = np.asarray(src).astype(int)
def px(x, y): c = o[int(y), int(x)]; return '#%02X%02X%02X' % (c[0], c[1], c[2])
def bbox(mask):
    ys, xs = np.nonzero(mask[::SCALE, ::SCALE]); return xs.min(), ys.min(), xs.max(), ys.max()
def column(mask, x, n=5):
    m = mask[::SCALE, ::SCALE]; ys = np.nonzero(m[:, x])[0]; top, bot = ys.min()+3, ys.max()-3
    return [(i/(n-1), px(x, top + (bot-top)*i/(n-1))) for i in range(n)]

info = {n: bbox(m) for n, m in masks.items()}
print('bbox', info)
# Rood: verticaal verloop, gemeten in een kolom aan de linkerkant.
rx0, ry0, rx1, ry1 = info['red']
red_stops = column(masks['red'], rx0 + (rx1-rx0)//4, 6)
# Blauw: verloop van linksboven (licht) naar rechtsonder (donker), gemeten langs die lijn.
bx0, by0, bx1, by1 = info['blue']
mb = masks['blue'][::SCALE, ::SCALE]
line = []
for i in range(6):
    t = i/5; x = bx0 + (bx1-bx0)*(0.35 + 0.6*t); y = by0 + (by1-by0)*(0.25 + 0.45*t)
    while not mb[int(y), int(x)]: x -= 2
    line.append((t, px(x, y)))
# Groen: radiaal, gemeten van het lichte midden-boven naar de rand.
gx0, gy0, gx1, gy1 = info['green']
mg = masks['green'][::SCALE, ::SCALE]
green_center = (gx0 + (gx1-gx0)*0.52, gy0 + 14)
gstops = []
for i, y in enumerate([gy0+8, gy0+30, gy0+60, gy0+85]):
    x = green_center[0]
    gstops.append(px(x, y))
print('red', red_stops); print('blue', line); print('green', gstops)
import json; json.dump({'paths': paths, 'red': red_stops, 'blue': line, 'green': gstops, 'bbox': {k:[int(v) for v in vv] for k,vv in info.items()}, 'size': [W, H]}, open('trace.json','w'))
