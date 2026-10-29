"""
VoleyCam - Extrae todas las fotos y cromos de la web "Voleibol Tomares" a su calidad original.

Uso (en el PC, con Python 3) — vale un .html o una carpeta guardada con "Página web completa":
  python extraer_cromos.py tomares.html
  python extraer_cromos.py "Voleibol Tomares_files"

  1. Descarga la web como .html (menú del artefacto -> Descargar / Copiar código y guárdalo como tomares.html).
  2. Pon este script en la misma carpeta y ejecuta:
        python extraer_cromos.py tomares.html
  3. Se crea la carpeta "cromos_extraidos" con todas las imágenes, sin recomprimir.

Saca las imágenes metidas dentro del HTML (data:image/...;base64) y también las que estén
enlazadas por URL (si hay conexión). Intenta ponerles el nombre de la jugadora mirando el
texto cercano (alt, title, nombre...). Al final genera "indice.html" para verlas todas de un vistazo.
"""
import base64
import hashlib
import html
import os
import re
import sys
import urllib.request

if len(sys.argv) < 2:
    print("Uso: python extraer_cromos.py tomares.html")
    sys.exit(1)

import shutil
entrada = sys.argv[1]
imagenes_sueltas = []
if os.path.isdir(entrada):
    # Carpeta de "Guardar como -> Página web completa": se leen todos los .html/.js y se copian las imágenes
    textos = []
    for raiz, _, ficheros in os.walk(entrada):
        for fn in ficheros:
            ruta = os.path.join(raiz, fn)
            if fn.lower().endswith((".html", ".htm", ".js", ".css")):
                textos.append(open(ruta, encoding="utf-8", errors="ignore").read())
            elif fn.lower().endswith((".jpg", ".jpeg", ".png", ".webp", ".avif")) and os.path.getsize(ruta) > 20000:
                imagenes_sueltas.append(ruta)
    src = "\n".join(textos)
else:
    src = open(entrada, encoding="utf-8", errors="ignore").read()
out = "cromos_extraidos"
os.makedirs(out, exist_ok=True)

EXT = {"jpeg": "jpg", "jpg": "jpg", "png": "png", "webp": "webp", "gif": "gif", "svg+xml": "svg", "avif": "avif"}


def limpia(nombre):
    nombre = html.unescape(nombre).strip()
    nombre = re.sub(r"[^\w\- áéíóúÁÉÍÓÚñÑ]", "", nombre).strip().replace(" ", "_")
    return nombre[:40]


def nombre_cercano(pos):
    """Busca un nombre (alt, title, name, nombre) en los 600 caracteres alrededor de la imagen."""
    trozo = src[max(0, pos - 600):pos + 300]
    for pat in [r'alt\s*=\s*["\']([^"\']{2,40})["\']', r'title\s*=\s*["\']([^"\']{2,40})["\']',
                r'nombre\s*[:=]\s*["\']([^"\']{2,40})["\']', r'name\s*[:=]\s*["\']([^"\']{2,40})["\']']:
        m = list(re.finditer(pat, trozo, re.I))
        if m:
            return limpia(m[-1].group(1))
    return ""


vistas = set()
guardadas = []
n = 0

# 1) Imágenes incrustadas en base64
for m in re.finditer(r"data:image/([a-zA-Z+]+);base64,([A-Za-z0-9+/=\s]+)", src):
    tipo, datos = m.group(1).lower(), re.sub(r"\s", "", m.group(2))
    try:
        b = base64.b64decode(datos)
    except Exception:
        continue
    h = hashlib.md5(b).hexdigest()
    if h in vistas or len(b) < 2000:   # repetidas o iconos diminutos
        continue
    vistas.add(h)
    n += 1
    base = nombre_cercano(m.start()) or "imagen"
    fn = f"{n:02d}_{base}.{EXT.get(tipo, 'img')}"
    open(os.path.join(out, fn), "wb").write(b)
    guardadas.append(fn)

# 1b) Imágenes que ya venían como archivo (Página web completa)
for ruta in imagenes_sueltas:
    b = open(ruta, "rb").read()
    h = hashlib.md5(b).hexdigest()
    if h in vistas:
        continue
    vistas.add(h)
    n += 1
    fn = f"{n:02d}_{limpia(os.path.splitext(os.path.basename(ruta))[0]) or 'imagen'}{os.path.splitext(ruta)[1].lower()}"
    shutil.copyfile(ruta, os.path.join(out, fn))
    guardadas.append(fn)

# 2) Imágenes enlazadas por URL
for m in re.finditer(r'(https?://[^\s"\'()<>]+?\.(?:jpe?g|png|webp|avif))', src, re.I):
    url = m.group(1)
    try:
        b = urllib.request.urlopen(url, timeout=20).read()
    except Exception as e:
        print("No se pudo bajar", url, "-", e)
        continue
    h = hashlib.md5(b).hexdigest()
    if h in vistas:
        continue
    vistas.add(h)
    n += 1
    base = nombre_cercano(m.start()) or "imagen"
    fn = f"{n:02d}_{base}.{url.rsplit('.', 1)[-1].lower()}"
    open(os.path.join(out, fn), "wb").write(b)
    guardadas.append(fn)

# Índice para revisarlas
try:
    from PIL import Image  # opcional: para mostrar el tamaño de cada imagen
except ImportError:
    Image = None
filas = []
for fn in guardadas:
    tam = ""
    if Image:
        try:
            with Image.open(os.path.join(out, fn)) as im:
                tam = f"{im.width}×{im.height}"
        except Exception:
            pass
    filas.append(f'<figure><img src="{fn}"><figcaption>{fn} {tam}</figcaption></figure>')
open(os.path.join(out, "indice.html"), "w", encoding="utf-8").write(
    "<meta charset='utf-8'><style>body{font-family:sans-serif;background:#222;color:#eee}"
    "figure{display:inline-block;margin:8px;width:220px}img{width:100%}figcaption{font-size:12px}</style>"
    + "".join(filas))

print(f"Listo: {len(guardadas)} imágenes en la carpeta '{out}'. Abre {out}/indice.html para verlas.")
