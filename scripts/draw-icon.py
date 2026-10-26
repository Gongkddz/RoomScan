"""One geometric brand, rendered for Windows and Android from the same paths."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'assets'
OUT.mkdir(exist_ok=True)
S = 1024
im = Image.new('RGB', (S, S), '#12243B')
d = ImageDraw.Draw(im)
def pts(values): return [(int(x*S/108), int(y*S/108)) for x,y in values]
faces = [('#B8FFF0', [(54,26),(78,40),(54,54),(30,40)]),
         ('#36BFAF', [(30,40),(54,54),(54,82),(30,68)]),
         ('#68E6C5', [(54,54),(78,40),(78,68),(54,82)])]
for color, polygon in faces: d.polygon(pts(polygon), fill=color)
paths = ['M54,26 L78,40 L54,54 L30,40 Z','M30,40 L54,54 L54,82 L30,68 Z','M54,54 L78,40 L78,68 L54,82 Z']
for x,y,r in [(82,27,3),(87,38,1.7),(73,20,1.5)]:
    d.ellipse(((x-r)*S/108,(y-r)*S/108,(x+r)*S/108,(y+r)*S/108),fill='#C1FFF1')
im.save(OUT/'roomscan.png')
im.save(OUT/'roomscan.ico',sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
svg='<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><rect width="108" height="108" rx="23" fill="#12243B"/>'
xml='<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">'
for (color,_),path in zip(faces,paths):
    svg+=f'<path fill="{color}" d="{path}"/>'
    xml+=f'<path android:fillColor="{color}" android:pathData="{path}"/>'
for x,y,r in [(82,27,3),(87,38,1.7),(73,20,1.5)]:
    svg+=f'<circle cx="{x}" cy="{y}" r="{r}" fill="#C1FFF1"/>'
    xml+=f'<path android:fillColor="#C1FFF1" android:pathData="M{x-r},{y} a{r},{r} 0,1 0,{2*r},0 a{r},{r} 0,1 0,{-2*r},0"/>'
(OUT/'roomscan.svg').write_text(svg+'</svg>',encoding='utf-8')
(ROOT/'android/app/src/main/res/drawable/ic_foreground.xml').write_text(xml+'</vector>',encoding='utf-8')
(ROOT/'android/app/src/main/res/values/colors.xml').write_text('<resources><color name="icon_background">#12243B</color></resources>\n',encoding='utf-8')
# Also use the complete icon inside the app header.
dest=ROOT/'android/app/src/main/res/drawable-nodpi'
dest.mkdir(exist_ok=True)
im.resize((192,192),Image.Resampling.LANCZOS).save(dest/'roomscan_logo.png')
