/* SportCam — lógica del marcador (sin dependencias, compartida por la web y los tests) */
(function (root) {
  'use strict';

  const other = s => (s === 'h' ? 'a' : 'h');

  function defaultLive() {
    return {
      sport: 'volley',            // 'volley' | 'football'
      homeId: '', awayId: '',
      score: { h: 0, a: 0 },
      sets: { h: 0, a: 0 },
      setNo: 1,
      serve: '',                  // 'h' | 'a' | ''
      history: [],                // [{h,a}] sets terminados
      finished: false,
      rules: { bestOf: 5, setPts: 25, tiePts: 15 },
      clock: { running: false, baseMs: 0, startedAt: 0, dir: 'up', durMs: 40 * 60000, period: '1ª PARTE' },
      banner: { show: false, text: '' },
      brk: { show: false, text: 'TIEMPO MUERTO' },
      spons: { show: true, sec: 10, carousel: false },
      board: { show: true, pos: 'tl' },   // tl | tr | bl | br
      spFull: { show: false, id: '' }      // patrocinador a pantalla completa
    };
  }

  /** Completa campos que falten (Firebase elimina arrays vacíos y objetos vacíos). */
  function normalize(L) {
    const d = defaultLive();
    if (!L || typeof L !== 'object') return d;
    const out = Object.assign({}, d, L);
    for (const k of ['score', 'sets', 'rules', 'clock', 'banner', 'brk', 'spons', 'board', 'spFull']) {
      out[k] = Object.assign({}, d[k], L[k] || {});
    }
    out.history = Array.isArray(L.history) ? L.history.slice()
      : (L.history && typeof L.history === 'object' ? Object.values(L.history) : []);
    return out;
  }

  const clone = o => JSON.parse(JSON.stringify(o));

  function targetFor(L) {
    return L.setNo >= L.rules.bestOf ? L.rules.tiePts : L.rules.setPts;
  }

  function setsToWin(L) { return Math.ceil(L.rules.bestOf / 2); }

  /* ---------- Voleibol ---------- */
  function volleyPoint(L0, s) {
    const L = normalize(clone(L0));
    if (L.finished) return L;
    const o = other(s);
    L.score[s] += 1;
    L.serve = s;
    const t = targetFor(L);
    if (L.score[s] >= t && L.score[s] - L.score[o] >= 2) {
      L.sets[s] += 1;
      L.history.push({ h: L.score.h, a: L.score.a });
      if (L.sets[s] >= setsToWin(L)) {
        L.finished = true;
      } else {
        L.setNo += 1;
        L.score = { h: 0, a: 0 };
        L.serve = '';
      }
    }
    return L;
  }

  function scoreMinus(L0, s) {
    const L = normalize(clone(L0));
    if (L.score[s] > 0) L.score[s] -= 1;
    if (L.finished && L.sport === 'football') L.finished = false;
    return L;
  }

  function setServe(L0, s) {
    const L = normalize(clone(L0));
    L.serve = L.serve === s ? '' : s;
    return L;
  }

  function adjustSets(L0, s, delta) {
    const L = normalize(clone(L0));
    L.sets[s] = Math.max(0, L.sets[s] + delta);
    L.finished = L.sets.h >= setsToWin(L) || L.sets.a >= setsToWin(L);
    return L;
  }

  /* ---------- Fútbol ---------- */
  function goal(L0, s) {
    const L = normalize(clone(L0));
    L.score[s] += 1;
    return L;
  }

  function clockElapsed(clock, now) {
    const c = clock || {};
    return (c.baseMs || 0) + (c.running ? Math.max(0, now - (typeof c.startedAt === 'number' ? c.startedAt : now)) : 0);
  }

  function clockDisplayMs(clock, now) {
    const e = clockElapsed(clock, now);
    return clock && clock.dir === 'down' ? Math.max(0, (clock.durMs || 0) - e) : e;
  }

  function fmtClock(ms) {
    const t = Math.floor(ms / 1000);
    const m = Math.floor(t / 60), s = t % 60;
    return String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0');
  }

  function clockStart(L0, now) {
    const L = normalize(clone(L0));
    if (!L.clock.running) { L.clock.running = true; L.clock.startedAt = now; }
    return L;
  }

  function clockPause(L0, now) {
    const L = normalize(clone(L0));
    if (L.clock.running) {
      L.clock.baseMs = clockElapsed(L.clock, now);
      L.clock.running = false;
      L.clock.startedAt = 0;
    }
    return L;
  }

  function clockReset(L0) {
    const L = normalize(clone(L0));
    L.clock.running = false; L.clock.baseMs = 0; L.clock.startedAt = 0;
    return L;
  }

  /** Suma/resta tiempo transcurrido (deltaMs puede ser negativo). */
  function clockAdjust(L0, deltaMs, now) {
    const L = normalize(clone(L0));
    const e = clockElapsed(L.clock, now);
    const ne = Math.max(0, e + deltaMs);
    L.clock.baseMs += ne - e;
    return L;
  }

  /* ---------- Partido ---------- */
  function newMatch(L0) {
    const L = normalize(clone(L0));
    const d = defaultLive();
    L.score = d.score; L.sets = d.sets; L.setNo = 1; L.serve = '';
    L.history = []; L.finished = false;
    L.clock = Object.assign({}, d.clock, { dir: L.clock.dir, durMs: L.clock.durMs });
    L.brk.show = false;
    return L;
  }

  const api = {
    defaultLive, normalize, volleyPoint, scoreMinus, setServe, adjustSets,
    goal, clockElapsed, clockDisplayMs, fmtClock, clockStart, clockPause, clockReset, clockAdjust,
    newMatch, targetFor, setsToWin
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.SC = api;
})(this);
