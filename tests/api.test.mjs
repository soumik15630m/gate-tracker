import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { createStudyServer } from '../server/index.mjs';
import { randomUUID } from 'node:crypto';
test('authenticated sync, isolation, idempotency, conflicts, validation, and logout', async t => {
  const server = process.env.TEST_CLOUD_URL ? null : createStudyServer({ database: ':memory:' });
  if (server) { server.listen(0, '127.0.0.1'); await once(server, 'listening'); t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); })); }
  const base = process.env.TEST_CLOUD_URL || `http://127.0.0.1:${server.address().port}`;
  const created = [], suffix = randomUUID(), firstEmail = `studyspace-test-first-${suffix}@example.test`, secondEmail = `studyspace-test-second-${suffix}@example.test`;
  if (process.env.TEST_CLOUD_URL) t.after(async () => {
    assert.match(process.env.TEST_CLOUD_SCHEMA || '', /^studyspace_test_[a-f0-9]{32}$/);
    const { default: pg } = await import('../server/node_modules/pg/lib/index.js');
    const { cloudConnectionString } = await import('../server/cloud.mjs');
    const pool = new pg.Pool({ connectionString: cloudConnectionString(true), options: `-c search_path=${process.env.TEST_CLOUD_SCHEMA}` });
    try { await pool.query("DELETE FROM users WHERE id=ANY($1::text[]) AND email LIKE 'studyspace-test-%@example.test'", [created]); }
    finally { await pool.end(); }
  });
  async function request(path, body, token) { const response = await fetch(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body) }); return { status: response.status, body: await response.json() }; }
  const auth = await request('/api/auth/register', { email: firstEmail, password: 'a-long-test-password' }); assert.equal(auth.status, 200); created.push(auth.body.user.id);
  const second = await request('/api/auth/register', { email: secondEmail, password: 'another-test-password' }); assert.equal(second.status, 200); created.push(second.body.user.id);
  assert.equal((await request('/api/sync', { cursor: 0, changes: [] })).status, 401);
  assert.equal((await request('/api/auth/login', { email: firstEmail, password: 'incorrect-password' })).status, 401);
  const change = { id: 'exam', kind: 'exam', data: { id: 'exam', name: 'Exam one', date: '2027-02-07', weeklyTarget: 10, archived: false }, baseVersion: 0, mutationId: 'first-change' };
  const first = await request('/api/sync', { cursor: 0, changes: [change] }, auth.body.token); assert.equal(first.status, 200); assert.equal(first.body.cursor, 1);
  const retry = await request('/api/sync', { cursor: 0, changes: [change] }, auth.body.token); assert.equal(retry.body.cursor, 1); assert.equal(retry.body.accepted.length, 1);
  const isolated = await request('/api/sync', { cursor: 0, changes: [] }, second.body.token); assert.deepEqual(isolated.body.records, []);
  const conflict = await request('/api/sync', { cursor: 1, changes: [{ ...change, mutationId: 'stale-change', data: { ...change.data, name: 'Stale device' } }] }, auth.body.token); assert.equal(conflict.body.conflicts.length, 1); assert.equal(conflict.body.conflicts[0].remote.data.name, 'Exam one');
  const invalid = await request('/api/sync', { cursor: 1, changes: [{ ...change, baseVersion: 1, mutationId: 'bad-value', data: { ...change.data, weeklyTarget: -20 } }] }, auth.body.token); assert.equal(invalid.status, 400);
  const circular = { id: 'topic-a', kind: 'topic', data: { id: 'topic-a', examId: 'exam', parentId: 'topic-a', title: 'Circular', order: 0, confidence: 'unrated', completedOn: null }, mutationId: 'cycle', baseVersion: 0 };
  assert.equal((await request('/api/sync', { cursor: 1, changes: [circular] }, auth.body.token)).status, 400);
  const after = await request('/api/sync', { cursor: 0, changes: [] }, auth.body.token); assert.equal(after.body.cursor, 1); assert.equal(after.body.records.length, 1);
  const parent = { ...circular, id: 'parent', mutationId: 'create-parent', data: { ...circular.data, id: 'parent', parentId: null } }, child = { ...circular, id: 'child', mutationId: 'create-child', data: { ...circular.data, id: 'child', parentId: 'parent' } };
  const tree = await request('/api/sync', { cursor: 1, changes: [parent, child] }, auth.body.token); assert.equal(tree.status, 200);
  const changedChild = await request('/api/sync', { cursor: 3, changes: [{ ...child, baseVersion: 3, mutationId: 'other-device-edit', data: { ...child.data, confidence: 'confident' } }] }, auth.body.token); assert.equal(changedChild.body.cursor, 4);
  const branchDelete = await request('/api/sync', { cursor: 3, changes: [{ ...parent, baseVersion: 2, mutationId: 'delete-parent', deleted: true }, { ...child, baseVersion: 3, mutationId: 'delete-child', deleted: true }] }, auth.body.token);
  assert.equal(branchDelete.status, 200); assert.equal(branchDelete.body.conflicts.length, 2); assert.equal(branchDelete.body.accepted.length, 0); assert.equal(branchDelete.body.cursor, 4);
  const parentStillThere = await request('/api/sync', { cursor: 0, changes: [] }, auth.body.token); assert.equal(parentStillThere.body.records.find(r => r.id === 'parent').deleted, false);
  const resolved = await request('/api/sync', { cursor: 4, changes: branchDelete.body.conflicts.map(c => ({ ...c.local, baseVersion: c.remote.version, mutationId: c.local.mutationId + '-resolved' })) }, auth.body.token); assert.equal(resolved.status, 200); assert.equal(resolved.body.records.filter(r => r.deleted).length, 2);
  const concurrent = await Promise.all(['one', 'two'].map(name => request('/api/sync', { cursor: resolved.body.cursor, changes: [{ ...change, baseVersion: 1, mutationId: `parallel-${name}`, data: { ...change.data, name } }] }, auth.body.token)));
  assert.ok(concurrent.every(result => result.status === 200));
  assert.equal(concurrent.reduce((sum, result) => sum + result.body.accepted.length, 0), 1);
  assert.equal(concurrent.reduce((sum, result) => sum + result.body.conflicts.length, 0), 1);
  const subject = { ...parent, id: 'subject-progress', mutationId: 'subject-totals', data: { ...parent.data, id: 'subject-progress', lectureCount: 20, testCount: 4 } };
  const completedTopic = { ...child, id: 'completed-topic', mutationId: 'topic-content', data: { ...child.data, id: 'completed-topic', parentId: subject.id, completedOn: '2026-10-25', lectureIds: [1, 2, 3], testIds: [1] } };
  const plannedBlock = { id: 'plan-block', kind: 'task', baseVersion: 0, mutationId: 'detailed-plan', data: { id: 'plan-block', examId: 'exam', title: 'Calculus and questions', type: 'plan', date: '2026-10-26', done: false, topicId: completedTopic.id, minutes: 90, activity: 'Practice', notes: 'Solve questions, then revise errors.' } };
  const progress = await request('/api/sync', { cursor: 0, changes: [subject, completedTopic, plannedBlock] }, auth.body.token);
  assert.equal(progress.status, 200); assert.equal(progress.body.accepted.length, 3);
  const roundTrip = await request('/api/sync', { cursor: 0, changes: [] }, auth.body.token);
  assert.deepEqual(roundTrip.body.records.find(r => r.id === completedTopic.id).data.lectureIds, [1, 2, 3]);
  assert.equal(roundTrip.body.records.find(r => r.id === plannedBlock.id).data.notes, plannedBlock.data.notes);
  const legacyRecord = roundTrip.body.records.find(r => r.id === completedTopic.id);
  let current = legacyRecord;
  for (const importance of [5, 2, null]) {
    const rated = await request('/api/sync', { cursor: 0, changes: [{ ...completedTopic, baseVersion: current.version, mutationId: `importance-${importance}`, data: { ...legacyRecord.data, importance } }] }, auth.body.token);
    assert.equal(rated.status, 200);
    current = rated.body.records.find(r => r.id === completedTopic.id);
    const { importance: saved, ...unchanged } = current.data;
    assert.equal(saved, importance); assert.deepEqual(unchanged, legacyRecord.data);
    assert.equal(rated.body.records.length, roundTrip.body.records.length);
    for (const previous of roundTrip.body.records.filter(r => r.id !== completedTopic.id)) assert.deepEqual(rated.body.records.find(r => r.id === previous.id), previous);
    const legacyClientEdit = await request('/api/sync', { cursor: 0, changes: [{ ...completedTopic, baseVersion: current.version, mutationId: `older-client-${importance}`, data: legacyRecord.data }] }, auth.body.token);
    assert.equal(legacyClientEdit.status, 200);
    current = legacyClientEdit.body.records.find(r => r.id === completedTopic.id);
    assert.equal(current.data.importance, importance, 'an older client omitting importance must not erase the saved rating');
  }
  const invalidProgress = { ...completedTopic, id: 'out-of-range', mutationId: 'out-of-range', data: { ...completedTopic.data, id: 'out-of-range', lectureIds: [21] } };
  assert.equal((await request('/api/sync', { cursor: 0, changes: [invalidProgress] }, auth.body.token)).status, 400);
  await request('/api/auth/logout', {}, auth.body.token); assert.equal((await request('/api/sync', { cursor: 0, changes: [] }, auth.body.token)).status, 401);
});
