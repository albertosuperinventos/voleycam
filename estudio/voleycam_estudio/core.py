"""VoleyCam Estudio: lógica sin interfaz (canal, partidos, vídeos, jugadas y montaje con FFmpeg)."""
from __future__ import annotations

import base64
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from datetime import datetime, timezone

DB_URL = "https://voleycam-6d692-default-rtdb.europe-west1.firebasedatabase.app"

# Tipos de jugada y segundos por defecto (antes, después)
TYPES = {
    "jugada": ("⭐", "Jugada", 7, 3),
    "carmen": ("💜", "Jugadora", 7, 3),
    "bloqueo": ("🧱", "Bloqueo", 5, 2),
    "recepcion": ("🛡", "Recepción", 4, 1),
    "gol": ("⚽", "Gol", 12, 10),
    "tarjeta": ("🟨", "Tarjeta", 8, 4),
}
# Botones de cada deporte (en este orden: teclas Z X C V y 1 2 3 4)
SPORT_KINDS = {"volley": ("jugada", "carmen", "bloqueo", "recepcion"),
               "football": ("jugada", "carmen", "gol", "tarjeta")}
# Segundos por defecto del fútbol (las jugadas son más largas)
FOOTBALL_SECS = {"jugada": (12, 5), "carmen": (12, 5), "gol": (12, 10), "tarjeta": (8, 4)}
# Etiquetas extra (no son tipos): gol anulado y jugada larga (inicio-fin)
EXTRA_TAGS = ("anulado", "larga")

SUMMARIES = [
    # clave, título, filtro de etiquetas, lleva portada y patrocinadores
    ("partido", "Resumen del partido", lambda tags: tags != {"carmen"}, True),
    ("carmen", "Resumen de la jugadora", lambda tags: "carmen" in tags, False),
    ("bloqueo", "Resumen de bloqueos", lambda tags: "bloqueo" in tags, False),
    ("recepcion", "Resumen de recepciones", lambda tags: "recepcion" in tags, False),
    ("gol", "Resumen de goles", lambda tags: "gol" in tags, False),
    ("tarjeta", "Resumen de tarjetas", lambda tags: "tarjeta" in tags, False),
]
SPORT_SUMMARIES = {"volley": ("partido", "carmen", "bloqueo", "recepcion"),
                   "football": ("partido", "carmen", "gol", "tarjeta")}


def sport_of(m) -> str:
    return "football" if getattr(m, "sport", "") == "football" else "volley"


def base_dir() -> str:
    """Carpeta del programa (también dentro del .exe de PyInstaller)."""
    if getattr(sys, "frozen", False):
        return getattr(sys, "_MEIPASS", os.path.dirname(sys.executable))
    return os.path.dirname(os.path.abspath(__file__))


def tool(name: str) -> str:
    """ffmpeg / ffprobe: el que va con el programa o el del sistema."""
    exe = name + (".exe" if os.name == "nt" else "")
    for d in (os.path.join(base_dir(), "ffmpeg"), base_dir(), os.path.dirname(base_dir())):
        p = os.path.join(d, exe)
        if os.path.isfile(p):
            return p
    return shutil.which(name) or name


def _run(args, **kw):
    flags = 0x08000000 if os.name == "nt" else 0  # CREATE_NO_WINDOW
    return subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace", creationflags=flags, **kw)


# ----------------------------------------------------------------- Canal

def fetch_channel(key: str, timeout: int = 30) -> dict:
    import requests
    key = key.strip()
    if len(key) < 16:
        raise ValueError("La clave del canal no es válida")
    r = requests.get(f"{DB_URL}/c/{key}.json", timeout=timeout)
    r.raise_for_status()
    data = r.json()
    if not data:
        raise ValueError("Ese canal está vacío. ¿Es la clave correcta?")
    return data


def save_marks(key: str, path: str, marks: list, removed: list = (), timeout: int = 30) -> int:
    """Guarda en el canal las jugadas cambiadas, añadidas o quitadas. Devuelve cuántos cambios."""
    import requests
    import time
    upd = {k: None for k in removed}
    for mk in marks:
        if not mk.dirty:
            continue
        if not mk.key:
            mk.key = f"e{int(time.time() * 1000)}{len(upd)}"
        upd[mk.key] = mk.to_json()
    if not upd:
        return 0
    r = requests.patch(f"{DB_URL}/c/{key.strip()}/{path}.json", data=json.dumps(upd), timeout=timeout)
    r.raise_for_status()
    for mk in marks:
        mk.dirty = False
    return len(upd)


def create_match(key: str, rec: dict, timeout: int = 30) -> str:
    """Crea un partido en el historial del canal. Devuelve su clave."""
    import requests
    r = requests.post(f"{DB_URL}/c/{key.strip()}/matches.json", data=json.dumps(rec), timeout=timeout)
    r.raise_for_status()
    return (r.json() or {}).get("name", "")


CATEGORY_WORDS = {"junior", "júnior", "juvenil", "cadete", "infantil", "alevin", "alevín", "benjamin", "benjamín", "senior", "sénior",
                  "fem", "fem.", "femenino", "masc", "masc.", "masculino", "liga", "oro", "plata", "division", "división", "grupo"}


def teams_from_title(title: str) -> tuple:
    """('INMUV FUENTES', 'MAIRENA V.C.') a partir de '26/27 JUNIOR Fem. INMUV FUENTES vs MAIRENA V.C.'."""
    t = re.sub(r"\s+", " ", title or "").strip()
    m = re.split(r"\s+(?:vs\.?|v\.?s\.?|contra|-|–)\s+", t, maxsplit=1, flags=re.I)
    if len(m) < 2:
        return ("LOCAL", "VISITANTE")
    home, away = m[0], m[1]
    words = [w for w in home.split(" ") if not re.fullmatch(r"\d{2,4}[/-]\d{2,4}|\d+[ªºa]?", w) and w.lower() not in CATEGORY_WORDS]
    home = " ".join(words) or m[0]
    away = re.split(r"\s+[|·(\[]", away)[0].strip()
    return (home.strip(" .-|") or "LOCAL", away.strip(" -|") or "VISITANTE")


