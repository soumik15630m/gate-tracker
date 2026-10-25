import { test } from 'node:test';
import assert from 'node:assert/strict';
import { elapsedMs, pauseTimer, readTimer, resumeTimer, timerClock, timerMinutes, timerStorageKey } from '../src/core/stopwatch';

test('stopwatch restores running time after closing and excludes every paused interval', () => {
  let timer = { id: 'session_timer', accumulatedMs: 0, startedAt: 1000 as number | null };
  timer = pauseTimer(timer, 61000);
  assert.equal(elapsedMs(timer, 500000), 60000);
  timer = resumeTimer(timer, 500000);
  assert.equal(resumeTimer(timer, 600000).startedAt, 500000);
  timer = readTimer(JSON.stringify(timer))!;
  assert.equal(elapsedMs(timer, 620000), 180000);
  timer = pauseTimer(timer, 620000);
  assert.equal(elapsedMs(pauseTimer(timer, 999999), 999999), 180000);
  assert.equal(timerClock(elapsedMs(timer)), '00:03:00');
  assert.equal(timerMinutes(timer), 3);
});
test('stopwatch handles clock changes and reports whole-minute rounding without silently capping long sessions', () => {
  assert.equal(elapsedMs({ id: 'session_x', accumulatedMs: 50, startedAt: 1000 }, 0), 50);
  assert.equal(timerMinutes({ id: 'session_x', accumulatedMs: 29000, startedAt: null }), 1);
  assert.equal(timerMinutes({ id: 'session_x', accumulatedMs: 90000, startedAt: null }), 2);
  assert.equal(timerClock(3661000), '01:01:01');
  assert.equal(timerMinutes({ id: 'session_x', accumulatedMs: 25 * 3600000, startedAt: null }), 1500);
  assert.equal(readTimer('{bad'), null);
  assert.equal(readTimer(JSON.stringify({ id: 'session_x', accumulatedMs: -1, startedAt: null })), null);
});
test('unsaved stopwatch keys are separated by account and server', () => {
  const keys = [timerStorageKey(), timerStorageKey('https://one.test', 'alice'), timerStorageKey('https://one.test', 'bob'), timerStorageKey('https://two.test', 'alice')];
  assert.equal(new Set(keys).size, keys.length);
});
