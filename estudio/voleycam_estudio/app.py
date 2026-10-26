"""VoleyCam Estudio: ventana principal (PySide6)."""
from __future__ import annotations

import json
import os
import sys
import traceback
from datetime import datetime

from PySide6.QtCore import QObject, QRunnable, Qt, QThreadPool, QTimer, QUrl, Signal
from PySide6.QtGui import QDesktopServices, QFont, QIcon
from PySide6.QtWidgets import (
    QAbstractItemView, QApplication, QCheckBox, QComboBox, QDoubleSpinBox, QFileDialog, QFormLayout, QFrame,
    QGroupBox, QHBoxLayout, QHeaderView, QLabel, QLineEdit, QMainWindow, QMessageBox, QPlainTextEdit,
    QProgressBar, QPushButton, QSlider, QSplitter, QTableWidget, QTableWidgetItem, QTimeEdit, QVBoxLayout,
    QWidget, QMenu, QScrollArea,
)

from . import core

APP = "VoleyCam Estudio"
VERSION = "1.1.2"

try:
    if os.environ.get("VOLEYCAM_NO_MM"):
        raise ImportError
    from PySide6.QtMultimedia import QAudioOutput, QMediaPlayer
    from PySide6.QtMultimediaWidgets import QVideoWidget
    HAS_MM = True
except Exception:  # sin multimedia: no hay vista previa
    HAS_MM = False


def config_path() -> str:
    base = os.environ.get("APPDATA") or os.path.expanduser("~/.config")
    d = os.path.join(base, "VoleyCamEstudio")
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, "config.json")