def short_name(name: str) -> str:
    """Nombre corto para el marcador: la palabra más significativa (sin C.V., V.C., CD…)."""
    ws = [w for w in re.split(r"\s+", name.upper()) if w and not re.fullmatch(r"(C\.?V\.?|V\.?C\.?|C\.?D\.?|CD|CV|VC|CLUB|VOLEY|VOLEIBOL|DE|EL|LA)", w)]
    if not ws:
        return name.upper()[:12]
    return max(ws, key=len)[:12] if len(ws) > 2 else ws[-1][:12] if len(ws) == 2 else ws[0][:12]


def _vals(x):
    if isinstance(x, list):
        return [v for v in x if v is not None]
    if isinstance(x, dict):
        return list(x.values())
    return []


@dataclass
class Team:
    name: str
    short: str
    color: str = "#1e6bff"
    logo: str = ""


@dataclass
class Mark:
    ts: int            # hora del servidor, ms
    label: str
    tags: set
    enabled: bool = True
    before: float | None = None   # segundos propios de esta jugada (si se han cambiado)
    after: float | None = None
    key: str = ""                 # clave en el canal (para guardar cambios)
    dirty: bool = False           # cambiada o añadida en Estudio, pendiente de guardar

    @property
    def kind(self) -> str:
        """Cómo se guarda en el canal: '' (jugada), 'carmen', 'bloqueo+carmen', 'jugada+carmen'…"""
        t = set(self.tags) or {"jugada"}
        if t == {"jugada"}:
            return ""
        order = ["jugada", "carmen", "bloqueo", "recepcion", "gol", "tarjeta", "anulado", "larga"]
        return "+".join(x for x in order if x in t)

    def to_json(self) -> dict:
        d = {"ts": self.ts, "label": self.label}
        if self.kind:
            d["kind"] = self.kind
        if self.before is not None:
            d["before"] = round(float(self.before), 1)
        if self.after is not None:
            d["after"] = round(float(self.after), 1)
        return d

    @property
    def main(self) -> str:
        for t in ("gol", "tarjeta", "bloqueo", "recepcion", "carmen"):
            if t in self.tags:
                return t
        return "jugada"

    @property
    def icon(self) -> str:
        s = "".join(TYPES[t][0] for t in ("jugada", "carmen", "bloqueo", "recepcion", "gol", "tarjeta")
                    if (t == "jugada" and (not self.tags or "jugada" in self.tags)) or (t != "jugada" and t in self.tags))
        if "anulado" in self.tags:
            s += "✖"
        if "larga" in self.tags:
            s = "⏱" + s
        return s


def mark_window(mk: Mark, sec: dict) -> tuple:
    """(antes, después) que se aplican a una jugada."""
    tags = (mk.tags or {"jugada"}) & TYPES.keys() or {"jugada"}
    b = max(sec.get(k, (7, 3))[0] for k in tags)
    a = max(sec.get(k, (7, 3))[1] for k in tags)
    return (mk.before if mk.before is not None else b, mk.after if mk.after is not None else a)


def mark_from(d: dict, key: str = "") -> Mark:
    tags = set(d.get("tags") or [])
    k = (d.get("kind") or "").strip()
    if k:
        tags |= set(k.split("+"))
    def num(x):
        try:
            return float(x) if x is not None and x != "" else None
        except (TypeError, ValueError):
            return None
    return Mark(int(d.get("ts") or 0), d.get("label") or "Jugada", tags, before=num(d.get("before")), after=num(d.get("after")),
                key=str(key))


def _items(x):
    """(clave, valor) de una lista o un objeto del canal."""
    if isinstance(x, list):
        return [(str(i), v) for i, v in enumerate(x) if v is not None]
    if isinstance(x, dict):
        return [(str(k), v) for k, v in x.items() if v is not None]
    return []


@dataclass
class Match:
    title: str
    ts: int
    sport: str
    home: Team
    away: Team
    sets: tuple
    score: tuple
    history: list
    finished: bool
    live_start: int
    marks: list
    info: dict = field(default_factory=dict)
    path: str = "marks"   # dónde están sus jugadas dentro del canal
    removed: list = field(default_factory=list)   # claves de jugadas quitadas, pendientes de borrar en el canal
    yt: str = ""          # id del vídeo de YouTube de ese directo (si se guardó)
    pending: dict | None = None   # partido nuevo creado en Estudio, aún no guardado en el canal
    segs: list = field(default_factory=list)   # tramos (sets): {"key", "name", "start", "end"} en ms
    segs_dirty: bool = False
    sport_dirty: bool = False


def _team(d: dict, teams: dict, fb: str) -> Team:
    d = d or {}
    t = teams.get(d.get("id") or "", {}) if d.get("id") else {}
    return Team(d.get("name") or t.get("name") or fb, d.get("short") or t.get("short") or fb,
                d.get("color") or t.get("color") or "#1e6bff", t.get("logo") or "")


def matches_from(ch: dict) -> list:
    teams = ch.get("teams") or {}
    out = []
    live = ch.get("live") or {}
    cur_marks = [mark_from(m, k) for k, m in _items(ch.get("marks"))]
    status = ch.get("status") or {}
    if live:
        h = teams.get(live.get("homeId") or "", {})
        a = teams.get(live.get("awayId") or "", {})
        out.append(Match(
            "Partido actual", int(datetime.now().timestamp() * 1000), live.get("sport", "volley"),
            Team(h.get("name", "LOCAL"), h.get("short", "LOCAL"), h.get("color", "#1e6bff"), h.get("logo", "")),
            Team(a.get("name", "VISITANTE"), a.get("short", "VISITANTE"), a.get("color", "#ff3b3b"), a.get("logo", "")),
            ((live.get("sets") or {}).get("h", 0), (live.get("sets") or {}).get("a", 0)),
            ((live.get("score") or {}).get("h", 0), (live.get("score") or {}).get("a", 0)),
            [(x.get("h", 0), x.get("a", 0)) for x in _vals(live.get("history"))],
            bool(live.get("finished")), int(status.get("liveStartTs") or 0), cur_marks, live.get("info") or {}, "marks",
            yt=str(status.get("ytVideoId") or ""), segs=segs_from(ch.get("segs"))))
    for mid, m in sorted(_items(ch.get("matches")), key=lambda km: -(km[1].get("ts") or 0)):
        home, away = _team(m.get("home"), teams, "LOCAL"), _team(m.get("away"), teams, "VISITANTE")
        date = datetime.fromtimestamp((m.get("ts") or 0) / 1000).strftime("%d/%m/%Y")
        sets = m.get("sets") or {}
        out.append(Match(
            f"{home.short} {sets.get('h', 0)}-{sets.get('a', 0)} {away.short} · {date}", int(m.get("ts") or 0),
            m.get("sport", "volley"), home, away, (sets.get("h", 0), sets.get("a", 0)),
            ((m.get("score") or {}).get("h", 0), (m.get("score") or {}).get("a", 0)),
            [(x.get("h", 0), x.get("a", 0)) for x in _vals(m.get("history"))],
            bool(m.get("finished")), int(m.get("liveStart") or 0), [mark_from(x, k) for k, x in _items(m.get("marks"))],
            m.get("info") or {}, f"matches/{mid}/marks", yt=str(m.get("ytVideoId") or ""), segs=segs_from(m.get("segs"))))
    return out


