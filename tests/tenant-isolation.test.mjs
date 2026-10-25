import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { randomUUID } from 'node:crypto';
import { createStudyServer } from '../server/index.mjs';

test('tenant isolation: identities, colliding IDs, receipts, conflicts, deletion and sessions', async t => {
  const server = process.env.TEST_CLOUD_URL ? null : createStudyServer({ database: ':memory:' });
  if (server) {
    server.listen(0, '127.0.0.1'); await once(server, 'listening');
    t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }));
  }
  const base = process.env.TEST_CLOUD_URL || `http://127.0.0.1:${server.address().port}`;
  const created = [], suffix = randomUUID(), password = 'isolated-test-password-' + randomUUID();
  if (process.env.TEST_CLOUD_URL) t.after(async () => {
    assert.match(process.env.TEST_CLOUD_SCHEMA || '', /^studyspace_test_[a-f0-9]{32}$/);
    const { default: pg } = await import('../server/node_modules/pg/lib/index.js');
    const { cloudConnectionString } = await import('../server/cloud.mjs');
    const pool = new pg.Pool({ connectionString: cloudConnectionString(true), options: `-c search_path=${process.env.TEST_CLOUD_SCHEMA}` });
    try { await pool.query("DELETE FROM users WHERE id=ANY($1::text[]) AND email LIKE 'studyspace-test-%@example.test'", [created]); }
    finally { await pool.end(); }
  });
  async function post(path, body, token, headers = {}) {
    const response = await fetch(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers }, body: JSON.stringify(body) });
    return { status: response.status, body: await response.json(), cache: response.headers.get('cache-control') };
  }
  const sync = (token, changes = [], extra = {}) => post('/api/sync', { cursor: 0, changes, ...extra }, token);
  const change = (kind, data, mutationId = `seed-${data.id}`, baseVersion = 0) => ({ id: data.id, kind, data, mutationId, baseVersion });
  const aEmail = `studyspace-test-tenant-a-${suffix}@example.test`, bEmail = `studyspace-test-tenant-b-${suffix}@example.test`;
  const a = await post('/api/auth/register', { email: aEmail, password });
  assert.equal(a.status, 200); created.push(a.body.user.id);
  const b = await post('/api/auth/register', { email: bEmail, password: password + '-b' });
  assert.equal(b.status, 200); created.push(b.body.user.id);
  const tokenA = a.body.token, tokenB = b.body.token;
  assert.notEqual(tokenA, tokenB); assert.notEqual(a.body.user.id, b.body.user.id);
  const secretA = 'A-private-' + randomUUID(), secretB = 'B-private-' + randomUUID();
  function records(marker) {
    return [
      change('exam', { id: 'shared-exam', name: marker, date: '2027-02-07', weeklyTarget: 10, archived: false }),
      change('topic', { id: 'shared-topic', examId: 'shared-exam', parentId: null, title: marker, order: 0, completedOn: null, confidence: 'unrated' }),
      change('session', { id: 'shared-session', examId: 'shared-exam', topicId: 'shared-topic', topicPath: marker, date: '2026-10-25', minutes: 25, activity: 'Learning', note: marker }),
      change('mock', { id: 'shared-mock', examId: 'shared-exam', name: marker, series: marker, date: '2026-10-25', score: 50, maximum: 100, minutes: 60, sections: [], mistakes: [] }),
      change('task', { id: 'shared-task', examId: 'shared-exam', title: marker, type: 'plan', date: '2026-10-26', done: false, minutes: 45, activity: 'Practice', notes: marker }),
      change('reflection', { id: 'shared-reflection', examId: 'shared-exam', week: '2026-10-19', worked: marker, improve: '', next: marker }),
      change('preferences', { id: 'preferences', timezone: 'Asia/Kolkata', streakMinimum: 20, displayName: marker }),
    ];
  }
  const seededA = await sync(tokenA, records(secretA)); assert.equal(seededA.status, 200); assert.equal(seededA.body.accepted.length, 7);
  await t.test('anonymous, forged and missing authorization cannot read or write', async () => {
    for (const token of [undefined, tokenA.slice(0, -1) + (tokenA.endsWith('x') ? 'y' : 'x'), a.body.user.id]) {
      const response = await sync(token, records('unauthorized'));
      assert.equal(response.status, 401); assert.ok(!JSON.stringify(response.body).includes(secretA));
    }
  });
  await t.test('body, headers and query parameters cannot select a different tenant', async () => {
    const spoofed = await post(`/api/sync?user_id=${a.body.user.id}&userId=${a.body.user.id}`, { cursor: 0, changes: [], user_id: a.body.user.id, userId: a.body.user.id, tenantId: a.body.user.id, account: a.body }, tokenB, { 'X-User-Id': a.body.user.id, 'X-Tenant-Id': a.body.user.id });
    assert.equal(spoofed.status, 200); assert.deepEqual(spoofed.body.records, []); assert.equal(spoofed.body.cursor, 0);
    assert.equal((await sync(tokenB, [], { cursor: seededA.body.cursor })).status, 409);
  });
  await t.test('guessed foreign IDs and versions cannot expose conflicts or change their owner', async () => {
    const guessed = await sync(tokenB, [{ ...records('attacker')[0], mutationId: 'guess-owner', baseVersion: 1 }]);
    assert.equal(guessed.status, 200); assert.equal(guessed.body.conflicts.length, 1);
    assert.equal(guessed.body.conflicts[0].remote.version, 0); assert.equal(guessed.body.conflicts[0].remote.deleted, true);
    assert.ok(!JSON.stringify(guessed.body).includes(secretA));
    const unique = change('exam', { ...records(secretA)[0].data, id: 'a-only-exam' }, 'a-only');
    assert.equal((await sync(tokenA, [unique])).status, 200);
    const foreignChild = change('topic', { ...records('foreign-reference')[1].data, id: 'foreign-child', examId: unique.id }, 'foreign-child');
    assert.equal((await sync(tokenB, [foreignChild])).status, 400);
    assert.equal((await sync(tokenB)).body.records.length, 0);
  });
  await t.test('identical record IDs and mutation IDs remain independent for every entity type', async () => {
    const seededB = await sync(tokenB, records(secretB));
    assert.equal(seededB.status, 200); assert.equal(seededB.body.accepted.length, 7); assert.equal(seededB.body.cursor, 7);
    for (const [token, own, foreign, expected] of [[tokenA, secretA, secretB, 8], [tokenB, secretB, secretA, 7]]) {
      const read = await sync(token); assert.equal(read.body.records.length, expected); assert.match(read.cache, /no-store/);
      assert.ok(JSON.stringify(read.body).includes(own)); assert.ok(!JSON.stringify(read.body).includes(foreign));
      assert.ok(read.body.records.every(record => !('user_id' in record) && !('password' in record.data) && !('token' in record.data)));
    }
    const retryA = await sync(tokenA, records(secretA)); assert.equal(retryA.body.accepted.length, 7);
    const retryB = await sync(tokenB, records(secretB)); assert.equal(retryB.body.accepted.length, 7);
    assert.equal(retryA.body.cursor, 8); assert.equal(retryB.body.cursor, 7);
  });
  await t.test('stale conflicts and deletion return only the authenticated account data', async () => {
    const stale = await sync(tokenB, [change('exam', { ...records(secretB)[0].data, name: 'stale B edit' }, 'stale-b', 0)]);
    assert.equal(stale.body.conflicts[0].remote.data.name, secretB); assert.ok(!JSON.stringify(stale.body).includes(secretA));
    const beforeA = await sync(tokenA), beforeB = await sync(tokenB);
    const deleted = await sync(tokenB, beforeB.body.records.map(record => ({ ...change(record.kind, record.data, 'delete-' + record.id, record.version), deleted: true })));
    assert.equal(deleted.status, 200); assert.ok(deleted.body.records.every(record => record.deleted));
    const afterA = await sync(tokenA); assert.deepEqual(afterA.body, beforeA.body);
    assert.ok(afterA.body.records.every(record => !record.deleted));
  });
  await t.test('logout revokes only the caller session and tokens cannot be selected in a body', async () => {
    const login = await post('/api/auth/login', { email: aEmail, password }); assert.equal(login.status, 200);
    const logout = await post('/api/auth/logout', { token: tokenA, userId: a.body.user.id }, tokenB); assert.equal(logout.status, 200);
    assert.equal((await sync(tokenB)).status, 401); assert.equal((await sync(tokenA)).status, 200);
    assert.equal((await post('/api/auth/logout', {}, tokenA)).status, 200);
    assert.equal((await sync(tokenA)).status, 401); assert.equal((await sync(login.body.token)).status, 200);
    await post('/api/auth/logout', {}, login.body.token);
  });
});