def load_cfg() -> dict:
    try:
        with open(config_path(), encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_cfg(cfg: dict):
    try:
        with open(config_path(), "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=1)
    except Exception:
        pass


STYLE = """
QWidget { font-family: 'Segoe UI', 'Barlow', sans-serif; font-size: 10.5pt; color: #13233F; }
QMainWindow, QWidget#root { background: #EDF1F5; }
QGroupBox { background: #FFFFFF; border: 1px solid #D6DEE8; border-radius: 10px; margin-top: 14px; padding: 10px 10px 8px; font-weight: 600; }
QGroupBox::title { subcontrol-origin: margin; left: 12px; padding: 0 4px; color: #1D5FB4; font-size: 11.5pt; }
QPushButton { background: #FFFFFF; border: 1.5px solid #D6DEE8; border-radius: 8px; padding: 6px 12px; font-weight: 600; }
QPushButton:hover { border-color: #1D5FB4; }
QPushButton#primary { background: #1D5FB4; border-color: #1D5FB4; color: white; }
QPushButton#primary:disabled { background: #9DB4D6; border-color: #9DB4D6; }
QLineEdit, QComboBox, QTimeEdit, QDoubleSpinBox { background: #FFFFFF; border: 1.5px solid #D6DEE8; border-radius: 7px; padding: 4px 6px; }
QTableWidget { background: #FFFFFF; border: 1px solid #D6DEE8; border-radius: 8px; gridline-color: #EEF2F6; }
QHeaderView::section { background: #F3F6F9; border: 0; border-bottom: 1px solid #D6DEE8; padding: 5px; font-weight: 600; color: #5E6E85; }
QProgressBar { border: 1px solid #D6DEE8; border-radius: 6px; background: #FFFFFF; text-align: center; height: 18px; }
QProgressBar::chunk { background: #1D5FB4; border-radius: 6px; }
QLabel#muted { color: #5E6E85; }
QLabel#title { font-size: 18pt; font-weight: 800; color: #13233F; }
"""


class Signals(QObject):
    done = Signal(object)
    fail = Signal(str)
    progress = Signal(int, str)
    log = Signal(str)


class Task(QRunnable):
    def __init__(self, fn, *a):
        super().__init__()
        self.fn, self.a, self.s = fn, a, Signals()

    def run(self):
        try:
            self.s.done.emit(self.fn(self.s, *self.a))
        except Exception as e:  # noqa
            traceback.print_exc()
            self.s.fail.emit(str(e))


def fmt_time(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000).strftime("%H:%M:%S") if ms else "—"


def fmt_dur(s: float) -> str:
    s = int(s)
    return f"{s // 3600}:{s // 60 % 60:02d}:{s % 60:02d}" if s >= 3600 else f"{s // 60:02d}:{s % 60:02d}"


class Main(QMainWindow):
    def __init__(self):
        super().__init__()
        self.cfg = load_cfg()
        self.pool = QThreadPool.globalInstance()
        self.channel: dict = {}
        self.matches: list = []
        self.match: core.Match | None = None
        self.videos: list = []
        self.stop_flag = False
        self.busy = False
        self.setWindowTitle(f"{APP} {VERSION}")
        self.resize(1360, 860)
        self._build()
        QTimer.singleShot(200, self._autoconnect)

    # ------------------------------------------------------------ interfaz
    def _build(self):
        root = QWidget(objectName="root")
        self.setCentralWidget(root)
        lay = QVBoxLayout(root)
        lay.setContentsMargins(14, 10, 14, 10)

        top = QHBoxLayout()
        t = QLabel("Voley<span style='color:#1D5FB4'>Cam</span> Estudio", objectName="title")
        t.setTextFormat(Qt.RichText)
        top.addWidget(t)
        top.addStretch(1)
        top.addWidget(QLabel("Clave del canal:"))
        self.key = QLineEdit(self.cfg.get("key", ""))
        self.key.setPlaceholderText("Pega la clave (Ajustes de la app → Clave del canal)")
        self.key.setMinimumWidth(320)
        top.addWidget(self.key)
        self.btnConn = QPushButton("Conectar")
        self.btnConn.clicked.connect(self.connect_channel)
        top.addWidget(self.btnConn)
        self.lblConn = QLabel("", objectName="muted")
        top.addWidget(self.lblConn)
        lay.addLayout(top)

        split = QSplitter(Qt.Horizontal)
        lay.addWidget(split, 1)

        # --- Izquierda: partido, vídeos, jugadas
        left = QWidget()
        ll = QVBoxLayout(left)
        ll.setContentsMargins(0, 0, 6, 0)

        g1 = QGroupBox("1 · Partido")
        h = QHBoxLayout(g1)
        self.cmbMatch = QComboBox()
        self.cmbMatch.currentIndexChanged.connect(self.on_match)
        h.addWidget(self.cmbMatch, 1)
        self.lblMatch = QLabel("", objectName="muted")
        h.addWidget(self.lblMatch)
        ll.addWidget(g1)

        g2 = QGroupBox("2 · Vídeos del partido")
        v2 = QVBoxLayout(g2)
        self.tblVid = QTableWidget(0, 4)
        self.tblVid.setHorizontalHeaderLabels(["Vídeo", "Empieza", "Duración", "Cómo se sabe la hora"])
        self.tblVid.horizontalHeader().setSectionResizeMode(0, QHeaderView.Stretch)
        self.tblVid.verticalHeader().setVisible(False)
        self.tblVid.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.tblVid.setMaximumHeight(150)
        v2.addWidget(self.tblVid)
        hb = QHBoxLayout()
        b = QPushButton("＋ Añadir vídeos…"); b.clicked.connect(self.add_videos); hb.addWidget(b)
        b = QPushButton("Quitar"); b.clicked.connect(self.remove_video); hb.addWidget(b)
        b = QPushButton("Es el vídeo del directo de YouTube"); b.setToolTip("El vídeo empieza cuando se pulsó EN DIRECTO")
        b.clicked.connect(self.mark_youtube); hb.addWidget(b)
        hb.addStretch(1)
        v2.addLayout(hb)
        v2.addWidget(QLabel("Elige los vídeos originales del móvil (VoleyCam_fecha_hora). Si la hora no cuadra, cámbiala en la columna «Empieza».", objectName="muted"))
        ll.addWidget(g2)

        g3 = QGroupBox("3 · Jugadas")
        v3 = QVBoxLayout(g3)
        self.tblMk = QTableWidget(0, 7)
        self.tblMk.setHorizontalHeaderLabels(["✓", "Tipo", "Hora", "En el vídeo", "Antes (s)", "Después (s)", "Jugada"])
        hh = self.tblMk.horizontalHeader()
        hh.setSectionResizeMode(6, QHeaderView.Stretch)
        self.tblMk.verticalHeader().setVisible(False)
        self.tblMk.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.tblMk.setSelectionMode(QAbstractItemView.ExtendedSelection)
        self.tblMk.cellDoubleClicked.connect(lambda r, c: c != 1 and self.preview(r))
        self.tblMk.cellClicked.connect(lambda r, c: c == 1 and self.type_menu(r))
        self.tblMk.itemChanged.connect(self.on_mark_edit)
        v3.addWidget(self.tblMk, 1)
        hb = QHBoxLayout()
        self.lblMk = QLabel("", objectName="muted"); hb.addWidget(self.lblMk, 1)
        b = QPushButton("▶ Ver jugada"); b.clicked.connect(lambda: self.preview(self.tblMk.currentRow())); hb.addWidget(b)
        b = QPushButton("Marcar todas"); b.clicked.connect(lambda: self.check_all(True)); hb.addWidget(b)
        b = QPushButton("Ninguna"); b.clicked.connect(lambda: self.check_all(False)); hb.addWidget(b)
        b = QPushButton("🗑 Quitar"); b.setToolTip("Borra las jugadas seleccionadas (al guardar, también del canal)")
        b.clicked.connect(self.delete_marks); hb.addWidget(b)
        self.btnSave = QPushButton("💾 Guardar cambios en el canal"); self.btnSave.setEnabled(False)
        self.btnSave.setToolTip("Guarda en el canal los tipos cambiados y las jugadas añadidas (también se ven en la app)")
        self.btnSave.clicked.connect(self.save_marks); hb.addWidget(self.btnSave)
        v3.addLayout(hb)
        v3.addWidget(QLabel("Toca la columna «Tipo» para cambiarla (⭐ jugada, 💜 jugadora, 🧱 bloqueo, 🛡 recepción). Doble clic para ver la jugada.", objectName="muted"))
        ll.addWidget(g3, 1)
        split.addWidget(left)

        # --- Derecha: vista previa y crear
        right = QWidget()
        rl = QVBoxLayout(right)
        rl.setContentsMargins(6, 0, 0, 0)
        gp = QGroupBox("Vista previa")
        vp = QVBoxLayout(gp)
        if HAS_MM:
            self.vw = QVideoWidget()
            self.vw.setMinimumSize(420, 236)
            self.vw.setStyleSheet("background:#000")
            vp.addWidget(self.vw)
            self.player = QMediaPlayer()
            self.audio = QAudioOutput()
            self.player.setAudioOutput(self.audio)
            self.player.setVideoOutput(self.vw)
            self.player.positionChanged.connect(self.on_pos)
            self.play_end = 0
            self.cur_video = -1
            hb = QHBoxLayout()
            b = QPushButton("−5 s"); b.clicked.connect(lambda: self.seek_rel(-5000)); hb.addWidget(b)
            self.btnPlay = QPushButton("▶"); self.btnPlay.clicked.connect(self.toggle_play); hb.addWidget(self.btnPlay)
            b = QPushButton("+5 s"); b.clicked.connect(lambda: self.seek_rel(5000)); hb.addWidget(b)
            self.lblPos = QLabel("", objectName="muted"); hb.addWidget(self.lblPos)
            hb.addStretch(1)
            vp.addLayout(hb)
            ha = QHBoxLayout()
            ha.addWidget(QLabel("Añadir aquí:"))
            self.addBtns = {}
            for k in ("jugada", "carmen", "bloqueo", "recepcion"):
                b = QPushButton(f"{core.TYPES[k][0]} {core.TYPES[k][1]}")
                b.setToolTip("Añade una jugada en el momento que se ve ahora. Si pulsas otro botón enseguida, se juntan en la misma jugada.")
                b.clicked.connect(lambda _=False, kk=k: self.add_mark_here(kk))
                ha.addWidget(b)
                self.addBtns[k] = b
            ha.addStretch(1)
            vp.addLayout(ha)
            self.last_added = None
            self.lblPrev = QLabel("Doble clic en una jugada para verla", objectName="muted"); vp.addWidget(self.lblPrev)
        else:
            vp.addWidget(QLabel("La vista previa no está disponible en este equipo.", objectName="muted"))
        rl.addWidget(gp)

        g4 = QGroupBox("4 · Crear resúmenes")
        f = QFormLayout(g4)
        f.setVerticalSpacing(8)
        self.chk = {}
        boxw = QWidget()
        boxes = QVBoxLayout(boxw)
        boxes.setContentsMargins(0, 0, 0, 0); boxes.setSpacing(6)
        for key, title, _, _ in core.SUMMARIES:
            c = QCheckBox(title); c.setChecked(True); c.toggled.connect(self.update_counts)
            self.chk[key] = c
            boxes.addWidget(c)
        f.addRow(boxw)
        self.chkSlides = QCheckBox("Portada «MEJORES JUGADAS» y patrocinadores (solo el del partido)"); self.chkSlides.setChecked(True)
        f.addRow(self.chkSlides)
        self.spFade = QDoubleSpinBox(); self.spFade.setRange(0, 2); self.spFade.setSingleStep(0.1); self.spFade.setValue(self.cfg.get("fade", 0.6)); self.spFade.setSuffix(" s"); self.spFade.setMinimumWidth(110)
        f.addRow("Fundido entre jugadas:", self.spFade)
        self.cmbQ = QComboBox(); self.cmbQ.addItems(["Normal (más rápido)", "Alta", "Máxima"]); self.cmbQ.setCurrentIndex(self.cfg.get("quality", 1))
        f.addRow("Calidad:", self.cmbQ)
        self.cmbRes = QComboBox(); self.cmbRes.addItems(["1080p", "720p"]); self.cmbRes.setCurrentIndex(self.cfg.get("res", 0))
        f.addRow("Resolución:", self.cmbRes)
        hm = QHBoxLayout()
        self.edMusic = QLineEdit(self.cfg.get("music", "")); self.edMusic.setPlaceholderText("Sin música")
        hm.addWidget(self.edMusic, 1)
        b = QPushButton("Elegir…"); b.clicked.connect(self.pick_music); hm.addWidget(b)
        b = QPushButton("✕"); b.clicked.connect(lambda: self.edMusic.setText("")); hm.addWidget(b)
        f.addRow("Música (MP3):", hm)
        self.slMusic = QSlider(Qt.Horizontal); self.slMusic.setRange(0, 100); self.slMusic.setValue(self.cfg.get("music_vol", 80))
        self.slVenue = QSlider(Qt.Horizontal); self.slVenue.setRange(0, 100); self.slVenue.setValue(self.cfg.get("venue_vol", 30))
        f.addRow("Volumen música:", self.slMusic)
        f.addRow("Sonido del pabellón:", self.slVenue)
        ho = QHBoxLayout()
        self.edOut = QLineEdit(self.cfg.get("out", os.path.join(os.path.expanduser("~"), "Videos", "VoleyCam")))
        ho.addWidget(self.edOut, 1)
        b = QPushButton("Elegir…"); b.clicked.connect(self.pick_out); ho.addWidget(b)
        f.addRow("Guardar en:", ho)
        hb = QHBoxLayout()
        self.btnMake = QPushButton("🎬 Crear resúmenes", objectName="primary"); self.btnMake.clicked.connect(self.make)
        hb.addWidget(self.btnMake, 1)
        self.btnStop = QPushButton("Cancelar"); self.btnStop.setEnabled(False); self.btnStop.clicked.connect(self.cancel)
        hb.addWidget(self.btnStop)
        b = QPushButton("📂 Abrir carpeta"); b.clicked.connect(self.open_out); hb.addWidget(b)
        f.addRow(hb)
        self.prog = QProgressBar(); self.prog.setValue(0)
        f.addRow(self.prog)
        self.log = QPlainTextEdit(); self.log.setReadOnly(True); self.log.setMaximumHeight(140)
        f.addRow(self.log)
        rl.addWidget(g4)
        rl.addStretch(1)
        scroll = QScrollArea(); scroll.setWidgetResizable(True); scroll.setFrameShape(QFrame.NoFrame)
        scroll.setWidget(right)
        split.addWidget(scroll)
        split.setSizes([800, 560])

    # ------------------------------------------------------------ canal y partido
    def _autoconnect(self):
        if len(self.key.text().strip()) >= 16:
            self.connect_channel()

    def connect_channel(self):
        k = self.key.text().strip()
        if len(k) < 16:
            return QMessageBox.warning(self, APP, "Pega la clave del canal. Está en Ajustes de la app VoleyCam (Clave del canal) o en Ajustes → Canal del mando.")
        self.lblConn.setText("Conectando…"); self.btnConn.setEnabled(False)
        t = Task(lambda s, key: core.fetch_channel(key), k)
        t.s.done.connect(self.on_channel)
        t.s.fail.connect(lambda e: (self.lblConn.setText(""), self.btnConn.setEnabled(True), QMessageBox.warning(self, APP, f"No se pudo leer el canal:\n{e}")))
        self.pool.start(t)

    def on_channel(self, ch):
        self.btnConn.setEnabled(True)
        self.channel = ch
        self.cfg["key"] = self.key.text().strip(); save_cfg(self.cfg)
        self.matches = core.matches_from(ch)
        name = core.player_name(ch)
        self.chk["carmen"].setText(f"Resumen de {name}")
        self.lblConn.setText(f"✔ Conectado · {len(self.matches)} partidos")
        self.cmbMatch.blockSignals(True)
        self.cmbMatch.clear()
        for m in self.matches:
            self.cmbMatch.addItem(f"{m.title}  ({len(m.marks)} jugadas)")
        self.cmbMatch.blockSignals(False)
        if self.matches:
            self.cmbMatch.setCurrentIndex(0)
            self.on_match(0)

    def on_match(self, i):
        if i < 0 or i >= len(self.matches):
            return
        self.match = self.matches[i]
        m = self.match
        res = f"{m.sets[0]}-{m.sets[1]}" if m.sport == "volley" else f"{m.score[0]}-{m.score[1]}"
        det = "  ".join(f"{h}-{a}" for h, a in m.history)
        self.lblMatch.setText(f"{m.home.short} {res} {m.away.short}   {det}")
        self.refresh_marks()

    # ------------------------------------------------------------ vídeos
    def add_videos(self):
        start = self.cfg.get("video_dir", os.path.expanduser("~"))
        files, _ = QFileDialog.getOpenFileNames(self, "Vídeos del partido", start, "Vídeos (*.mp4 *.mov *.mkv *.m4v);;Todos (*.*)")
        if not files:
            return
        self.cfg["video_dir"] = os.path.dirname(files[0]); save_cfg(self.cfg)
        self.lblMk.setText("Leyendo vídeos…")
        t = Task(lambda s, fs: [core.load_video(f) for f in fs], files)
        t.s.done.connect(self.on_videos)
        t.s.fail.connect(lambda e: QMessageBox.warning(self, APP, e))
        self.pool.start(t)

    def on_videos(self, vids):
        known = {v.path for v in self.videos}
        self.videos += [v for v in vids if v.path not in known]
        self.videos.sort(key=lambda v: (v.start_ms or 9e15, os.path.basename(v.path)))
        self.refresh_videos()
        self.refresh_marks()

    def refresh_videos(self):
        if HAS_MM:
            self.cur_video = -1
            self.player.stop()
        self.tblVid.setRowCount(len(self.videos))
        for r, v in enumerate(self.videos):
            self.tblVid.setItem(r, 0, QTableWidgetItem(os.path.basename(v.path)))
            te = QTimeEdit(); te.setDisplayFormat("HH:mm:ss")
            if v.start_ms:
                d = datetime.fromtimestamp(v.start_ms / 1000)
                from PySide6.QtCore import QTime
                te.setTime(QTime(d.hour, d.minute, d.second))
            te.timeChanged.connect(lambda tm, row=r: self.on_start_edit(row, tm))
            self.tblVid.setCellWidget(r, 1, te)
            self.tblVid.setItem(r, 2, QTableWidgetItem(fmt_dur(v.duration)))
            src = {"nombre": "por el nombre del archivo", "fecha interna": "por la fecha guardada en el vídeo",
                   "directo": "inicio del directo", "manual": "puesta a mano", "?": "⚠ desconocida: ponla a mano"}.get(v.source, v.source)
            self.tblVid.setItem(r, 3, QTableWidgetItem(src))

    def on_start_edit(self, row, tm):
        v = self.videos[row]
        base = datetime.fromtimestamp(v.start_ms / 1000) if v.start_ms else (
            datetime.fromtimestamp(self.match.ts / 1000) if self.match and self.match.ts else datetime.now())
        new = base.replace(hour=tm.hour(), minute=tm.minute(), second=tm.second())
        v.start_ms = int(new.timestamp() * 1000)
        v.source = "manual"
        self.tblVid.setItem(row, 3, QTableWidgetItem("puesta a mano"))
        self.refresh_marks()

    def remove_video(self):
        r = self.tblVid.currentRow()
        if r >= 0:
            del self.videos[r]
            self.refresh_videos(); self.refresh_marks()

    def mark_youtube(self):
        r = self.tblVid.currentRow()
        if r < 0:
            return QMessageBox.information(self, APP, "Selecciona primero el vídeo en la lista.")
        if not self.match or not self.match.live_start:
            return QMessageBox.information(self, APP, "Este partido no tiene guardada la hora de inicio del directo.")
        self.videos[r].start_ms = self.match.live_start
        self.videos[r].source = "directo"
        self.refresh_videos(); self.refresh_marks()

    # ------------------------------------------------------------ jugadas
    def sec(self) -> dict:
        b, a = core.hl_seconds(self.channel) if self.channel else (7, 3)
        s = {k: (t[2], t[3]) for k, t in core.TYPES.items()}
        s["jugada"] = s["carmen"] = (b, a)
        return s

    def refresh_marks(self):
        m = self.match
        self.tblMk.blockSignals(True)
        if not m:
            self.tblMk.setRowCount(0); self.tblMk.blockSignals(False); return
        marks = sorted(m.marks, key=lambda x: x.ts)
        self.tblMk.setRowCount(len(marks))
        inside = 0
        sec = self.sec()
        for r, mk in enumerate(marks):
            where = ""
            for i, v in enumerate(self.videos):
                if v.start_ms and v.start_ms <= mk.ts <= v.end_ms:
                    where = f"{i + 1} · {fmt_dur((mk.ts - v.start_ms) / 1000)}"
                    break
            if where:
                inside += 1
            c = QTableWidgetItem(); c.setFlags(Qt.ItemIsUserCheckable | Qt.ItemIsEnabled)
            c.setCheckState(Qt.Checked if mk.enabled else Qt.Unchecked)
            c.setData(Qt.UserRole, r)
            self.tblMk.setItem(r, 0, c)
            ro = lambda s: (lambda it: (it.setFlags(Qt.ItemIsEnabled | Qt.ItemIsSelectable), it)[1])(QTableWidgetItem(s))
            self.tblMk.setItem(r, 1, ro(mk.icon))
            self.tblMk.setItem(r, 2, ro(fmt_time(mk.ts)))
            self.tblMk.setItem(r, 3, ro(where or "fuera de los vídeos"))
            b, a = core.mark_window(mk, sec)
            self.tblMk.setItem(r, 4, QTableWidgetItem(f"{b:g}"))
            self.tblMk.setItem(r, 5, QTableWidgetItem(f"{a:g}"))
            self.tblMk.setItem(r, 6, ro(mk.label))
            if not where:
                for col in range(7):
                    it = self.tblMk.item(r, col)
                    if it:
                        it.setForeground(Qt.gray)
        self._sorted_marks = marks
        self.tblMk.resizeColumnsToContents()
        self.tblMk.horizontalHeader().setSectionResizeMode(6, QHeaderView.Stretch)
        self.tblMk.blockSignals(False)
        self.lblMk.setText(f"{len(marks)} jugadas · {inside} caen dentro de los vídeos" if self.videos else f"{len(marks)} jugadas · añade los vídeos del partido")
        dirty = sum(1 for x in marks if x.dirty) + len(m.removed)
        self.btnSave.setEnabled(dirty > 0)
        self.btnSave.setText(f"💾 Guardar cambios en el canal ({dirty})" if dirty else "💾 Guardar cambios en el canal")
        self.update_counts()

    def on_mark_edit(self, item):
        r = item.row()
        marks = getattr(self, "_sorted_marks", [])
        if r >= len(marks):
            return
        mk = marks[r]
        if item.column() == 0:
            mk.enabled = item.checkState() == Qt.Checked
        elif item.column() in (4, 5):
            try:
                val = max(0.0, float(item.text().replace(",", ".")))
            except ValueError:
                return
            if item.column() == 4:
                mk.before = val
            else:
                mk.after = val
        self.update_counts()

    def check_all(self, on):
        for mk in getattr(self, "_sorted_marks", []):
            mk.enabled = on
        self.refresh_marks()

    def update_counts(self):
        if not self.match:
            return
        sec = self.sec()
        for key, title, filt, _ in core.SUMMARIES:
            n = len(core.clips_for(self.match.marks, self.videos, sec, filt)) if self.videos else 0
            base = self.chk[key].text().split("  (")[0]
            self.chk[key].setText(f"{base}  ({n} tramo{'' if n == 1 else 's'})")
            self.chk[key].setEnabled(n > 0)

    def preview(self, r):
        if not HAS_MM or r < 0:
            return
        marks = getattr(self, "_sorted_marks", [])
        if r >= len(marks):
            return
        mk = marks[r]
        for v in self.videos:
            if v.start_ms and v.start_ms <= mk.ts <= v.end_ms:
                b, a = core.mark_window(mk, self.sec())
                t = (mk.ts - v.start_ms) / 1000
                self.play_end = int((t + a) * 1000)
                if self.cur_video != self.videos.index(v):
                    self.player.setSource(QUrl.fromLocalFile(v.path))
                    self.cur_video = self.videos.index(v)
                self.player.setPosition(int(max(0, t - b) * 1000))
                self.player.play()
                self.lblPrev.setText(f"{mk.icon} {mk.label}")
                return
        self.lblPrev.setText("Esa jugada no cae dentro de ningún vídeo")

    def on_pos(self, p):
        if self.play_end and p >= self.play_end:
            self.player.pause(); self.play_end = 0
        if 0 <= self.cur_video < len(self.videos):
            v = self.videos[self.cur_video]
            self.lblPos.setText(f"{fmt_dur(p / 1000)}  ·  {fmt_time(v.start_ms + p) if v.start_ms else ''}")
        self.btnPlay.setText("❚❚" if self.player.playbackState() == QMediaPlayer.PlayingState else "▶")

    def toggle_play(self):
        if self.cur_video < 0 and self.videos:
            self.player.setSource(QUrl.fromLocalFile(self.videos[0].path)); self.cur_video = 0
        self.play_end = 0
        if self.player.playbackState() == QMediaPlayer.PlayingState:
            self.player.pause()
        else:
            self.player.play()

    def seek_rel(self, ms):
        self.play_end = 0
        self.player.setPosition(max(0, self.player.position() + ms))

    def add_mark_here(self, kind: str):
        if not self.match or self.cur_video < 0:
            return QMessageBox.information(self, APP, "Primero pon un vídeo en la vista previa (doble clic en una jugada o ▶).")
        v = self.videos[self.cur_video]
        if not v.start_ms:
            return QMessageBox.information(self, APP, "Ese vídeo no tiene hora de inicio. Ponla en la columna «Empieza».")
        ts = int(v.start_ms + self.player.position())
        last = self.last_added
        if last is not None and last in self.match.marks and abs(ts - last.ts) < 5000:
            # Otro botón justo después: misma jugada con otra etiqueta
            last.tags = set(last.tags or {"jugada"}) | {kind}
            last.dirty = True
            mk = last
        else:
            name = core.player_name(self.channel) if kind == "carmen" else core.TYPES[kind][1]
            mk = core.Mark(ts, f"{name} · añadida en Estudio", {kind}, dirty=True)
            self.match.marks.append(mk)
            self.last_added = mk
        self.refresh_marks()
        self.lblPrev.setText(f"Añadida: {mk.icon} {fmt_time(mk.ts)}  (pulsa «Guardar cambios en el canal» para conservarla)")

    def type_menu(self, r):
        marks = getattr(self, "_sorted_marks", [])
        if r >= len(marks):
            return
        mk = marks[r]
        menu = QMenu(self)
        tags = set(mk.tags) or {"jugada"}
        acts = {}
        for k in ("jugada", "carmen", "bloqueo", "recepcion"):
            icon, name = core.TYPES[k][0], core.TYPES[k][1]
            if k == "carmen":
                name = core.player_name(self.channel)
            a = menu.addAction(f"{icon} {name}"); a.setCheckable(True); a.setChecked(k in tags)
            acts[a] = k
        rect = self.tblMk.visualItemRect(self.tblMk.item(r, 1))
        chosen = menu.exec(self.tblMk.viewport().mapToGlobal(rect.bottomLeft()))
        if chosen in acts:
            k = acts[chosen]
            tags = tags ^ {k}
            if not tags:
                tags = {"jugada"}
            mk.tags = tags
            mk.dirty = True
            self.refresh_marks()

    def delete_marks(self):
        if not self.match:
            return
        marks = getattr(self, "_sorted_marks", [])
        rows = sorted({i.row() for i in self.tblMk.selectedIndexes()})
        sel = [marks[r] for r in rows if r < len(marks)]
        if not sel:
            return QMessageBox.information(self, APP, "Selecciona en la lista la jugada o jugadas que quieres quitar.")
        if QMessageBox.question(self, APP, f"¿Quitar {len(sel)} jugada(s)? Al pulsar «Guardar cambios en el canal» se borrarán también del canal.") != QMessageBox.Yes:
            return
        for mk in sel:
            self.match.marks.remove(mk)
            if mk.key:
                self.match.removed.append(mk.key)
        self.refresh_marks()

    def save_marks(self):
        if not self.match:
            return
        try:
            n = core.save_marks(self.key.text(), self.match.path, self.match.marks, self.match.removed)
            self.match.removed.clear()
            self.btnSave.setEnabled(False)
            self.lblMk.setText(f"Guardadas {n} jugadas en el canal")
        except Exception as e:
            QMessageBox.warning(self, APP, f"No se pudo guardar:\n{e}")

    # ------------------------------------------------------------ crear
    def pick_music(self):
        f, _ = QFileDialog.getOpenFileName(self, "Música de fondo", os.path.expanduser("~"), "Audio (*.mp3 *.m4a *.wav *.aac *.ogg)")
        if f:
            self.edMusic.setText(f)

    def pick_out(self):
        d = QFileDialog.getExistingDirectory(self, "Carpeta para los resúmenes", self.edOut.text())
        if d:
            self.edOut.setText(d)

    def open_out(self):
        d = self.edOut.text()
        os.makedirs(d, exist_ok=True)
        QDesktopServices.openUrl(QUrl.fromLocalFile(d))

    def options(self) -> core.Options:
        q = self.cmbQ.currentIndex()
        w, h = (1920, 1080) if self.cmbRes.currentIndex() == 0 else (1280, 720)
        return core.Options(fade=self.spFade.value(), crf=[23, 20, 17][q], preset=["veryfast", "faster", "medium"][q],
                            music=self.edMusic.text().strip(), music_vol=self.slMusic.value() / 100,
                            venue_vol=self.slVenue.value() / 100, width=w, height=h)

    def make(self):
        if self.busy or not self.match:
            return
        if not self.videos:
            return QMessageBox.information(self, APP, "Añade primero los vídeos del partido.")
        jobs = []
        sec = self.sec()
        name = core.player_name(self.channel)
        safe = "".join(ch for ch in name if ch.isalnum()) or "jugadora"
        m = self.match
        stamp = datetime.fromtimestamp((self.videos[0].start_ms or m.ts) / 1000).strftime("%Y%m%d")
        vs = f"{m.home.short}_{m.away.short}".replace(" ", "")
        for key, title, filt, slides in core.SUMMARIES:
            if not self.chk[key].isChecked() or not self.chk[key].isEnabled():
                continue
            clips = core.clips_for(m.marks, self.videos, sec, filt)
            if clips:
                fname = {"partido": f"Resumen_{vs}_{stamp}.mp4", "carmen": f"Resumen_{safe}_{stamp}.mp4",
                         "bloqueo": f"Bloqueos_{vs}_{stamp}.mp4", "recepcion": f"Recepciones_{vs}_{stamp}.mp4"}[key]
                jobs.append((key, self.chk[key].text().split("  (")[0], clips, slides and self.chkSlides.isChecked(), fname))
        if not jobs:
            return QMessageBox.information(self, APP, "No hay jugadas para los resúmenes elegidos.")
        out_dir = self.edOut.text().strip()
        os.makedirs(out_dir, exist_ok=True)
        opt = self.options()
        self.cfg.update(fade=self.spFade.value(), quality=self.cmbQ.currentIndex(), res=self.cmbRes.currentIndex(),
                        music=self.edMusic.text(), music_vol=self.slMusic.value(), venue_vol=self.slVenue.value(), out=out_dir)
        save_cfg(self.cfg)
        sponsors = core.sponsors_from(self.channel)
        self.busy = True; self.stop_flag = False
        self.btnMake.setEnabled(False); self.btnStop.setEnabled(True)
        self.log.clear()

        def work(s, jobs):
            done = []
            for i, (key, title, clips, slides, fname) in enumerate(jobs):
                if self.stop_flag:
                    break
                out = os.path.join(out_dir, fname)
                s.log.emit(f"▶ {title}: {len(clips)} tramos…")
                ok, err = core.make_summary(key, m, clips, self.videos, sponsors, out, opt, slides,
                                            lambda p, t=title, i=i: s.progress.emit(int((i + p / 100) / len(jobs) * 100), t),
                                            lambda: self.stop_flag)
                if ok:
                    s.log.emit(f"   ✔ Guardado: {fname}")
                    done.append(out)
                elif self.stop_flag:
                    s.log.emit("   Cancelado.")
                else:
                    s.log.emit(f"   ✖ Error al crear {title}:\n{err}")
            return done

        t = Task(work, jobs)
        t.s.log.connect(self.log.appendPlainText)
        t.s.progress.connect(lambda p, title: (self.prog.setValue(p), self.prog.setFormat(f"{title}: %p %")))
        t.s.done.connect(self.on_made)
        t.s.fail.connect(lambda e: (self.on_made([]), QMessageBox.warning(self, APP, e)))
        self.pool.start(t)

    def on_made(self, done):
        self.busy = False
        self.btnMake.setEnabled(True); self.btnStop.setEnabled(False)
        self.prog.setValue(100 if done else 0); self.prog.setFormat("%p %")
        if done:
            self.log.appendPlainText(f"Listo: {len(done)} resúmenes en {self.edOut.text()}")

    def cancel(self):
        self.stop_flag = True

    def closeEvent(self, e):
        self.stop_flag = True
        super().closeEvent(e)


def main():
    app = QApplication(sys.argv)
    app.setApplicationName(APP)
    app.setStyleSheet(STYLE)
    ico = os.path.join(core.base_dir(), "icon.ico")
    if os.path.isfile(ico):
        app.setWindowIcon(QIcon(ico))
    w = Main()
    w.show()
    sys.exit(app.exec())