def save_match_fields(key: str, marks_path: str, fields: dict, timeout: int = 30):
    """Cambia campos de un partido del historial (p. ej. el deporte). marks_path = 'matches/ID/marks'."""
    import requests
    if not marks_path.startswith("matches/"):
        return
    base = marks_path[:-len("/marks")]
    r = requests.patch(f"{DB_URL}/c/{key.strip()}/{base}.json", data=json.dumps(fields), timeout=timeout)
    r.raise_for_status()


def segs_from(x) -> list:
    out = []
    for k, d in _items(x):
        try:
            out.append({"key": k, "name": str(d.get("name") or "Tramo"), "start": int(d.get("start") or 0), "end": int(d.get("end") or 0)})
        except (AttributeError, ValueError, TypeError):
            pass
    return sorted(out, key=lambda g: g["start"])


def segs_path(marks_path: str) -> str:
    """'matches/ID/marks' -> 'matches/ID/segs'; 'marks' -> 'segs'."""
    return marks_path[:-5] + "segs" if marks_path.endswith("marks") else marks_path + "/segs"


def save_segs(key: str, marks_path: str, segs: list, timeout: int = 30):
    """Guarda los tramos (sets) del partido en el canal (se reemplazan todos)."""
    import requests
    data = {g["key"]: {"name": g["name"], "start": g["start"], "end": g["end"]} for g in segs} or None
    r = requests.put(f"{DB_URL}/c/{key.strip()}/{segs_path(marks_path)}.json", data=json.dumps(data), timeout=timeout)
    r.raise_for_status()


def clips_range(seg: dict, videos: list) -> list:
    """El tramo completo, sin cortes (un trozo por vídeo si ocupa más de uno)."""
    out = []
    for i, v in enumerate(videos):
        if not v.start_ms:
            continue
        a, b = max(seg["start"], v.start_ms), min(seg["end"], v.end_ms)
        if b > a:
            out.append(Clip(i, (a - v.start_ms) / 1000, (b - v.start_ms) / 1000, []))
    return out


def sponsors_from(ch: dict) -> list:
    return [s for s in _vals(ch.get("sponsors")) if s.get("logo") or s.get("full")]


def player_name(ch: dict) -> str:
    return (((ch.get("live") or {}).get("hl") or {}).get("name") or "Carmen").strip() or "Carmen"


def hl_seconds(ch: dict) -> tuple:
    hl = (ch.get("live") or {}).get("hl") or {}
    return int(hl.get("before", 7) or 7), int(hl.get("after", 3) or 3)


# ----------------------------------------------------------------- Vídeos

NAME_RE = re.compile(r"VoleyCam_(\d{8})_(\d{6})")


@dataclass
class Video:
    path: str
    duration: float          # segundos
    start_ms: int            # hora de inicio (ms, hora del reloj)
    source: str              # nombre | fecha interna | directo | manual | ?
    width: int = 1920
    height: int = 1080

    @property
    def end_ms(self) -> int:
        return self.start_ms + int(self.duration * 1000)


def probe(path: str) -> dict:
    r = _run([tool("ffprobe"), "-v", "error", "-print_format", "json", "-show_format", "-show_streams", path])
    if r.returncode != 0:
        raise RuntimeError(f"No se puede leer el vídeo: {os.path.basename(path)}")
    return json.loads(r.stdout or "{}")


