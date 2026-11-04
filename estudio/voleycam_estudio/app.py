"""VoleyCam Estudio: ventana principal (PySide6). Tres pestañas: Preparar · Ver y marcar · Crear resúmenes."""
from __future__ import annotations

import json
import os
import sys
import traceback
from datetime import datetime

from PySide6.QtCore import QObject, QRunnable, QRectF, Qt, QThreadPool, QTime, QTimer, QUrl, Signal
from PySide6.QtGui import QColor, QDesktopServices, QIcon, QKeySequence, QPainter, QPen, QShortcut
from PySide6.QtWidgets import (
    QAbstractItemView, QApplication, QButtonGroup, QCheckBox, QComboBox, QDoubleSpinBox, QFileDialog,
    QFormLayout, QFrame, QGroupBox, QHBoxLayout, QHeaderView, QLabel, QLineEdit, QMainWindow, QMessageBox,
    QPlainTextEdit, QProgressBar, QPushButton, QScrollArea, QSizePolicy, QSlider, QSplitter, QStackedWidget,
    QTableWidget, QTableWidgetItem, QTimeEdit, QVBoxLayout, QWidget,
)

from . import core

APP = "VoleyCam Estudio"
VERSION = "1.2.4"

try:
    if os.environ.get("VOLEYCAM_NO_MM"):
        raise ImportError
    from PySide6.QtMultimedia import QAudioOutput, QMediaPlayer
    from PySide6.QtMultimediaWidgets import QVideoWidget
    HAS_MM = True
except Exception:  # sin multimedia: no hay vídeo
    HAS_MM = False

