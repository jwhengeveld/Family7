import json, re
t = json.load(open('trace.json'))
W, H = t['size']
SCALE = 3

def to_original(d, tf):
    # potrace: translate(0,H*3) scale(0.1,-0.1) in 3x-ruimte -> terug naar 1x.
    ty = float(re.search(r'translate\(([-\d.]+),([-\d.]+)\)', tf).group(2))
    sx, sy = [float(v) for v in re.search(r'scale\(([-\d.]+),([-\d.]+)\)', tf).groups()]
    tokens = re.findall(r'[MmLlCcZz]|-?\d+(?:\.\d+)?', d)
    out, cmd, nums = [], None, []
    def flush():
        if cmd is None: return
        if cmd in 'Zz': out.append('Z'); return
        pts = []
        for i in range(0, len(nums), 2):
            x, y = nums[i], nums[i+1]
            if cmd.isupper(): X, Y = x*sx/SCALE, (ty + y*sy)/SCALE
            else:             X, Y = x*sx/SCALE, y*sy/SCALE
            pts.append(f'{X:.2f} {Y:.2f}'.replace('.00', ''))
        out.append(cmd + ' '.join(pts))
    for tok in tokens:
        if re.match(r'[A-Za-z]', tok):
            flush(); cmd, nums = tok, []
        else:
            nums.append(float(tok))
    flush()
    return ''.join(out)

paths = {k: to_original(*v) for k, v in t['paths'].items()}

def stops(pairs):
    return ''.join(f'<stop offset="{o:g}" stop-color="{c}"/>' for o, c in pairs)

red = [(0, '#FF7A80'), (0.1, '#FF2A35'), (0.25, '#F7000F'), (0.55, '#E0000A'), (0.8, '#C80005'), (1, '#AE0000')]
blue = [(0, '#0058FF'), (0.14, '#0030E8'), (0.32, '#0006CC'), (0.55, '#0000B4'), (0.8, '#000094'), (1, '#000070')]
green = [(0.5, '#7AFF00'), (0.6, '#70F902'), (0.73, '#54E20C'), (0.84, '#31C618'), (0.93, '#16B121'), (1, '#009F29')]

svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}">
<title>Family7 embleem</title>
<!-- Vectorversie van het officiële Family7-embleem (family7.nl, "Family7 Embleem_RGB.png"):
     vlakken getraceerd met potrace, verlopen bemonsterd uit het origineel. -->
<defs>
<radialGradient id="green" gradientUnits="userSpaceOnUse" cx="393" cy="314" r="313">{stops(green)}</radialGradient>
<linearGradient id="red" gradientUnits="userSpaceOnUse" x1="0" y1="143" x2="0" y2="631">{stops(red)}</linearGradient>
<linearGradient id="blue" gradientUnits="userSpaceOnUse" x1="505" y1="245" x2="790" y2="385">{stops(blue)}</linearGradient>
</defs>
<path fill="url(#green)" d="{paths['green']}"/>
<path fill="url(#red)" d="{paths['red']}"/>
<path fill="url(#blue)" d="{paths['blue']}"/>
<path fill="#FFFFFF" d="{paths['white']}"/>
</svg>
'''
open('family7_embleem.svg', 'w').write(svg)
print(len(svg), 'bytes')
