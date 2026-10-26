import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validateEntity, validateRelationships } from '../shared/validation.mjs';
import { lectureDay, lecturePlanProgress } from '../src/core/lectures';
import { timerLogWrites } from '../server/timer.mjs';
import type { Session, Task, Topic } from '../src/core/model';
const subject: Topic = { id: 'subject', examId: 'exam', title: 'Networks', parentId: null, order: 0, completedOn: null, confidence: 'unrated', lectureCount: 3 };
const leaf: Topic = { ...subject, id: 'leaf', parentId: subject.id, title: 'Packet switching', lectureCount: undefined };
const plan: Task = { id: 'daily', examId: 'exam', title: 'Networks lectures', type: 'plan', date: '2026-10-26', topicId: subject.id, done: false, lectureTarget: 2 };
const entry: Session = { id: 'session_timer', examId: 'exam', topicId: leaf.id, topicPath: 'Networks / Packet switching', date: plan.date, minutes: 45, activity: 'Learning', note: '', lectureIds: [1,2], lectureSubjectId: subject.id };
const seed: any[] = [{ kind: 'exam', id: 'exam', data: { id: 'exam', name: 'Exam', date: '2027-02-07', weeklyTarget: 10, archived: false } }, ...[subject,leaf].map(data => ({ id: data.id, kind: 'topic', data })), { id: plan.id, kind: 'task', data: plan }];
test('daily subject lecture plans update from unique recorded lectures and follow edited dates', () => {
  assert.ok(validateEntity('task', plan)); validateRelationships(seed);
  assert.deepEqual(lecturePlanProgress(plan, []), { done: 0, target: 2, remaining: 2, complete: false });
  assert.equal(lectureDay([entry], subject.id, plan.date), 2);
  assert.equal(lecturePlanProgress(plan, [entry]).complete, true);
  assert.equal(lecturePlanProgress(plan, [{ ...entry, date: '2026-10-27' }]).done, 0);
  assert.equal(lecturePlanProgress(plan, [entry, { ...entry, lectureSubjectId: 'other' }]).done, 2);
  assert.equal(lecturePlanProgress(plan, [entry, entry]).done, 2);
});
test('lecture totals, unique credit and one target per subject per day are enforced', () => {
  assert.equal(validateEntity('session', { ...entry, lectureIds: [1,1] }), false);
  assert.equal(validateEntity('task', { ...plan, lectureTarget: 0 }), false);
  assert.throws(() => validateRelationships([...seed, { kind: 'session', data: { ...entry, lectureIds: [4] } }]), /total/);
  assert.throws(() => validateRelationships([...seed, { kind: 'topic', data: { ...subject, id: 'other-subject' } }, { kind: 'session', data: { ...entry, lectureSubjectId: 'other-subject' } }]), /different subject/);
  assert.throws(() => validateRelationships([...seed, { kind: 'session', data: entry }, { kind: 'session', data: { ...entry, id: 'duplicate' } }]), /already/);
  assert.throws(() => validateRelationships([...seed, { kind: 'task', data: { ...plan, id: 'duplicate-target' } }]), /existing/);
  assert.throws(() => validateRelationships(seed.map(r => r.kind === 'task' ? { ...r, data: { ...plan, lectureTarget: 4 } } : r)), /total/);
});
test('timed logging merges lecture progress atomically without closing topics or losing review changes', () => {
  const writes = timerLogWrites({ entry: { ...entry, activity: 'Revision' }, review: { reviewOn: '2026-11-01' } }, seed);
  const topic = writes.find((w: any) => w.id === leaf.id)!.data;
  assert.deepEqual(topic.lectureIds, [1,2]); assert.equal(topic.completedOn, null);
  assert.equal(topic.reviewOn, '2026-11-01'); assert.equal(topic.lastReviewedOn, entry.date);
  assert.equal(writes.length, 2);
  assert.throws(() => timerLogWrites({ entry: { ...entry, lectureIds: [4] } }, seed), /total/);
  assert.throws(() => timerLogWrites({ entry }, seed.map(r => r.id === leaf.id ? { ...r, data: { ...leaf, lectureIds: [1] } } : r)), /already/);
});