def load_video(path: str, live_start: int = 0) -> Video:
    info = probe(path)
    fmt = info.get("format") or {}
    dur = float(fmt.get("duration") or 0)
    w, h = 1920, 1080
    for s in info.get("streams") or []:
        if s.get("codec_type") == "video":
            w, h = int(s.get("width") or w), int(s.get("height") or h)
            rot = int(((s.get("tags") or {}).get("rotate")) or 0)
            for sd in s.get("side_data_list") or []:
                if "rotation" in sd:
                    rot = int(sd["rotation"])
            if abs(rot) in (90, 270):
                w, h = h, w
            break
    m = NAME_RE.search(os.path.basename(path))
    if m:
        st = datetime.strptime(m.group(1) + m.group(2), "%Y%m%d%H%M%S").timestamp()
        return Video(path, dur, int(st * 1000), "nombre", w, h)
    ct = (fmt.get("tags") or {}).get("creation_time")
    if ct and live_start <= 0:
        try:
            end = datetime.strptime(ct[:19], "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc).timestamp()
            return Video(path, dur, int((end - dur) * 1000), "fecha interna", w, h)
        except ValueError:
            pass
    if live_start > 0:
        return Video(path, dur, live_start, "directo", w, h)
    return Video(path, dur, 0, "?", w, h)


# ----------------------------------------------------------------- Tramos

@dataclass
class Clip:
    video: int       # índice en la lista de vídeos
    start: float     # segundos dentro del vídeo
    end: float
    marks: list


def clips_for(marks: list, videos: list, sec: dict, filt=None) -> list:
    """Tramos ordenados y sin solaparse. sec: tipo -> (antes, después)."""
    raw = []
    for mk in marks:
        if not mk.enabled or (filt and not filt(mk.tags or {"jugada"})):
            continue
        for i, v in enumerate(videos):
            if v.start_ms and v.start_ms <= mk.ts <= v.end_ms:
                t = (mk.ts - v.start_ms) / 1000
                bb, aa = mark_window(mk, sec)
                raw.append(Clip(i, max(0.0, t - bb), min(v.duration, t + aa), [mk]))
                break
    raw.sort(key=lambda c: (c.video, c.start))
    out = []
    for c in raw:
        if out and out[-1].video == c.video and c.start <= out[-1].end + 0.3:
            out[-1].end = max(out[-1].end, c.end)
            out[-1].marks += c.marks
        else:
            out.append(c)
    return out


# ----------------------------------------------------------------- Imágenes (portada y patrocinadores)

def _font(size: int, bold=True):
    from PIL import ImageFont
    names = ["BarlowCondensed-Black.ttf"] if bold else ["BarlowCondensed-Bold.ttf", "BarlowCondensed-Black.ttf"]
    for n in names:
        p = os.path.join(base_dir(), "fonts", n)
        if os.path.isfile(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default(size)


def _img_from_data(data: str):
    from PIL import Image
    if not data:
        return None
    try:
        raw = base64.b64decode(data.split("base64,", 1)[-1])
        return Image.open(io.BytesIO(raw)).convert("RGBA")
    except Exception:
        return None


def _fit(im, w, h):
    k = min(w / im.width, h / im.height)
    return im.resize((max(1, int(im.width * k)), max(1, int(im.height * k))))


INK = (11, 18, 32)
ACCENT = (198, 244, 50)


def _text(d, xy, s, font, fill, anchor="mm", shadow=True):
    if shadow:
        d.text((xy[0] + 3, xy[1] + 4), s, font=font, fill=(0, 0, 0), anchor=anchor)
    d.text(xy, s, font=font, fill=fill, anchor=anchor)


def _fit_font(d, s, size, maxw, bold=True):
    f = _font(size, bold)
    while size > 12 and d.textlength(s, font=f) > maxw:
        size -= 2
        f = _font(size, bold)
    return f


def render_intro(m: Match, subtitle: str, W: int, H: int, with_title=True):
    """Portada: tabla de sets (o resultado) y subtítulo. Medidas pensadas para 1920x1080."""
    from PIL import Image, ImageDraw
    k = W / 1920
    im = Image.new("RGB", (W, H), INK)
    d = ImageDraw.Draw(im)
    if m.sport == "volley" and m.history:
        n = len(m.history)
        colw, setsw, logow = 140 * k, 180 * k, 130 * k
        namew = min(W - 180 * k - logow - n * colw - setsw, 640 * k)
        tw = logow + namew + n * colw + setsw
        left = (W - tw) / 2
        y = 380 * k
        x = left + logow + namew
        for i in range(n):
            _text(d, (x + colw / 2, y + 36 * k), f"SET {i + 1}", _font(int(36 * k), False), (200, 205, 215))
            x += colw
        _text(d, (x + setsw / 2, y + 36 * k), "SETS", _font(int(36 * k), False), ACCENT)
        y += 72 * k
        rowh = 156 * k
        win = "h" if m.sets[0] > m.sets[1] else "a" if m.sets[1] > m.sets[0] else ""
        for t, side in ((m.home, "h"), (m.away, "a")):
            isw = win == side
            d.rounded_rectangle((left, y, left + tw, y + rowh - 10 * k), 14 * k, fill=(40, 52, 20) if isw else (27, 38, 60))
            d.rectangle((left, y, left + 12 * k, y + rowh - 10 * k), fill=t.color)
            lg = _img_from_data(t.logo)
            if lg:
                lg = _fit(lg, int(logow - 30 * k), int(rowh - 40 * k))
                im.paste(lg, (int(left + 22 * k + (logow - 30 * k - lg.width) / 2), int(y + (rowh - 10 * k - lg.height) / 2)), lg)
            f = _fit_font(d, t.name.upper(), int(54 * k), namew - 30 * k)
            _text(d, (left + logow + 14 * k, y + (rowh - 10 * k) / 2), t.name.upper(), f, ACCENT if isw else (255, 255, 255), "lm")
            x = left + logow + namew
            for h, a in m.history:
                mine, other = (h, a) if side == "h" else (a, h)
                _text(d, (x + colw / 2, y + (rowh - 10 * k) / 2), str(mine), _font(int(72 * k)),
                      (255, 255, 255) if mine > other else (140, 146, 160))
                x += colw
            d.rounded_rectangle((x + 12 * k, y + 16 * k, x + setsw - 12 * k, y + rowh - 26 * k), 12 * k,
                                fill=ACCENT if isw else (255, 255, 255))
            _text(d, (x + setsw / 2, y + (rowh - 10 * k) / 2), str(m.sets[0] if side == "h" else m.sets[1]),
                  _font(int(84 * k)), INK, shadow=False)
            y += rowh
    else:
        cy = 330 * k
        for t, x in ((m.home, W / 2 - 500 * k), (m.away, W / 2 + 500 * k)):
            lg = _img_from_data(t.logo)
            if lg:
                lg = _fit(lg, int(300 * k), int(300 * k))
                im.paste(lg, (int(x - lg.width / 2), int(cy)), lg)
            else:
                d.ellipse((x - 120 * k, cy + 30 * k, x + 120 * k, cy + 270 * k), fill=t.color)
            _text(d, (x, cy + 360 * k), t.name.upper(), _fit_font(d, t.name.upper(), int(52 * k), 600 * k), (255, 255, 255))
        res = f"{m.sets[0]} - {m.sets[1]}" if m.sport == "volley" else f"{m.score[0]} - {m.score[1]}"
        _text(d, (W / 2, cy + 150 * k), res, _font(int(180 * k)), (255, 255, 255))
    if subtitle:
        _text(d, (W / 2, H - 100 * k), subtitle, _fit_font(d, subtitle, int(44 * k), W - 200 * k, False), (220, 224, 232))
    if with_title:
        im.paste(render_title(W, H), (0, 0), render_title(W, H))
    return im


def render_title(W: int, H: int):
    from PIL import Image, ImageDraw, ImageFilter
    k = W / 1920
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    f = _font(int(160 * k))
    sh = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(sh).text((W / 2, 160 * k + 8 * k), "MEJORES JUGADAS", font=f, fill=(0, 0, 0, 230), anchor="mm")
    im.alpha_composite(sh.filter(ImageFilter.GaussianBlur(10 * k)))
    ImageDraw.Draw(im).text((W / 2, 160 * k), "MEJORES JUGADAS", font=f, fill=ACCENT + (255,), anchor="mm")
    return im


def render_goal(W: int, H: int, annulled=False):
    """Rótulo transparente «¡GOL!» (o «GOL ANULADO») para poner encima del vídeo."""
    from PIL import Image, ImageDraw, ImageFilter
    k = W / 1920
    txt = "GOL ANULADO" if annulled else "¡GOL!"
    col = (230, 60, 60) if annulled else ACCENT
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    f = _font(int((150 if annulled else 220) * k))
    y = H - 230 * k
    sh = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(sh).text((W / 2, y + 10 * k), txt, font=f, fill=(0, 0, 0, 235), anchor="mm")
    im.alpha_composite(sh.filter(ImageFilter.GaussianBlur(12 * k)))
    ImageDraw.Draw(im).text((W / 2, y), txt, font=f, fill=col + (255,), anchor="mm")
    return im


def goal_overlays(clips: list, videos: list, sec: dict, work: str, W: int, H: int) -> dict:
    """{índice de tramo: [(png, desde, hasta)]}: el rótulo sale 3,5 s desde el momento del gol."""
    out, pngs = {}, {}
    for i, c in enumerate(clips):
        v = videos[c.video]
        for mk in c.marks:
            if "gol" not in (mk.tags or ()):
                continue
            ann = "anulado" in mk.tags
            if ann not in pngs:
                pngs[ann] = os.path.join(work, f"gol_{int(ann)}.png")
                render_goal(W, H, ann).save(pngs[ann])
            t = (mk.ts - v.start_ms) / 1000 - c.start
            if "larga" in mk.tags:   # jugada larga: el gol está al final
                b, a = mark_window(mk, sec)
                t = t + a - 3.5
            t = max(0.0, min(t, (c.end - c.start) - 1.0))
            out.setdefault(i, []).append((pngs[ann], t, t + 3.5))
    return out


def render_sponsor(sp: dict, W: int, H: int):
    from PIL import Image, ImageDraw
    k = W / 1920
    im = Image.new("RGB", (W, H), INK)
    full = _img_from_data(sp.get("full") or "")
    if full:
        f = _fit(full, W, H)
        im.paste(f, ((W - f.width) // 2, (H - f.height) // 2), f)
        return im
    d = ImageDraw.Draw(im)
    text = (sp.get("text") or "").strip()
    cw, ch = 1140 * k, (600 if text else 690) * k
    top = (H - ch - (135 * k if text else 0)) / 2
    card = (W / 2 - cw / 2, top, W / 2 + cw / 2, top + ch)
    d.rounded_rectangle(card, 27 * k, fill=(255, 255, 255))
    lg = _img_from_data(sp.get("logo") or "")
    if lg:
        lg = _fit(lg, int(cw - 120 * k), int(ch - 108 * k))
        im.paste(lg, (int(W / 2 - lg.width / 2), int(card[1] + (ch - lg.height) / 2)), lg)
    if text:
        _text(d, (W / 2, card[3] + 80 * k), text, _fit_font(d, text, int(60 * k), W - 240 * k), (255, 255, 255))
    return im


def subtitle_for(m: Match) -> str:
    info = m.info or {}
    try:
        date = datetime.strptime(info.get("when", "")[:16], "%Y-%m-%dT%H:%M").strftime("%d/%m/%Y")
    except ValueError:
        date = datetime.fromtimestamp(m.ts / 1000).strftime("%d/%m/%Y") if m.ts else ""
    return "  ·  ".join(x for x in (date, info.get("comp", ""), info.get("cat", "")) if x)


# ----------------------------------------------------------------- Montaje

@dataclass
class Options:
    fade: float = 0.6
    slide_secs: float = 3.0
    crf: int = 20
    preset: str = "veryfast"
    music: str = ""
    music_vol: float = 0.8
    venue_vol: float = 0.3
    width: int = 1920
    height: int = 1080
    fps: int = 30


def ffmpeg_major() -> int:
    r = _run([tool("ffmpeg"), "-version"])
    m = re.search(r"ffmpeg version n?(\d+)\.", r.stdout or "")
    # Las compilaciones "master" se llaman "N-12345-g…" (sin número de versión): son modernas
    return int(m.group(1)) if m else 99


def build_job(clips: list, videos: list, out: str, opt: Options, intro=None, sponsors=(), workdir: str = "", overlays=None) -> tuple:
    """Devuelve (argumentos de ffmpeg, duración total en segundos). intro = (fondo.png, título.png)."""
    W, H, F = opt.width, opt.height, opt.fade
    args = [tool("ffmpeg"), "-hide_banner", "-y", "-nostdin"]
    items = []   # (tipo, duración)
    n_in = 0
    norm_v = f"scale={W}:{H}:force_original_aspect_ratio=decrease,pad={W}:{H}:(ow-iw)/2:(oh-ih)/2,setsar=1,fps={opt.fps},format=yuv420p"
    norm_a = "aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo"
    parts = []
    vlab, alab = [], []

    def add_still(png, secs, title_png=None):
        nonlocal n_in
        args.extend(["-loop", "1", "-framerate", str(opt.fps), "-t", f"{secs:.3f}", "-i", png])
        vi = n_in; n_in += 1
        if title_png:
            args.extend(["-loop", "1", "-framerate", str(opt.fps), "-t", f"{secs:.3f}", "-i", title_png])
            ti = n_in; n_in += 1
        args.extend(["-f", "lavfi", "-t", f"{secs:.3f}", "-i", "anullsrc=r=48000:cl=stereo"])
        ai = n_in; n_in += 1
        k = len(vlab)
        if title_png:
            parts.append(f"[{vi}:v]{norm_v}[bg{k}];[{ti}:v]scale={W}:{H},format=rgba,fade=in:st=0:d=0.9:alpha=1[tt{k}];"
                         f"[bg{k}][tt{k}]overlay=0:0:shortest=1,format=yuv420p[v{k}]")
        else:
            parts.append(f"[{vi}:v]{norm_v}[v{k}]")
        parts.append(f"[{ai}:a]{norm_a}[a{k}]")
        vlab.append(f"v{k}"); alab.append(f"a{k}"); items.append(secs)

    if intro:
        add_still(intro[0], opt.slide_secs, intro[1])
    for ci, c in enumerate(clips):
        d = max(0.5, c.end - c.start)
        args.extend(["-ss", f"{c.start:.3f}", "-t", f"{d:.3f}", "-i", videos[c.video].path])
        k = len(vlab)
        src = n_in
        n_in += 1
        ovs = (overlays or {}).get(ci) or []
        if not ovs:
            parts.append(f"[{src}:v]{norm_v},setpts=PTS-STARTPTS[v{k}]")
        else:
            parts.append(f"[{src}:v]{norm_v},setpts=PTS-STARTPTS[c{k}o0]")
            for j, (png, t0, t1) in enumerate(ovs):
                args.extend(["-loop", "1", "-framerate", str(opt.fps), "-t", f"{d:.3f}", "-i", png])
                oi = n_in; n_in += 1
                outl = f"v{k}" if j == len(ovs) - 1 else f"c{k}o{j + 1}"
                parts.append(f"[{oi}:v]scale={W}:{H},format=rgba,fade=in:st={t0:.2f}:d=0.3:alpha=1,fade=out:st={max(t0, t1 - 0.4):.2f}:d=0.4:alpha=1[g{k}_{j}];"
                             f"[c{k}o{j}][g{k}_{j}]overlay=0:0:enable='between(t,{t0:.2f},{t1:.2f})':shortest=1,format=yuv420p[{outl}]")
        parts.append(f"[{src}:a]{norm_a},asetpts=PTS-STARTPTS[a{k}]")
        vlab.append(f"v{k}"); alab.append(f"a{k}"); items.append(d)
    for png in sponsors:
        add_still(png, opt.slide_secs)

    # Fundido encadenado: imagen con xfade y sonido con acrossfade
    cur_v, cur_a = vlab[0], alab[0]
    total = items[0]
    for k in range(1, len(vlab)):
        f = min(F, items[k] / 2.5, items[k - 1] / 2.5) if F > 0 else 0
        if f >= 0.1:
            parts.append(f"[{cur_v}][{vlab[k]}]xfade=transition=fade:duration={f:.3f}:offset={total - f:.3f}[xv{k}]")
            parts.append(f"[{cur_a}][{alab[k]}]acrossfade=d={f:.3f}[xa{k}]")
            total += items[k] - f
        else:
            parts.append(f"[{cur_v}][{vlab[k]}]concat=n=2:v=1:a=0[xv{k}]")
            parts.append(f"[{cur_a}][{alab[k]}]concat=n=2:v=0:a=1[xa{k}]")
            total += items[k]
        cur_v, cur_a = f"xv{k}", f"xa{k}"
    if opt.music and os.path.isfile(opt.music):
        args.extend(["-stream_loop", "-1", "-i", opt.music])
        mi = n_in; n_in += 1
        fo = max(0.0, total - 3)
        parts.append(f"[{mi}:a]{norm_a},atrim=0:{total:.3f},volume={opt.music_vol:.2f},afade=t=out:st={fo:.3f}:d=3[mus]")
        parts.append(f"[{cur_a}]volume={opt.venue_vol:.2f}[ven]")
        parts.append("[ven][mus]amix=inputs=2:duration=first:normalize=0[aout]")
        cur_a = "aout"
    graph = ";\n".join(parts)
    gpath = os.path.join(workdir or tempfile.gettempdir(), "filtro.txt")
    with open(gpath, "w", encoding="utf-8") as fh:
        fh.write(graph)
    if ffmpeg_major() >= 7:
        args.extend(["-/filter_complex", gpath])
    else:
        args.extend(["-filter_complex_script", gpath])
    args.extend(["-map", f"[{cur_v}]", "-map", f"[{cur_a}]",
                 "-c:v", "libx264", "-preset", opt.preset, "-crf", str(opt.crf), "-pix_fmt", "yuv420p",
                 "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart",
                 "-progress", "pipe:1", "-nostats", out])
    return args, total


def run_job(args: list, total: float, on_progress=None, should_stop=None) -> tuple:
    """Ejecuta ffmpeg. Devuelve (ok, últimas líneas de error)."""
    flags = 0x08000000 if os.name == "nt" else 0
    p = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                         encoding="utf-8", errors="replace", creationflags=flags)
    err_tail = []
    import threading

    def read_err():
        for line in p.stderr:
            err_tail.append(line.rstrip())
            if len(err_tail) > 40:
                del err_tail[0]
    th = threading.Thread(target=read_err, daemon=True)
    th.start()
    for line in p.stdout:
        if should_stop and should_stop():
            p.kill()
            break
        if line.startswith("out_time_us=") or line.startswith("out_time_ms="):
            try:
                us = int(line.split("=", 1)[1])
                if on_progress and total > 0:
                    on_progress(min(100, int(us / 1e6 / total * 100)))
            except ValueError:
                pass
    p.wait()
    th.join(timeout=2)
    return p.returncode == 0, "\n".join(err_tail[-12:])


def make_summary(kind: str, match: Match, clips: list, videos: list, sponsors: list, out: str, opt: Options,
                 with_slides: bool, on_progress=None, should_stop=None, sec: dict | None = None) -> tuple:
    work = tempfile.mkdtemp(prefix="voleycam_")
    try:
        intro, spons = None, []
        if with_slides:
            bg, tt = os.path.join(work, "portada.png"), os.path.join(work, "titulo.png")
            render_intro(match, subtitle_for(match), opt.width, opt.height, with_title=False).save(bg)
            render_title(opt.width, opt.height).save(tt)
            intro = (bg, tt)
            for i, sp in enumerate(sponsors):
                pth = os.path.join(work, f"patro_{i}.png")
                render_sponsor(sp, opt.width, opt.height).save(pth)
                spons.append(pth)
        ovs = goal_overlays(clips, videos, sec or {}, work, opt.width, opt.height) if kind != "completo" else {}
        args, total = build_job(clips, videos, out, opt, intro, spons, work, ovs)
        ok, err = run_job(args, total, on_progress, should_stop)
        if not ok and "Unrecognized option" in err and "filter_complex" in err:
            # La otra forma de pasar el filtro (cambia según la versión de ffmpeg)
            alt = {"-/filter_complex": "-filter_complex_script", "-filter_complex_script": "-/filter_complex"}
            args = [alt.get(a, a) for a in args]
            ok, err = run_job(args, total, on_progress, should_stop)
        return ok, err
    finally:
        shutil.rmtree(work, ignore_errors=True)


# ----------------------------------------------------------------- Descargar de YouTube (yt-dlp)

def _user_bin() -> str:
    """Carpeta del usuario donde vive yt-dlp (se puede actualizar sin permisos de administrador)."""
    base = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~/.local/share")
    d = os.path.join(base, "VoleyCamEstudio", "bin")
    os.makedirs(d, exist_ok=True)
    return d


def _bundled(exe: str) -> str:
    for d in (os.path.join(base_dir(), "yt"), base_dir(), os.path.dirname(base_dir())):
        p = os.path.join(d, exe)
        if os.path.isfile(p):
            return p
    return ""


def ytdlp_path() -> str:
    """yt-dlp: copia del usuario (actualizable); la primera vez se copia la que va con el programa."""
    exe = "yt-dlp.exe" if os.name == "nt" else "yt-dlp"
    mine = os.path.join(_user_bin(), exe)
    if not os.path.isfile(mine):
        src = _bundled(exe)
        if src:
            shutil.copyfile(src, mine)
        else:
            return shutil.which("yt-dlp") or ""
    return mine


def deno_path() -> str:
    exe = "deno.exe" if os.name == "nt" else "deno"
    return _bundled(exe) or shutil.which("deno") or ""


def _run_to_file(args, timeout: int) -> tuple:
    """Ejecuta sin tuberías (en Windows, yt-dlp -U deja un proceso hijo que las mantiene abiertas). (código, texto)."""
    flags = 0x08000000 if os.name == "nt" else 0
    with tempfile.TemporaryFile("w+", encoding="utf-8", errors="replace") as f:
        p = subprocess.Popen(args, stdout=f, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, creationflags=flags)
        try:
            rc = p.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            p.kill()
            rc = -1
        f.seek(0)
        return rc, f.read()


def ytdlp_update() -> str:
    """Actualiza yt-dlp (YouTube cambia a menudo). Devuelve el texto de resultado."""
    y = ytdlp_path()
    if not y:
        raise RuntimeError("No se encuentra yt-dlp")
    rc, out = _run_to_file([y, "-U"], 180)
    if rc == -1:
        raise RuntimeError("El descargador tardó demasiado en actualizarse. Prueba otra vez más tarde.")
    lines = [x for x in out.strip().splitlines() if x.strip()]
    return lines[-1] if lines else ("Actualizado" if rc == 0 else "No se pudo actualizar")


YT_PROGRESS = re.compile(r"\[download\]\s+([\d.]+)%")
YT_ID = re.compile(r"(?:v=|youtu\.be/|/live/|/shorts/|/embed/)([A-Za-z0-9_-]{11})")
FILE_ID = re.compile(r"\[([A-Za-z0-9_-]{11})\](?:\.f\d+)?\.\w+$")


def youtube_id(url: str) -> str:
    m = YT_ID.search(url or "")
    if m:
        return m.group(1)
    u = (url or "").strip()
    return u if re.fullmatch(r"[A-Za-z0-9_-]{11}", u) else ""


def clean_youtube_url(url: str) -> str:
    """https://www.youtube.com/live/ID?feature=shared -> https://www.youtube.com/watch?v=ID"""
    vid = youtube_id(url)
    return f"https://www.youtube.com/watch?v={vid}" if vid else (url or "").strip()


def _ytdlp_base() -> list:
    y = ytdlp_path()
    if not y:
        raise RuntimeError("No se encuentra yt-dlp")
    args = [y]
    ff = tool("ffmpeg")
    if os.path.isfile(ff):
        args += ["--ffmpeg-location", os.path.dirname(ff)]
    dn = deno_path()
    if dn:
        args += ["--js-runtimes", f"deno:{dn}"]
    return args


def _parse_meta(line: str, meta: dict) -> bool:
    if line.startswith("VCFILE "):
        meta["file"] = line[7:].strip()
    elif line.startswith("VCID "):
        meta["id"] = line[5:].strip()
    elif line.startswith("VCTITLE "):
        meta["title"] = line[8:].strip()
    elif line.startswith("VCTS "):
        x = (line[5:].split() or [""])[0]   # release_timestamp = cuándo empezó el directo
        if x.isdigit():
            meta["start_ms"] = int(x) * 1000
    else:
        return False
    return True


META_PRINTS = ["--print", "VCTS %(release_timestamp)s %(timestamp)s %(was_live)s", "--print", "VCID %(id)s",
               "--print", "VCTITLE %(title)s"]


def youtube_info(vid: str) -> dict:
    """Hora de inicio y título de un vídeo de YouTube, sin descargarlo."""
    args = _ytdlp_base() + ["--skip-download", "--no-playlist"] + META_PRINTS + [f"https://www.youtube.com/watch?v={vid}"]
    rc, out = _run_to_file(args, 120)
    meta = {"id": vid, "title": "", "start_ms": 0}
    for line in out.splitlines():
        _parse_meta(line.strip(), meta)
    return meta


def _streams(path: str) -> set:
    try:
        return {s.get("codec_type") for s in probe(path).get("streams") or []}
    except Exception:
        return set()


def parts_for(folder: str, vid: str) -> tuple:
    """(pista de vídeo, pista de audio) sueltas de un vídeo de YouTube en una carpeta: «… [ID].f137.mp4», «… [ID].f140.m4a»."""
    video = audio = ""
    try:
        names = sorted(os.listdir(folder))
    except OSError:
        return "", ""
    for fn in names:
        if f"[{vid}].f" not in fn or fn.endswith((".part", ".ytdl")):
            continue
        p = os.path.join(folder, fn)
        st = _streams(p)
        if "video" in st and not video:
            video = p
        elif st == {"audio"} and not audio:
            audio = p
    return video, audio


def merge_parts(video: str, audio: str, log=print) -> str:
    """Junta la pista de vídeo y la de audio en un MP4 («… [ID].mp4»). Devuelve la ruta."""
    base = re.sub(r"\.f\d+\.\w+$", "", video)
    out = base + ".mp4"
    if out == video:
        out = base + " (con audio).mp4"
    if not audio:
        return video
    log(f"Juntando vídeo y audio: {os.path.basename(video)} + {os.path.basename(audio)}")
    ff = tool("ffmpeg")
    for extra in ([], ["-c:a", "aac", "-b:a", "160k"]):   # primero sin recodificar; si falla, el audio a AAC
        args = [ff, "-hide_banner", "-y", "-i", video, "-i", audio, "-map", "0:v:0", "-map", "1:a:0", "-c", "copy"] + extra + \
               ["-movflags", "+faststart", out]
        r = _run(args)
        if r.returncode == 0 and os.path.isfile(out):
            for f in (video, audio):
                try:
                    os.remove(f)
                except OSError:
                    pass
            return out
        log("ffmpeg: " + "\n".join((r.stderr or "").strip().splitlines()[-12:]))
    raise RuntimeError("Se descargaron el vídeo y el audio pero no se pudieron juntar (detalles en el registro).")


def log_path() -> str:
    return os.path.normpath(os.path.join(_user_bin(), "..", "ultima_descarga.log"))


def prepare_youtube_file(path: str, log=print) -> dict:
    """Archivo ya descargado de YouTube (lleva [ID] en el nombre): junta el audio si está suelto y pide a YouTube la hora y el título."""
    m = FILE_ID.search(os.path.basename(path))
    if not m:
        return {}
    vid = m.group(1)
    if re.search(r"\.f\d+\.\w+$", path):
        v, a = parts_for(os.path.dirname(path), vid)
        if v and a:
            path = merge_parts(v, a, log)
    meta = youtube_info(vid)
    meta["path"] = path
    return meta


def youtube_download(url: str, out_dir: str, height: int = 1080, on_progress=None, should_stop=None, on_log=None) -> dict:
    """Descarga un vídeo de YouTube como MP4 (preferencia H.264 + AAC hasta `height`).
    Devuelve {"path", "start_ms" (hora real de inicio del directo según YouTube, 0 si no se sabe), "id", "title"}."""
    url = clean_youtube_url(url)
    if not url:
        raise ValueError("Pega el enlace del vídeo")
    os.makedirs(out_dir, exist_ok=True)
    args = _ytdlp_base() + ["--verbose", "--newline", "--progress", "--no-playlist", "--no-mtime", "--windows-filenames",
                            "--trim-filenames", "120", "-f", "bv*+ba/b", "-S", f"res:{int(height)},vcodec:h264,acodec:aac",
                            "--merge-output-format", "mp4", "-o", os.path.join(out_dir, "%(title)s [%(id)s].%(ext)s")]
    args += [x if not x.startswith("VC") else "after_move:" + x for x in META_PRINTS] + ["--print", "after_move:VCFILE %(filepath)s"]
    args.append(url)
    lf = open(log_path(), "w", encoding="utf-8", errors="replace")
    lf.write(" ".join(f'"{a}"' if " " in a else a for a in args) + "\n")

    def log(msg):
        lf.write(msg + "\n")
        lf.flush()
        if on_log:
            on_log(msg.splitlines()[0][:120] if msg else msg)
    flags = 0x08000000 if os.name == "nt" else 0
    p = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, text=True,
                         encoding="utf-8", errors="replace", creationflags=flags)
    meta = {"file": "", "start_ms": 0, "id": youtube_id(url), "title": ""}
    tail, part, last = [], 0, -1
    try:
        for line in p.stdout:
            line = line.rstrip()
            if should_stop and should_stop():
                p.kill()
                break
            m = YT_PROGRESS.search(line)
            if m and not line.startswith("[debug]"):
                pc = float(m.group(1))
                if pc < last - 50:   # empieza la segunda pista (audio)
                    part += 1
                last = pc
                if on_progress:   # vídeo 0-90 %, audio 90-99 %
                    on_progress(int(pc * 0.9) if part == 0 else min(99, 90 + int(pc / 10)))
                continue
            lf.write(line + "\n")
            if _parse_meta(line, meta):
                continue
            if line and not line.startswith("[debug]"):
                tail.append(line)
                del tail[:-15]
                if on_log and ("ERROR" in line or "Merging" in line):
                    on_log(line[:120])
        p.wait()
        if should_stop and should_stop():
            raise RuntimeError("Descarga cancelada")
        final = meta["file"]
        if p.returncode != 0 or not final or not os.path.isfile(final):
            # ¿Falló solo al juntar? Si están las dos pistas, se juntan aquí
            v, a = parts_for(out_dir, meta["id"]) if meta["id"] else ("", "")
            if not (v and a):
                raise RuntimeError("No se pudo descargar:\n" + "\n".join(tail[-8:]))
            log("yt-dlp no pudo juntar las pistas; se juntan con ffmpeg.")
            if on_progress:
                on_progress(99)
            final = merge_parts(v, a, log)
            if not meta["start_ms"] or not meta["title"]:
                info = youtube_info(meta["id"])
                meta["start_ms"] = meta["start_ms"] or info.get("start_ms", 0)
                meta["title"] = meta["title"] or info.get("title", "")
        elif re.search(r"\.f\d+\.\w+$", final):
            v, a = parts_for(os.path.dirname(final), meta["id"])
            if v and a:
                final = merge_parts(v, a, log)
        stem = os.path.splitext(final)[0]
        for ext in (".webm", ".m4a", ".part", ".ytdl"):
            f = stem + ext
            if os.path.isfile(f):
                try:
                    os.remove(f)
                except OSError:
                    pass
    finally:
        lf.close()
    if on_progress:
        on_progress(100)
    return {"path": final, "start_ms": meta["start_ms"], "id": meta["id"], "title": meta["title"]}