KINDS = ("jugada", "carmen", "bloqueo", "recepcion")
COLORS = {"jugada": "#F2B705", "carmen": "#9B4DCA", "bloqueo": "#C0392B", "recepcion": "#2D7FD3"}


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
QMainWindow, QWidget#root, QWidget#page { background: #EDF1F5; }
QGroupBox { background: #FFFFFF; border: 1px solid #D6DEE8; border-radius: 10px; margin-top: 14px; padding: 10px 10px 8px; font-weight: 600; }
QGroupBox::title { subcontrol-origin: margin; left: 12px; padding: 0 4px; color: #1D5FB4; font-size: 11.5pt; }
QFrame#card { background: #FFFFFF; border: 1px solid #D6DEE8; border-radius: 10px; }
QPushButton { background: #FFFFFF; border: 1.5px solid #D6DEE8; border-radius: 8px; padding: 6px 12px; font-weight: 600; }
QPushButton:hover { border-color: #1D5FB4; }
QPushButton:checked { background: #E3ECF8; border-color: #1D5FB4; }
QPushButton#primary { background: #1D5FB4; border-color: #1D5FB4; color: white; }
QPushButton#primary:disabled { background: #9DB4D6; border-color: #9DB4D6; }
QPushButton#danger { color: #C62828; }
QPushButton#tab { border: 0; background: transparent; font-size: 12pt; color: #5E6E85; padding: 7px 16px; }
QPushButton#tab:checked { background: #1D5FB4; color: white; border-radius: 9px; }
QPushButton#filter { border-radius: 12px; padding: 3px 10px; font-weight: 500; }
QLineEdit, QComboBox, QTimeEdit, QDoubleSpinBox { background: #FFFFFF; border: 1.5px solid #D6DEE8; border-radius: 7px; padding: 4px 6px; }
QTableWidget { background: #FFFFFF; border: 1px solid #D6DEE8; border-radius: 8px; gridline-color: #EEF2F6; selection-background-color: #DCE8F7; selection-color: #13233F; }
QHeaderView::section { background: #F3F6F9; border: 0; border-bottom: 1px solid #D6DEE8; padding: 5px; font-weight: 600; color: #5E6E85; }
QProgressBar { border: 1px solid #D6DEE8; border-radius: 6px; background: #FFFFFF; text-align: center; height: 18px; }
QProgressBar::chunk { background: #1D5FB4; border-radius: 6px; }
QLabel#muted { color: #5E6E85; }
QLabel#title { font-size: 18pt; font-weight: 800; color: #13233F; }
QLabel#h { color: #1D5FB4; font-weight: 700; font-size: 11pt; }
QLabel#time { font-size: 13pt; font-weight: 800; }
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
    s = int(max(0, s))
    return f"{s // 3600}:{s // 60 % 60:02d}:{s % 60:02d}" if s >= 3600 else f"{s // 60:02d}:{s % 60:02d}"


class Timeline(QWidget):
    """Barra de tiempo deslizante del vídeo actual, con las jugadas en colores."""
    seek = Signal(int)          # ms dentro del vídeo
    markClicked = Signal(object)

    def __init__(self):
        super().__init__()
        self.dur = 0
        self.pos = 0
        self.marks = []         # (ms, color, mark)
        self.clips = []         # (inicio ms, fin ms)
        self.sel = None
        self.drag = False
        self.setMinimumHeight(46)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        self.setCursor(Qt.PointingHandCursor)
        self.setMouseTracking(True)

    def set_data(self, dur, marks, clips):
        self.dur, self.marks, self.clips = max(0, int(dur)), marks, clips
        self.update()

    def set_pos(self, ms):
        if not self.drag:
            self.pos = ms
            self.update()

    def _x(self, ms):
        w = self.width() - 16
        return 8 + (ms / self.dur * w if self.dur else 0)

    def _ms(self, x):
        w = self.width() - 16
        return int(min(max(0.0, (x - 8) / w), 1.0) * self.dur) if self.dur and w > 0 else 0

    def _mark_at(self, x):
        best, bd = None, 7
        for ms, _, mk in self.marks:
            d = abs(self._x(ms) - x)
            if d < bd:
                best, bd = mk, d
        return best

    def mousePressEvent(self, e):
        if e.button() != Qt.LeftButton or not self.dur:
            return
        x = e.position().x()
        mk = self._mark_at(x)
        if mk is not None:
            self.markClicked.emit(mk)
            return
        self.drag = True
        self.pos = self._ms(x); self.update()
        self.seek.emit(self.pos)

    def mouseMoveEvent(self, e):
        x = e.position().x()
        if self.drag:
            self.pos = self._ms(x); self.update()
            self.seek.emit(self.pos)
        else:
            mk = self._mark_at(x)
            self.setToolTip(f"{mk.icon} {fmt_time(mk.ts)}  {mk.label}" if mk is not None else fmt_dur(self._ms(x) / 1000) if self.dur else "")

    def mouseReleaseEvent(self, e):
        if self.drag:
            self.drag = False
            self.seek.emit(self._ms(e.position().x()))

    def paintEvent(self, _):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(8, 6, self.width() - 16, self.height() - 12)
        p.setPen(Qt.NoPen)
        p.setBrush(QColor("#EEF2F6")); p.drawRoundedRect(r, 6, 6)
        if not self.dur:
            return
        # Tramos que entran en los resúmenes (sombreado)
        p.setBrush(QColor(29, 95, 180, 28))
        for a, b in self.clips:
            p.drawRect(QRectF(self._x(a), r.top(), max(2.0, self._x(b) - self._x(a)), r.height()))
        # Parte ya vista
        p.setBrush(QColor(29, 95, 180, 40))
        p.drawRoundedRect(QRectF(r.left(), r.top(), self._x(self.pos) - r.left(), r.height()), 6, 6)
        # Jugadas
        for ms, color, mk in self.marks:
            x = self._x(ms)
            if mk is self.sel:
                p.setPen(QPen(QColor("#13233F"), 2))
            else:
                p.setPen(Qt.NoPen)
            p.setBrush(QColor(color))
            p.drawRoundedRect(QRectF(x - 3, r.top() + 6, 6, r.height() - 12), 2, 2)
        # Cabezal
        x = self._x(self.pos)
        p.setPen(QPen(QColor("#D32F2F"), 2.5)); p.drawLine(int(x), int(r.top() - 3), int(x), int(r.bottom() + 3))


def ro(text: str) -> QTableWidgetItem:
    it = QTableWidgetItem(text)
    it.setFlags(Qt.ItemIsEnabled | Qt.ItemIsSelectable)
    return it


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
        self.yt_stop = False
        self.busy = False
        self.sel = None              # jugada seleccionada
        self.filter = "todas"
        self.view_marks = []         # jugadas de la lista (filtradas y ordenadas)
        self.cur_video = -1
        self.play_end = 0
        self.pending = None          # (posición ms, reproducir) al terminar de cargar un vídeo
        self.last_added = None
        self.setWindowTitle(f"{APP} {VERSION}")
        self.resize(1440, 900)
        self._build()
        QTimer.singleShot(200, self._autoconnect)

    # ================================================================ interfaz
    def _build(self):
        root = QWidget(objectName="root")
        self.setCentralWidget(root)
        lay = QVBoxLayout(root)
        lay.setContentsMargins(14, 8, 14, 10)

        top = QHBoxLayout()
        t = QLabel("Voley<span style='color:#1D5FB4'>Cam</span> Estudio", objectName="title")
        t.setTextFormat(Qt.RichText)
        top.addWidget(t)
        top.addSpacing(24)
        self.tabs = QButtonGroup(self)
        for i, name in enumerate(["1 · Preparar", "2 · Ver y marcar", "3 · Crear resúmenes"]):
            b = QPushButton(name, objectName="tab"); b.setCheckable(True); b.setFocusPolicy(Qt.NoFocus)
            self.tabs.addButton(b, i); top.addWidget(b)
        self.tabs.idClicked.connect(self.go_tab)
        top.addStretch(1)
        self.lblHead = QLabel("", objectName="muted"); top.addWidget(self.lblHead)
        lay.addLayout(top)

        self.stack = QStackedWidget()
        lay.addWidget(self.stack, 1)
        self.stack.addWidget(self._page_prepare())
        self.stack.addWidget(self._page_view())
        self.stack.addWidget(self._page_make())
        self.tabs.button(0).setChecked(True)
        self._shortcuts()

    def _scroll(self, w):
        s = QScrollArea(); s.setWidgetResizable(True); s.setFrameShape(QFrame.NoFrame); s.setWidget(w)
        return s

    # ---------------------------------------------------------------- 1 · Preparar
    def _page_prepare(self):
        page = QWidget(objectName="page")
        outer = QHBoxLayout(page)
        col = QWidget(); col.setMaximumWidth(1000)
        ll = QVBoxLayout(col); ll.setContentsMargins(0, 0, 0, 0)
        outer.addStretch(1); outer.addWidget(col, 10); outer.addStretch(1)

        g0 = QGroupBox("Canal")
        h = QHBoxLayout(g0)
        h.addWidget(QLabel("Clave del canal:"))
        self.key = QLineEdit(self.cfg.get("key", ""))
        self.key.setPlaceholderText("Pega la clave (Ajustes de la app → Clave del canal)")
        h.addWidget(self.key, 1)
        self.btnConn = QPushButton("Conectar"); self.btnConn.clicked.connect(self.connect_channel); h.addWidget(self.btnConn)
        self.lblConn = QLabel("", objectName="muted"); h.addWidget(self.lblConn)
        ll.addWidget(g0)

        g1 = QGroupBox("Partido")
        h = QHBoxLayout(g1)
        self.cmbMatch = QComboBox(); self.cmbMatch.currentIndexChanged.connect(self.on_match)
        h.addWidget(self.cmbMatch, 1)
        self.lblMatch = QLabel("", objectName="muted"); h.addWidget(self.lblMatch)
        ll.addWidget(g1)

        g2 = QGroupBox("Vídeos del partido")
        v2 = QVBoxLayout(g2)
        self.tblVid = QTableWidget(0, 4)
        self.tblVid.setHorizontalHeaderLabels(["Vídeo", "Empieza", "Duración", "Cómo se sabe la hora"])
        self.tblVid.horizontalHeader().setSectionResizeMode(0, QHeaderView.Stretch)
        self.tblVid.verticalHeader().setVisible(False)
        self.tblVid.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.tblVid.setMinimumHeight(150)
        v2.addWidget(self.tblVid)
        hb = QHBoxLayout()
        b = QPushButton("＋ Añadir vídeos…"); b.clicked.connect(self.add_videos); hb.addWidget(b)
        b = QPushButton("Quitar vídeo"); b.clicked.connect(self.remove_video); hb.addWidget(b)
        b = QPushButton("Es el vídeo del directo de YouTube"); b.setToolTip("El vídeo empieza cuando se pulsó EN DIRECTO")
        b.clicked.connect(self.mark_youtube); hb.addWidget(b)
        hb.addStretch(1)
        v2.addLayout(hb)
        v2.addWidget(QLabel("Elige los vídeos originales del móvil (VoleyCam_fecha_hora) o descárgalo de YouTube. Si la hora no cuadra, cámbiala en «Empieza».", objectName="muted"))
        ll.addWidget(g2)

        g3 = QGroupBox("Descargar de YouTube")
        v3 = QVBoxLayout(g3)
        hy = QHBoxLayout()
        self.edYt = QLineEdit(); self.edYt.setPlaceholderText("Pega el enlace del vídeo o del directo ya emitido")
        hy.addWidget(self.edYt, 1)
        self.cmbYtRes = QComboBox(); self.cmbYtRes.addItems(["1080p", "720p"]); self.cmbYtRes.setCurrentIndex(self.cfg.get("yt_res", 0))
        hy.addWidget(self.cmbYtRes)
        self.btnYt = QPushButton("⬇ Descargar", objectName="primary"); self.btnYt.clicked.connect(self.yt_download); hy.addWidget(self.btnYt)
        self.btnYtStop = QPushButton("Cancelar"); self.btnYtStop.setEnabled(False); self.btnYtStop.clicked.connect(self.yt_cancel); hy.addWidget(self.btnYtStop)
        v3.addLayout(hy)
        hy2 = QHBoxLayout()
        self.progYt = QProgressBar(); self.progYt.setValue(0); self.progYt.setFormat("%p %"); hy2.addWidget(self.progYt, 1)
        self.lblYt = QLabel("", objectName="muted"); hy2.addWidget(self.lblYt)
        b = QPushButton("📂"); b.setToolTip("Abrir la carpeta de descargas"); b.clicked.connect(self.yt_open_dir); hy2.addWidget(b)
        b = QPushButton("Actualizar descargador"); b.setToolTip("Actualiza yt-dlp si YouTube deja de descargar"); b.clicked.connect(self.yt_update); hy2.addWidget(b)
        v3.addLayout(hy2)
        v3.addWidget(QLabel("Se guarda en la carpeta de los resúmenes, dentro de «Directos». Al terminar se añade a la lista de vídeos.", objectName="muted"))
        ll.addWidget(g3)

        hb = QHBoxLayout(); hb.addStretch(1)
        b = QPushButton("Ver y marcar →", objectName="primary"); b.clicked.connect(lambda: self.go_tab(1)); hb.addWidget(b)
        ll.addLayout(hb)
        ll.addStretch(1)
        return self._scroll(page)

    # ---------------------------------------------------------------- 2 · Ver y marcar
    def _page_view(self):
        page = QWidget(objectName="page")
        split = QSplitter(Qt.Horizontal, page)
        pl = QHBoxLayout(page); pl.setContentsMargins(0, 4, 0, 0); pl.addWidget(split)

        # --- Izquierda: vídeo + barra + controles
        left = QWidget(); lv = QVBoxLayout(left); lv.setContentsMargins(0, 0, 6, 0)
        if HAS_MM:
            self.vw = QVideoWidget(); self.vw.setStyleSheet("background:#000"); self.vw.setMinimumSize(480, 270)
            self.vw.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
            lv.addWidget(self.vw, 1)
            self.player = QMediaPlayer(); self.audio = QAudioOutput()
            self.player.setAudioOutput(self.audio); self.player.setVideoOutput(self.vw)
            self.player.positionChanged.connect(self.on_pos)
            self.player.mediaStatusChanged.connect(self.on_media_status)
            self.player.playbackStateChanged.connect(lambda *_: self.btnPlay.setText("❚❚" if self.playing() else "▶"))
        else:
            self.player = None
            lab = QLabel("La vista de vídeo no está disponible en este equipo.", alignment=Qt.AlignCenter)
            lab.setStyleSheet("background:#000;color:#ccc;border-radius:10px"); lab.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
            lv.addWidget(lab, 1)

        bar = QFrame(objectName="card"); bv = QVBoxLayout(bar); bv.setContentsMargins(12, 8, 12, 8)
        ht = QHBoxLayout()
        self.lblTime = QLabel("00:00", objectName="time"); ht.addWidget(self.lblTime)
        self.lblTime2 = QLabel("", objectName="muted"); ht.addWidget(self.lblTime2)
        ht.addStretch(1)
        self.cmbVid = QComboBox(); self.cmbVid.setFocusPolicy(Qt.NoFocus); self.cmbVid.activated.connect(lambda i: self.load_video_idx(i, 0, False))
        ht.addWidget(self.cmbVid)
        bv.addLayout(ht)
        self.timeline = Timeline()
        self.timeline.seek.connect(self.on_timeline_seek)
        self.timeline.markClicked.connect(lambda mk: self.select_mark(mk, play=True))
        bv.addWidget(self.timeline)
        hc = QHBoxLayout(); hc.setSpacing(6)

        def btn(text, fn, tip="", name=""):
            b = QPushButton(text, objectName=name) if name else QPushButton(text)
            b.setFocusPolicy(Qt.NoFocus); b.clicked.connect(fn)
            if tip:
                b.setToolTip(tip)
            hc.addWidget(b)
            return b
        btn("⏮ Anterior", lambda: self.nav(-1), "Jugada anterior (P)")
        btn("−10 s", lambda: self.seek_rel(-10000))
        btn("−5 s", lambda: self.seek_rel(-5000), "←")
        self.btnPlay = btn("▶", self.toggle_play, "Espacio", "primary"); self.btnPlay.setMinimumWidth(48)
        btn("+5 s", lambda: self.seek_rel(5000), "→")
        btn("+10 s", lambda: self.seek_rel(10000))
        btn("Siguiente ⏭", lambda: self.nav(1), "Jugada siguiente (N)")
        self.cmbSpeed = QComboBox(); self.cmbSpeed.addItems(["0.5x", "1x", "1.5x", "2x"]); self.cmbSpeed.setCurrentIndex(1)
        self.cmbSpeed.setFocusPolicy(Qt.NoFocus); self.cmbSpeed.currentIndexChanged.connect(self.on_speed)
        hc.addWidget(self.cmbSpeed)
        hc.addSpacing(10)
        self.addBtns = {}
        for i, k in enumerate(KINDS):
            self.addBtns[k] = btn(f"{core.TYPES[k][0]} {core.TYPES[k][1]}", lambda _=False, kk=k: self.add_mark_here(kk),
                                  f"Añadir aquí ({i + 1}). Si pulsas otro enseguida, se juntan en la misma jugada.")
        hc.addStretch(1)
        bv.addLayout(hc)
        lv.addWidget(bar)
        split.addWidget(left)

        # --- Derecha: jugada seleccionada, lista, teclado, guardar
        right = QWidget(); rv = QVBoxLayout(right); rv.setContentsMargins(6, 0, 0, 0)
        right.setMinimumWidth(400); right.setMaximumWidth(500)

        cs = QFrame(objectName="card"); sv = QVBoxLayout(cs); sv.setContentsMargins(12, 10, 12, 10)
        sv.addWidget(QLabel("Jugada seleccionada", objectName="h"))
        self.lblSel = QLabel("Toca una jugada de la lista o de la barra", objectName="muted"); self.lblSel.setWordWrap(True)
        sv.addWidget(self.lblSel)
        hk = QHBoxLayout()
        self.selKind = {}
        for k in KINDS:
            b = QPushButton(core.TYPES[k][0]); b.setCheckable(True); b.setFocusPolicy(Qt.NoFocus)
            b.setToolTip(core.TYPES[k][1]); b.clicked.connect(lambda _=False, kk=k: self.toggle_kind(kk))
            hk.addWidget(b); self.selKind[k] = b
        sv.addLayout(hk)
        hs = QHBoxLayout()
        hs.addWidget(QLabel("Antes"))
        self.spBefore = QDoubleSpinBox(); self.spAfter = QDoubleSpinBox()
        for sp in (self.spBefore, self.spAfter):
            sp.setRange(0, 60); sp.setSingleStep(0.5); sp.setDecimals(1); sp.setSuffix(" s"); sp.setFocusPolicy(Qt.ClickFocus)
            sp.valueChanged.connect(self.on_sel_secs)
        hs.addWidget(self.spBefore); hs.addWidget(QLabel("Después")); hs.addWidget(self.spAfter)
        self.btnSelDel = QPushButton("🗑 Quitar", objectName="danger"); self.btnSelDel.setFocusPolicy(Qt.NoFocus)
        self.btnSelDel.clicked.connect(self.delete_current); hs.addWidget(self.btnSelDel)
        sv.addLayout(hs)
        self.chkSelOn = QCheckBox("Entra en los resúmenes"); self.chkSelOn.setFocusPolicy(Qt.NoFocus); self.chkSelOn.toggled.connect(self.on_sel_enabled)
        sv.addWidget(self.chkSelOn)
        rv.addWidget(cs)

        cl = QFrame(objectName="card"); lv2 = QVBoxLayout(cl); lv2.setContentsMargins(12, 10, 12, 10)
        self.lblList = QLabel("Jugadas", objectName="h"); lv2.addWidget(self.lblList)
        hf = QHBoxLayout(); hf.setSpacing(4)
        self.filters = QButtonGroup(self); self.filterBtns = {}
        for k in ("todas",) + KINDS:
            b = QPushButton("Todas" if k == "todas" else core.TYPES[k][0], objectName="filter"); b.setCheckable(True); b.setFocusPolicy(Qt.NoFocus)
            self.filters.addButton(b); self.filterBtns[k] = b; hf.addWidget(b)
            b.clicked.connect(lambda _=False, kk=k: self.set_filter(kk))
        self.filterBtns["todas"].setChecked(True)
        hf.addStretch(1)
        lv2.addLayout(hf)
        self.tblMk = QTableWidget(0, 3)
        self.tblMk.setHorizontalHeaderLabels(["✓", "Hora", "Jugada"])
        self.tblMk.horizontalHeader().setSectionResizeMode(2, QHeaderView.Stretch)
        self.tblMk.verticalHeader().setVisible(False)
        self.tblMk.setShowGrid(False)
        self.tblMk.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.tblMk.setSelectionMode(QAbstractItemView.ExtendedSelection)
        self.tblMk.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.tblMk.cellClicked.connect(self.on_list_click)
        self.tblMk.itemChanged.connect(self.on_list_check)
        lv2.addWidget(self.tblMk, 1)
        hl = QHBoxLayout(); hl.setSpacing(4)
        b = QPushButton("✓ Todas"); b.setToolTip("Marcar todas para los resúmenes"); b.clicked.connect(lambda: self.check_all(True)); hl.addWidget(b)
        b = QPushButton("Ninguna"); b.setToolTip("Desmarcar todas"); b.clicked.connect(lambda: self.check_all(False)); hl.addWidget(b)
        hl.addStretch(1)
        b = QPushButton("🗑 Quitar seleccionadas", objectName="danger"); b.setToolTip("Quita las jugadas seleccionadas en la lista (Ctrl o Mayús para varias) · Supr"); b.clicked.connect(self.delete_selected); hl.addWidget(b)
        lv2.addLayout(hl)
        self.btnOutside = QPushButton("🗑 Quitar las que no caen en los vídeos", objectName="danger")
        self.btnOutside.setToolTip("Jugadas en gris: no están dentro de ningún vídeo (por ejemplo, de otro partido)")
        self.btnOutside.clicked.connect(self.remove_outside)
        lv2.addWidget(self.btnOutside)
        rv.addWidget(cl, 1)

        ck = QFrame(objectName="card"); kv = QVBoxLayout(ck); kv.setContentsMargins(12, 8, 12, 8)
        kl = QLabel("<b>Teclado:</b> Espacio ▶/❚❚ · ← → 5 s (Mayús: 10 s) · 1-4 añadir ⭐💜🧱🛡 · Supr quitar · N / P siguiente / anterior jugada", objectName="muted")
        kl.setWordWrap(True); kl.setTextFormat(Qt.RichText); kv.addWidget(kl)
        rv.addWidget(ck)

        cg = QFrame(objectName="card"); gh = QHBoxLayout(cg); gh.setContentsMargins(12, 8, 12, 8)
        self.lblDirty = QLabel("Sin cambios", objectName="muted"); gh.addWidget(self.lblDirty, 1)
        self.btnSave = QPushButton("💾 Guardar", objectName="primary"); self.btnSave.setEnabled(False)
        self.btnSave.setToolTip("Guarda en el canal los tipos cambiados, las jugadas añadidas y las quitadas (también se ven en la app)")
        self.btnSave.clicked.connect(self.save_marks); gh.addWidget(self.btnSave)
        rv.addWidget(cg)

        split.addWidget(right)
        split.setStretchFactor(0, 1)
        split.setSizes([1000, 420])
        self.pgView = page
        self.update_sel_panel()
        return page

    # ---------------------------------------------------------------- 3 · Crear resúmenes
    def _page_make(self):
        page = QWidget(objectName="page")
        outer = QHBoxLayout(page)
        col = QWidget(); col.setMaximumWidth(820)
        cl = QVBoxLayout(col); cl.setContentsMargins(0, 0, 0, 0)
        outer.addStretch(1); outer.addWidget(col, 10); outer.addStretch(1)
        g4 = QGroupBox("Crear resúmenes")
        f = QFormLayout(g4)
        f.setVerticalSpacing(8)
        self.chk = {}
        boxw = QWidget()
        boxes = QVBoxLayout(boxw)
        boxes.setContentsMargins(0, 0, 0, 0); boxes.setSpacing(6)
        for key, title, _, _ in core.SUMMARIES:
            c = QCheckBox(title); c.setChecked(True)
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
        self.log = QPlainTextEdit(); self.log.setReadOnly(True); self.log.setMinimumHeight(160)
        f.addRow(self.log)
        cl.addWidget(g4)
        cl.addStretch(1)
        return self._scroll(page)

    def _shortcuts(self):
        def sc(key, fn):
            s = QShortcut(QKeySequence(key), self.pgView)
            s.setContext(Qt.WidgetWithChildrenShortcut)
            s.activated.connect(fn)
        sc(Qt.Key_Space, self.toggle_play)
        sc(Qt.Key_Left, lambda: self.seek_rel(-5000))
        sc(Qt.Key_Right, lambda: self.seek_rel(5000))
        sc("Shift+Left", lambda: self.seek_rel(-10000))
        sc("Shift+Right", lambda: self.seek_rel(10000))
        for i, k in enumerate(KINDS):
            sc(str(i + 1), lambda kk=k: self.add_mark_here(kk))
        sc(Qt.Key_Delete, self.delete_selected)
        sc("N", lambda: self.nav(1))
        sc("P", lambda: self.nav(-1))

    def go_tab(self, i):
        self.tabs.button(i).setChecked(True)
        self.stack.setCurrentIndex(i)
        if i == 1:
            self.pgView.setFocus()
            if self.player and self.cur_video < 0 and self.videos:
                self.load_video_idx(0, 0, False)
        if i == 2:
            self.update_counts()
        if i != 1 and self.playing():
            self.player.pause()

    # ================================================================ canal y partido
    def _autoconnect(self):
        if len(self.key.text().strip()) >= 16:
            self.connect_channel()

    def connect_channel(self):
        k = self.key.text().strip()
        if len(k) < 16:
            return QMessageBox.warning(self, APP, "Pega la clave del canal. Está en Ajustes de la app VoleyCam (Clave del canal) o en Ajustes → Canal del mando.")
        if self.dirty_count() and QMessageBox.question(self, APP, "Hay cambios sin guardar en las jugadas. ¿Volver a conectar y perderlos?") != QMessageBox.Yes:
            return
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
        self.addBtns["carmen"].setText(f"{core.TYPES['carmen'][0]} {name}")
        self.lblConn.setText(f"✔ Conectado · {len(self.matches)} partidos")
        self.cmbMatch.blockSignals(True)
        self.cmbMatch.clear()
        for m in self.matches:
            self.cmbMatch.addItem(f"{m.title}  ({len(m.marks)} jugadas)")
        self.cmbMatch.blockSignals(False)
        if self.matches:
            # Abre el último partido con jugadas (si el actual está vacío, el último del historial)
            i = next((i for i, m in enumerate(self.matches) if m.marks), 0)
            self.cmbMatch.setCurrentIndex(i)
            self.on_match(i)

    def match_id(self, m=None) -> str:
        m = m or self.match
        return f"{m.path}|{m.live_start}" if m else ""

    def on_match(self, i):
        if i < 0 or i >= len(self.matches):
            return
        self.match = self.matches[i]
        m = self.match
        res = f"{m.sets[0]}-{m.sets[1]}" if m.sport == "volley" else f"{m.score[0]}-{m.score[1]}"
        det = "  ".join(f"{h}-{a}" for h, a in m.history)
        self.lblMatch.setText(f"{m.home.short} {res} {m.away.short}   {det}")
        # Enlace de YouTube de ese partido (si se guardó); no pisa uno pegado a mano
        cur = self.edYt.text().strip()
        if not cur or cur == getattr(self, "_yt_auto", ""):
            self._yt_auto = f"https://www.youtube.com/watch?v={m.yt}" if m.yt else ""
            self.edYt.setText(self._yt_auto)
        self.sel = None
        self.videos = []
        if self.player:
            self.player.stop()
        self.cur_video = -1
        self.refresh_videos()
        self.refresh_marks()
        self.restore_videos()

    # ================================================================ vídeos
    def remember_videos(self):
        if not self.match:
            return
        store = self.cfg.setdefault("match_videos", {})
        store[self.match_id()] = [{"path": v.path, "start": v.start_ms, "source": v.source} for v in self.videos]
        if len(store) > 40:   # solo los últimos
            for k in list(store)[:-40]:
                del store[k]
        save_cfg(self.cfg)

    def restore_videos(self):
        saved = [x for x in (self.cfg.get("match_videos") or {}).get(self.match_id(), []) if os.path.isfile(x.get("path", ""))]
        if not saved:
            return
        mid = self.match_id()

        def work(s, items):
            out = []
            for x in items:
                v = core.load_video(x["path"])
                v.start_ms, v.source = int(x.get("start") or 0), x.get("source") or v.source
                out.append(v)
            return out
        t = Task(work, saved)
        t.s.done.connect(lambda vids: mid == self.match_id() and self.on_videos(vids, remember=False))
        self.pool.start(t)

    def add_videos(self):
        start = self.cfg.get("video_dir", os.path.expanduser("~"))
        files, _ = QFileDialog.getOpenFileNames(self, "Vídeos del partido", start, "Vídeos (*.mp4 *.mov *.mkv *.m4v);;Todos (*.*)")
        if not files:
            return
        self.cfg["video_dir"] = os.path.dirname(files[0]); save_cfg(self.cfg)
        self.lblYt.setText("Leyendo vídeos…")

        def work(s, fs):
            out = []
            for f in fs:
                meta = {}
                if core.FILE_ID.search(os.path.basename(f)):   # descargado de YouTube: hora y título de YouTube
                    s.log.emit("Preguntando a YouTube por el vídeo…")
                    try:
                        meta = core.prepare_youtube_file(f, lambda m: s.log.emit(m.splitlines()[0][:100]))
                    except Exception as e:  # noqa
                        s.log.emit(str(e)[:100])
                        meta = {}
                v = core.load_video(meta.get("path") or f)
                if meta:
                    v.yt = meta
                    v.start_ms, v.source = (meta["start_ms"], "youtube") if meta.get("start_ms") else (0, "?")
                out.append(v)
            return out
        t = Task(work, files)
        t.s.log.connect(lambda m: self.lblYt.setText(m))
        t.s.done.connect(self.on_added)
        t.s.fail.connect(lambda e: (self.lblYt.setText(""), QMessageBox.warning(self, APP, e)))
        self.pool.start(t)

    def on_added(self, vids):
        self.lblYt.setText("")
        yt = [v for v in vids if getattr(v, "yt", None)]
        rest = [v for v in vids if v not in yt]
        if rest:
            self.on_videos(rest)
        for v in yt:
            self.assign_youtube_video(v)

    def on_videos(self, vids, remember=True):
        known = {v.path for v in self.videos}
        self.videos += [v for v in vids if v.path not in known]
        self.videos.sort(key=lambda v: (v.start_ms or 9e15, os.path.basename(v.path)))
        self.refresh_videos()
        self.refresh_marks()
        if remember:
            self.remember_videos()

    def refresh_videos(self):
        if self.player:
            self.player.stop()
        self.cur_video = -1
        self.tblVid.setRowCount(len(self.videos))
        for r, v in enumerate(self.videos):
            self.tblVid.setItem(r, 0, ro(os.path.basename(v.path)))
            te = QTimeEdit(); te.setDisplayFormat("HH:mm:ss")
            if v.start_ms:
                d = datetime.fromtimestamp(v.start_ms / 1000)
                te.setTime(QTime(d.hour, d.minute, d.second))
            te.timeChanged.connect(lambda tm, row=r: self.on_start_edit(row, tm))
            self.tblVid.setCellWidget(r, 1, te)
            self.tblVid.setItem(r, 2, ro(fmt_dur(v.duration)))
            src = {"nombre": "por el nombre del archivo", "fecha interna": "por la fecha guardada en el vídeo",
                   "directo": "inicio del directo", "youtube": "inicio del directo (YouTube)", "manual": "puesta a mano", "?": "⚠ desconocida: ponla a mano"}.get(v.source, v.source)
            self.tblVid.setItem(r, 3, ro(src))
        self.cmbVid.clear()
        n = len(self.videos)
        for i, v in enumerate(self.videos):
            self.cmbVid.addItem(f"vídeo {i + 1} de {n} · {fmt_time(v.start_ms)}")
        self.cmbVid.setVisible(n > 0)
        self.update_timeline()

    def on_start_edit(self, row, tm):
        v = self.videos[row]
        base = datetime.fromtimestamp(v.start_ms / 1000) if v.start_ms else (
            datetime.fromtimestamp(self.match.ts / 1000) if self.match and self.match.ts else datetime.now())
        new = base.replace(hour=tm.hour(), minute=tm.minute(), second=tm.second())
        v.start_ms = int(new.timestamp() * 1000)
        v.source = "manual"
        self.tblVid.setItem(row, 3, ro("puesta a mano"))
        self.remember_videos()
        self.refresh_marks()

    def remove_video(self):
        r = self.tblVid.currentRow()
        if r < 0 or r >= len(self.videos):
            return QMessageBox.information(self, APP, "Selecciona en la lista el vídeo que quieres quitar.")
        del self.videos[r]
        self.refresh_videos(); self.refresh_marks(); self.remember_videos()

    def mark_youtube(self):
        r = self.tblVid.currentRow()
        if r < 0 or r >= len(self.videos):
            return QMessageBox.information(self, APP, "Selecciona primero el vídeo en la lista.")
        if not self.match or not self.match.live_start:
            return QMessageBox.information(self, APP, "Este partido no tiene guardada la hora de inicio del directo.")
        self.videos[r].start_ms = self.match.live_start
        self.videos[r].source = "directo"
        self.refresh_videos(); self.refresh_marks(); self.remember_videos()

    # ---------------------------------------------------------------- descargar de YouTube
    def yt_dir(self) -> str:
        return os.path.join(self.edOut.text().strip() or os.path.join(os.path.expanduser("~"), "Videos", "VoleyCam"), "Directos")

    def yt_open_dir(self):
        d = self.yt_dir()
        os.makedirs(d, exist_ok=True)
        QDesktopServices.openUrl(QUrl.fromLocalFile(d))

    def yt_download(self):
        url = core.clean_youtube_url(self.edYt.text())
        if not url:
            return QMessageBox.information(self, APP, "Pega el enlace del vídeo de YouTube.")
        self.edYt.setText(url)
        h = 1080 if self.cmbYtRes.currentIndex() == 0 else 720
        self.cfg["yt_res"] = self.cmbYtRes.currentIndex(); save_cfg(self.cfg)
        self.yt_stop = False
        self.btnYt.setEnabled(False); self.btnYtStop.setEnabled(True)
        self.progYt.setValue(0); self.lblYt.setText("Descargando…")
        out_dir = self.yt_dir()

        def work(s, url, h):
            r = core.youtube_download(url, out_dir, h, lambda p: s.progress.emit(p, ""), lambda: self.yt_stop,
                                      lambda line: s.log.emit(line))
            s.progress.emit(100, "Leyendo vídeo…")
            v = core.load_video(r["path"])
            v.yt = r   # id y título del vídeo de YouTube
            if r["start_ms"]:   # hora real en que empezó ese directo, según YouTube
                v.start_ms, v.source = r["start_ms"], "youtube"
            else:               # la fecha interna de una descarga no es la del partido
                v.start_ms, v.source = 0, "?"
            return v

        t = Task(work, url, h)
        t.s.progress.connect(lambda p, txt: (self.progYt.setValue(p), txt and self.lblYt.setText(txt)))
        t.s.log.connect(lambda line: self.lblYt.setText(line[:90]))
        t.s.done.connect(self.on_yt_done)
        t.s.fail.connect(self.on_yt_fail)
        self.pool.start(t)

    def match_for_video(self, v):
        """Partido del canal al que pertenece un vídeo: el que tiene más jugadas dentro, o el directo que empezó a la vez."""
        if not v.start_ms:
            return None
        best, score = None, 0
        for m in self.matches:
            n = sum(1 for mk in m.marks if v.start_ms <= mk.ts <= v.end_ms)
            near = m.live_start and abs(m.live_start - v.start_ms) < 15 * 60 * 1000
            sc = n * 10 + (5 if near else 0)
            if sc > score:
                best, score = m, sc
        return best

    def on_yt_done(self, v):
        self.btnYt.setEnabled(True); self.btnYtStop.setEnabled(False)
        self.lblYt.setText(f"✔ {os.path.basename(v.path)}")
        self.assign_youtube_video(v)

    def assign_youtube_video(self, v):
        """Pone un vídeo de YouTube en su partido (o en uno nuevo si no tiene jugadas en el canal)."""
        m = self.match_for_video(v)
        yt = getattr(v, "yt", {}) or {}
        if m is None:
            m = self.new_match_for(v, yt)   # partido que no se marcó con VoleyCam: partido nuevo
        if m is not None and m is not self.match:
            self.cmbMatch.setCurrentIndex(self.matches.index(m))   # cambia al partido de ese vídeo
        self.on_videos([v])
        if not v.start_ms:
            QMessageBox.information(self, APP, "Vídeo añadido, pero YouTube no dice a qué hora empezó (o no hay conexión).\n\n"
                                               "Pon la hora en la columna «Empieza» para que cuadren las jugadas.")
        elif self.match is not None and self.match.pending is not None:
            QMessageBox.information(self, APP, "Este vídeo no tiene jugadas en el canal, así que se ha creado un partido nuevo para él.\n\n"
                                               "Márcalas en «Ver y marcar» (⭐💜🧱🛡 o teclas 1-4) y pulsa «Guardar»: "
                                               "el partido quedará en el historial del canal.")
            self.go_tab(1)

    def new_match_for(self, v, yt):
        """Crea (sin guardar todavía) un partido para un vídeo que no pertenece a ninguno del canal."""
        from PySide6.QtWidgets import QDialog, QDialogButtonBox
        home, away = core.teams_from_title(yt.get("title", ""))
        dlg = QDialog(self); dlg.setWindowTitle("Partido nuevo")
        f = QFormLayout(dlg)
        f.addRow(QLabel("Este vídeo no tiene jugadas en el canal. Se crea un partido nuevo:"))
        if yt.get("title"):
            t = QLabel(yt["title"], objectName="muted"); t.setWordWrap(True); f.addRow(t)
        edH, edA = QLineEdit(home), QLineEdit(away)
        f.addRow("Local:", edH); f.addRow("Visitante:", edA)
        bb = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        bb.button(QDialogButtonBox.Ok).setText("Crear partido"); bb.button(QDialogButtonBox.Cancel).setText("No, dejarlo en el partido elegido")
        bb.accepted.connect(dlg.accept); bb.rejected.connect(dlg.reject); f.addRow(bb)
        if dlg.exec() != QDialog.Accepted:
            return None
        hn, an = edH.text().strip() or "LOCAL", edA.text().strip() or "VISITANTE"
        hs, as_ = core.short_name(hn), core.short_name(an)
        ts = v.start_ms or int(datetime.now().timestamp() * 1000)
        rec = {"ts": ts, "sport": "volley", "home": {"id": "", "name": hn, "short": hs, "color": "#1e6bff"},
               "away": {"id": "", "name": an, "short": as_, "color": "#ff3b3b"},
               "score": {"h": 0, "a": 0}, "sets": {"h": 0, "a": 0}, "history": [], "finished": True,
               "liveStart": v.start_ms or 0, "ytVideoId": yt.get("id", ""), "source": "estudio"}
        date = datetime.fromtimestamp(ts / 1000).strftime("%d/%m/%Y")
        m = core.Match(f"{hs} - {as_} · {date} (nuevo)", ts, "volley", core.Team(hn, hs, "#1e6bff"), core.Team(an, as_, "#ff3b3b"),
                       (0, 0), (0, 0), [], True, v.start_ms or 0, [], {}, f"matches/_nuevo{ts}/marks", yt=yt.get("id", ""), pending=rec)
        pos = 1 if self.matches and self.matches[0].title == "Partido actual" else 0
        self.matches.insert(pos, m)
        self.cmbMatch.blockSignals(True)
        self.cmbMatch.insertItem(pos, m.title)
        self.cmbMatch.blockSignals(False)
        return m

    def on_yt_fail(self, e):
        self.btnYt.setEnabled(True); self.btnYtStop.setEnabled(False)
        self.progYt.setValue(0)
        if self.yt_stop:
            self.lblYt.setText("Cancelada")
            return
        self.lblYt.setText("Error")
        QMessageBox.warning(self, APP, f"{e}\n\nSi YouTube ha cambiado algo, pulsa «Actualizar descargador» y vuelve a probar.")

    def yt_cancel(self):
        self.yt_stop = True

    def yt_update(self):
        self.lblYt.setText("Actualizando descargador…")
        t = Task(lambda s: core.ytdlp_update())
        t.s.done.connect(lambda txt: self.lblYt.setText(txt[:90]))
        t.s.fail.connect(lambda e: (self.lblYt.setText(""), QMessageBox.warning(self, APP, f"No se pudo actualizar:\n{e}")))
        self.pool.start(t)

    # ================================================================ jugadas
    def sec(self) -> dict:
        b, a = core.hl_seconds(self.channel) if self.channel else (7, 3)
        s = {k: (t[2], t[3]) for k, t in core.TYPES.items()}
        s["jugada"] = s["carmen"] = (b, a)
        return s

    def locate(self, mk):
        """(índice de vídeo, segundos dentro) o None si la jugada no cae en ningún vídeo."""
        for i, v in enumerate(self.videos):
            if v.start_ms and v.start_ms <= mk.ts <= v.end_ms:
                return i, (mk.ts - v.start_ms) / 1000
        return None

    def dirty_count(self) -> int:
        if not self.match:
            return 0
        return sum(1 for x in self.match.marks if x.dirty) + len(self.match.removed) + (1 if self.match.pending is not None else 0)

    def mark_name(self, k):
        return core.player_name(self.channel) if k == "carmen" else core.TYPES[k][1]

    def refresh_marks(self):
        m = self.match
        marks = sorted(m.marks, key=lambda x: x.ts) if m else []
        counts = {k: sum(1 for x in marks if k in (x.tags or {"jugada"})) for k in KINDS}
        self.filterBtns["todas"].setText(f"Todas {len(marks)}")
        for k in KINDS:
            self.filterBtns[k].setText(f"{core.TYPES[k][0]} {counts[k]}")
        vis = [x for x in marks if self.filter == "todas" or self.filter in (x.tags or {"jugada"})]
        self.view_marks = vis
        self.tblMk.blockSignals(True)
        self.tblMk.clearSelection()
        self.tblMk.setRowCount(len(vis))
        for r, mk in enumerate(vis):
            inside = self.locate(mk) is not None
            c = QTableWidgetItem(); c.setFlags(Qt.ItemIsUserCheckable | Qt.ItemIsEnabled | Qt.ItemIsSelectable)
            c.setCheckState(Qt.Checked if mk.enabled else Qt.Unchecked)
            self.tblMk.setItem(r, 0, c)
            self.tblMk.setItem(r, 1, ro(fmt_time(mk.ts)))
            self.tblMk.setItem(r, 2, ro(f"{mk.icon}  {mk.label}"))
            if not inside:
                for col in (1, 2):
                    self.tblMk.item(r, col).setForeground(Qt.gray)
                    self.tblMk.item(r, col).setToolTip("Fuera de los vídeos")
        outside = sum(1 for x in marks if self.locate(x) is None) if self.videos else 0
        self.tblMk.resizeColumnsToContents()
        self.tblMk.horizontalHeader().setSectionResizeMode(2, QHeaderView.Stretch)
        self.tblMk.blockSignals(False)
        inside_n = len(marks) - outside if self.videos else 0
        self.lblList.setText(f"Jugadas ({len(marks)})" + (f" · {inside_n} en los vídeos" if self.videos else " · añade los vídeos en 1 · Preparar"))
        self.btnOutside.setVisible(outside > 0)
        self.btnOutside.setText("🗑 Quitar la jugada que no cae en los vídeos" if outside == 1 else f"🗑 Quitar las {outside} jugadas que no caen en los vídeos")
        if self.sel is not None and m and self.sel not in m.marks:
            self.sel = None
        self.highlight_sel()
        self.update_sel_panel()
        self.update_timeline()
        self.update_dirty()
        self.update_head()
        self.update_counts()

    def update_dirty(self):
        n = self.dirty_count()
        self.btnSave.setEnabled(n > 0)
        self.lblDirty.setText(f"{n} cambio{'' if n == 1 else 's'} sin guardar" if n else "Sin cambios")

    def update_head(self):
        m = self.match
        if not m:
            self.lblHead.setText("")
            return
        res = f"{m.sets[0]}-{m.sets[1]}" if m.sport == "volley" else f"{m.score[0]}-{m.score[1]}"
        nv = len(self.videos)
        self.lblHead.setText(f"{m.home.short} {res} {m.away.short} · {nv} vídeo{'' if nv == 1 else 's'} · {len(m.marks)} jugadas")

    def update_timeline(self):
        i = self.cur_video
        if not (0 <= i < len(self.videos)) or not self.match:
            self.timeline.set_data(0, [], [])
            return
        v = self.videos[i]
        marks = []
        for mk in self.match.marks:
            loc = self.locate(mk)
            if loc and loc[0] == i and (self.filter == "todas" or self.filter in (mk.tags or {"jugada"})):
                marks.append((int(loc[1] * 1000), COLORS[mk.main], mk))
        clips = [(int(c.start * 1000), int(c.end * 1000)) for c in core.clips_for(self.match.marks, self.videos, self.sec()) if c.video == i]
        self.timeline.sel = self.sel
        self.timeline.set_data(int(v.duration * 1000), marks, clips)

    def highlight_sel(self):
        if self.sel in self.view_marks:
            r = self.view_marks.index(self.sel)
            self.tblMk.blockSignals(True)
            if r not in {i.row() for i in self.tblMk.selectedIndexes()}:
                self.tblMk.selectRow(r)
            self.tblMk.scrollToItem(self.tblMk.item(r, 1))
            self.tblMk.blockSignals(False)

    def update_sel_panel(self):
        mk = self.sel
        on = mk is not None
        for w in list(self.selKind.values()) + [self.spBefore, self.spAfter, self.btnSelDel, self.chkSelOn]:
            w.setEnabled(on)
        if not on:
            self.lblSel.setText("Toca una jugada de la lista o de la barra")
            for k, b in self.selKind.items():
                b.setChecked(False); b.setText(core.TYPES[k][0])
            return
        loc = self.locate(mk)
        where = f"vídeo {loc[0] + 1} · {fmt_dur(loc[1])}" if loc else "fuera de los vídeos"
        self.lblSel.setText(f"<b>{mk.icon} {mk.label}</b> · {fmt_time(mk.ts)}<br><span style='color:#5E6E85'>{where}</span>")
        tags = mk.tags or {"jugada"}
        for k, b in self.selKind.items():
            b.setChecked(k in tags)
            b.setText(f"{core.TYPES[k][0]} {self.mark_name(k)}" if k in tags else core.TYPES[k][0])
        b, a = core.mark_window(mk, self.sec())
        for sp, val in ((self.spBefore, b), (self.spAfter, a)):
            sp.blockSignals(True); sp.setValue(val); sp.blockSignals(False)
        self.chkSelOn.blockSignals(True); self.chkSelOn.setChecked(mk.enabled); self.chkSelOn.blockSignals(False)

    def select_mark(self, mk, play=True):
        self.sel = mk
        self.highlight_sel()
        self.update_sel_panel()
        self.timeline.sel = mk
        self.timeline.update()
        loc = self.locate(mk)
        if loc and self.player:
            b, a = core.mark_window(mk, self.sec())
            self.play_end = int((loc[1] + a) * 1000) if play else 0
            self.load_video_idx(loc[0], int(max(0, loc[1] - b) * 1000), play)

    def set_filter(self, k):
        self.filter = k
        self.refresh_marks()

    def on_list_click(self, r, c):
        if 0 <= r < len(self.view_marks) and c != 0:
            if QApplication.keyboardModifiers() & (Qt.ControlModifier | Qt.ShiftModifier):
                return   # selección múltiple: no salta
            self.select_mark(self.view_marks[r], play=True)

    def on_list_check(self, item):
        r = item.row()
        if item.column() == 0 and r < len(self.view_marks):
            self.view_marks[r].enabled = item.checkState() == Qt.Checked
            if self.view_marks[r] is self.sel:
                self.update_sel_panel()
            self.update_timeline(); self.update_counts()

    def check_all(self, on):
        for mk in self.view_marks:
            mk.enabled = on
        self.refresh_marks()

    def toggle_kind(self, k):
        mk = self.sel
        if mk is None:
            return
        tags = set(mk.tags or {"jugada"}) ^ {k}
        mk.tags = tags or {"jugada"}
        mk.dirty = True
        self.refresh_marks()

    def on_sel_secs(self, _=None):
        if self.sel is None:
            return
        self.sel.before, self.sel.after = self.spBefore.value(), self.spAfter.value()
        self.update_timeline(); self.update_counts()

    def on_sel_enabled(self, on):
        if self.sel is None:
            return
        self.sel.enabled = on
        self.refresh_marks()

    def _remove(self, marks):
        for mk in marks:
            if mk in self.match.marks:
                self.match.marks.remove(mk)
                if mk.key:
                    self.match.removed.append(mk.key)
        if self.sel in marks:
            self.sel = None
        self.refresh_marks()

    def _remove_and_next(self, sel):
        nxt = None
        if len(sel) == 1 and sel[0] in self.view_marks:   # queda seleccionada la siguiente
            i = self.view_marks.index(sel[0])
            rest = [x for x in self.view_marks if x not in sel]
            nxt = rest[min(i, len(rest) - 1)] if rest else None
        self._remove(sel)
        if nxt is not None:
            self.select_mark(nxt, play=False)

    def delete_current(self):
        if self.match and self.sel is not None:
            self._remove_and_next([self.sel])

    def delete_selected(self):
        if not self.match:
            return
        rows = sorted({i.row() for i in self.tblMk.selectedIndexes()})
        sel = [self.view_marks[r] for r in rows if r < len(self.view_marks)]
        if not sel and self.sel is not None:
            sel = [self.sel]
        if not sel:
            return QMessageBox.information(self, APP, "Selecciona en la lista la jugada o jugadas que quieres quitar (Ctrl o Mayús para varias).")
        if len(sel) > 1 and QMessageBox.question(self, APP, f"¿Quitar {len(sel)} jugadas? Al pulsar «Guardar en el canal» se borrarán también del canal.") != QMessageBox.Yes:
            return
        self._remove_and_next(sel)

    def remove_outside(self):
        if not self.match or not self.videos:
            return
        out = [x for x in self.match.marks if self.locate(x) is None]
        if not out:
            return
        if QMessageBox.question(self, APP, f"¿Quitar las {len(out)} jugadas que no caen dentro de los vídeos (por ejemplo, de otro partido)?\n\n"
                                           "Al pulsar «Guardar en el canal» se borrarán también del canal.") != QMessageBox.Yes:
            return
        self._remove(out)

    def add_mark_here(self, kind: str):
        if not self.match or not self.player or self.cur_video < 0:
            return QMessageBox.information(self, APP, "Primero pon un vídeo (añádelo en 1 · Preparar y pulsa ▶).")
        v = self.videos[self.cur_video]
        if not v.start_ms:
            return QMessageBox.information(self, APP, "Ese vídeo no tiene hora de inicio. Ponla en 1 · Preparar, columna «Empieza».")
        ts = int(v.start_ms + self.player.position())
        last = self.last_added
        if last is not None and last in self.match.marks and abs(ts - last.ts) < 5000:
            # Otro botón justo después: misma jugada con otra etiqueta
            last.tags = set(last.tags or {"jugada"}) | {kind}
            last.dirty = True
            mk = last
        else:
            mk = core.Mark(ts, f"{self.mark_name(kind)} · añadida en Estudio", {kind}, dirty=True)
            self.match.marks.append(mk)
            self.last_added = mk
        self.sel = mk
        self.refresh_marks()

    def nav(self, d):
        vis = self.view_marks
        if not vis:
            return
        if self.sel in vis:
            i = vis.index(self.sel) + d
        else:
            now = self.now_abs()
            if d > 0:
                i = next((j for j, x in enumerate(vis) if x.ts > now + 1000), len(vis))
            else:
                i = max((j for j, x in enumerate(vis) if x.ts < now - 1000), default=-1)
        while 0 <= i < len(vis) and self.locate(vis[i]) is None:   # salta las que no caen en los vídeos
            i += d
        if 0 <= i < len(vis):
            self.select_mark(vis[i], play=True)

    def save_marks(self):
        if not self.match:
            return False
        try:
            m = self.match
            if m.pending is not None:   # partido nuevo: se crea en el historial y luego van sus jugadas
                old_id = self.match_id()
                mid = core.create_match(self.key.text(), m.pending)
                if not mid:
                    raise RuntimeError("El canal no devolvió la clave del partido nuevo")
                m.path, m.pending = f"matches/{mid}/marks", None
                m.title = m.title.replace(" (nuevo)", "")
                i = self.matches.index(m)
                self.cmbMatch.setItemText(i, f"{m.title}  ({len(m.marks)} jugadas)")
                store = self.cfg.get("match_videos") or {}
                store.pop(old_id, None)
                self.remember_videos()
            n = core.save_marks(self.key.text(), self.match.path, self.match.marks, self.match.removed)
            self.match.removed.clear()
            self.update_dirty()
            self.lblDirty.setText(f"✔ Guardados {n} cambios")
            return True
        except Exception as e:
            QMessageBox.warning(self, APP, f"No se pudo guardar:\n{e}")
            return False

    # ================================================================ reproductor
    def playing(self) -> bool:
        return bool(self.player) and self.player.playbackState() == QMediaPlayer.PlayingState

    def now_abs(self) -> int:
        if self.player and 0 <= self.cur_video < len(self.videos):
            return self.videos[self.cur_video].start_ms + self.player.position()
        return 0

    def load_video_idx(self, i, pos_ms, play):
        if not self.player or not (0 <= i < len(self.videos)):
            return
        if self.cur_video != i:
            self.cur_video = i
            self.pending = (pos_ms, play)
            self.player.setSource(QUrl.fromLocalFile(self.videos[i].path))
            self.cmbVid.setCurrentIndex(i)
            self.update_timeline()
        else:
            self.player.setPosition(pos_ms)
            if play:
                self.player.play()
        self.timeline.set_pos(pos_ms)

    def on_media_status(self, st):
        if self.pending and st in (QMediaPlayer.LoadedMedia, QMediaPlayer.BufferedMedia):
            pos, play = self.pending
            self.pending = None
            self.player.setPosition(pos)
            self.on_speed()
            if play:
                self.player.play()
            else:
                self.player.pause()

    def on_pos(self, p):
        if self.play_end and p >= self.play_end:
            self.player.pause()
            self.play_end = 0
        self.timeline.set_pos(p)
        if 0 <= self.cur_video < len(self.videos):
            v = self.videos[self.cur_video]
            self.lblTime.setText(fmt_dur(p / 1000))
            self.lblTime2.setText(f"/ {fmt_dur(v.duration)}  ·  {fmt_time(v.start_ms + p) if v.start_ms else ''}")

    def on_timeline_seek(self, ms):
        if not self.player:
            return
        self.play_end = 0
        if self.cur_video < 0 and self.videos:
            self.load_video_idx(0, ms, False)
        else:
            self.player.setPosition(ms)

    def toggle_play(self):
        if not self.player:
            return
        if self.cur_video < 0:
            if self.videos:
                self.load_video_idx(0, 0, True)
            return
        self.play_end = 0
        if self.playing():
            self.player.pause()
        else:
            self.player.play()

    def seek_rel(self, ms):
        if not self.player or self.cur_video < 0:
            return
        self.play_end = 0
        dur = int(self.videos[self.cur_video].duration * 1000)
        self.player.setPosition(min(max(0, self.player.position() + ms), dur))

    def on_speed(self, _=None):
        if self.player:
            self.player.setPlaybackRate([0.5, 1.0, 1.5, 2.0][self.cmbSpeed.currentIndex()])

    # ================================================================ crear
    def update_counts(self):
        if not self.match:
            return
        sec = self.sec()
        for key, title, filt, _ in core.SUMMARIES:
            n = len(core.clips_for(self.match.marks, self.videos, sec, filt)) if self.videos else 0
            base = self.chk[key].text().split("  (")[0]
            self.chk[key].setText(f"{base}  ({n} tramo{'' if n == 1 else 's'})")
            self.chk[key].setEnabled(n > 0)

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
            return QMessageBox.information(self, APP, "Añade primero los vídeos del partido (1 · Preparar).")
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
        self.busy = True
        self.stop_flag = False
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
        n = self.dirty_count()
        if n:
            r = QMessageBox.question(self, APP, f"Hay {n} cambio(s) en las jugadas sin guardar en el canal. ¿Guardarlos antes de salir?",
                                     QMessageBox.Save | QMessageBox.Discard | QMessageBox.Cancel)
            if r == QMessageBox.Cancel or (r == QMessageBox.Save and not self.save_marks()):
                e.ignore()
                return
        self.stop_flag = True
        self.yt_stop = True
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
