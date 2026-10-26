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
}

SUMMARIES = [
    # clave, título, filtro de etiquetas, lleva portada y patrocinadores
    ("partido", "Resumen del partido", lambda tags: tags != {"carmen"}, True),
    ("carmen", "Resumen de la jugadora", lambda tags: "carmen" in tags, False),
    ("bloqueo", "Resumen de bloqueos", lambda tags: "bloqueo" in tags, False),
    ("recepcion", "Resumen de recepciones", lambda tags: "recepcion" in tags, False),
]


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

    @property
    def main(self) -> str:
        for t in ("bloqueo", "recepcion", "carmen"):
            if t in self.tags:
                return t
        return "jugada"

    @property
    def icon(self) -> str:
        return "".join(TYPES[t][0] for t in ("jugada", "carmen", "bloqueo", "recepcion")
                       if (t == "jugada" and (not self.tags or "jugada" in self.tags)) or (t != "jugada" and t in self.tags))


def mark_window(mk: Mark, sec: dict) -> tuple:
    """(antes, después) que se aplican a una jugada."""
    tags = (mk.tags or {"jugada"}) & TYPES.keys() or {"jugada"}
    b = max(sec.get(k, (7, 3))[0] for k in tags)
    a = max(sec.get(k, (7, 3))[1] for k in tags)
    return (mk.before if mk.before is not None else b, mk.after if mk.after is not None else a)


def mark_from(d: dict) -> Mark:
    tags = set(d.get("tags") or [])
    k = (d.get("kind") or "").strip()
    if k:
        tags |= set(k.split("+"))
    return Mark(int(d.get("ts") or 0), d.get("label") or "Jugada", tags)


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


def _team(d: dict, teams: dict, fb: str) -> Team:
    d = d or {}
    t = teams.get(d.get("id") or "", {}) if d.get("id") else {}
    return Team(d.get("name") or t.get("name") or fb, d.get("short") or t.get("short") or fb,
                d.get("color") or t.get("color") or "#1e6bff", t.get("logo") or "")


def matches_from(ch: dict) -> list:
    teams = ch.get("teams") or {}
    out = []
    live = ch.get("live") or {}
    cur_marks = [mark_from(m) for m in _vals(ch.get("marks"))]
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
            bool(live.get("finished")), int(status.get("liveStartTs") or 0), cur_marks, live.get("info") or {}))
    for m in sorted(_vals(ch.get("matches")), key=lambda m: -(m.get("ts") or 0)):
        home, away = _team(m.get("home"), teams, "LOCAL"), _team(m.get("away"), teams, "VISITANTE")
        date = datetime.fromtimestamp((m.get("ts") or 0) / 1000).strftime("%d/%m/%Y")
        sets = m.get("sets") or {}
        out.append(Match(
            f"{home.short} {sets.get('h', 0)}-{sets.get('a', 0)} {away.short} · {date}", int(m.get("ts") or 0),
            m.get("sport", "volley"), home, away, (sets.get("h", 0), sets.get("a", 0)),
            ((m.get("score") or {}).get("h", 0), (m.get("score") or {}).get("a", 0)),
            [(x.get("h", 0), x.get("a", 0)) for x in _vals(m.get("history"))],
            bool(m.get("finished")), int(m.get("liveStart") or 0), [mark_from(x) for x in _vals(m.get("marks"))],
            m.get("info") or {}))
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
    m = re.search(r"ffmpeg version n?(\d+)", r.stdout or "")
    return int(m.group(1)) if m else 6


def build_job(clips: list, videos: list, out: str, opt: Options, intro=None, sponsors=(), workdir: str = "") -> tuple:
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
    for c in clips:
        d = max(0.5, c.end - c.start)
        args.extend(["-ss", f"{c.start:.3f}", "-t", f"{d:.3f}", "-i", videos[c.video].path])
        k = len(vlab)
        parts.append(f"[{n_in}:v]{norm_v},setpts=PTS-STARTPTS[v{k}]")
        parts.append(f"[{n_in}:a]{norm_a},asetpts=PTS-STARTPTS[a{k}]")
        n_in += 1
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
                 with_slides: bool, on_progress=None, should_stop=None) -> tuple:
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
        args, total = build_job(clips, videos, out, opt, intro, spons, work)
        return run_job(args, total, on_progress, should_stop)
    finally:
        shutil.rmtree(work, ignore_errors=True)
