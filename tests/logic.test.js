const assert = require('assert');
const SC = require('../docs/logic.js');

let L = SC.defaultLive();
// set 1: local 25-23
for (let i = 0; i < 23; i++) { L = SC.volleyPoint(L, 'h'); L = SC.volleyPoint(L, 'a'); }
assert.deepStrictEqual(L.score, { h: 23, a: 23 });
L = SC.volleyPoint(L, 'h'); L = SC.volleyPoint(L, 'h');
assert.deepStrictEqual(L.sets, { h: 1, a: 0 });
assert.deepStrictEqual(L.score, { h: 0, a: 0 });
assert.strictEqual(L.setNo, 2);
assert.deepStrictEqual(L.history, [{ h: 25, a: 23 }]);

// ventaja de 2: 24-24 -> 26-24
L = SC.newMatch(L);
for (let i = 0; i < 24; i++) { L = SC.volleyPoint(L, 'h'); L = SC.volleyPoint(L, 'a'); }
L = SC.volleyPoint(L, 'a');
assert.strictEqual(L.sets.a, 0, 'no gana con 25-24');
L = SC.volleyPoint(L, 'a');
assert.strictEqual(L.sets.a, 1);

// quinto set a 15 y fin de partido
L = SC.newMatch(L);
L.sets = { h: 2, a: 2 }; L.setNo = 5;
assert.strictEqual(SC.targetFor(L), 15);
for (let i = 0; i < 15; i++) L = SC.volleyPoint(L, 'a');
assert.strictEqual(L.finished, true);
assert.deepStrictEqual(L.sets, { h: 2, a: 3 });
assert.deepStrictEqual(L.score, { h: 0, a: 15 }, 'mantiene el marcador final');
const before = JSON.stringify(L);
L = SC.volleyPoint(L, 'h');
assert.strictEqual(JSON.stringify(L), before, 'no suma tras terminar');

// mejor de 3
L = SC.newMatch(SC.defaultLive()); L.rules.bestOf = 3;
L.sets = { h: 1, a: 1 }; L.setNo = 3;
assert.strictEqual(SC.targetFor(L), 15);

// normalize: Firebase sin arrays vacíos
const N = SC.normalize({ score: { h: 3 }, history: { 0: { h: 25, a: 10 } } });
assert.deepStrictEqual(N.score, { h: 3, a: 0 });
assert.deepStrictEqual(N.history, [{ h: 25, a: 10 }]);

// fútbol y reloj
L = SC.defaultLive(); L.sport = 'football';
L = SC.goal(L, 'a'); L = SC.goal(L, 'a'); L = SC.scoreMinus(L, 'a');
assert.deepStrictEqual(L.score, { h: 0, a: 1 });
L = SC.clockStart(L, 1000);
assert.strictEqual(SC.clockElapsed(L.clock, 61000), 60000);
L = SC.clockPause(L, 61000);
assert.strictEqual(SC.clockElapsed(L.clock, 999999), 60000);
L = SC.clockAdjust(L, 60000, 0);
assert.strictEqual(SC.fmtClock(SC.clockDisplayMs(L.clock, 0)), '02:00');
L = SC.clockAdjust(L, -999999, 0);
assert.strictEqual(SC.clockElapsed(L.clock, 0), 0);
L.clock.dir = 'down'; L.clock.durMs = 45 * 60000;
assert.strictEqual(SC.fmtClock(SC.clockDisplayMs(L.clock, 0)), '45:00');
L = SC.clockStart(L, 0); L = SC.clockAdjust(L, 30000, 10000);
assert.strictEqual(SC.clockElapsed(L.clock, 10000), 40000, 'ajuste en marcha');

console.log('OK: todos los tests de lógica pasan');
