import { test } from 'node:test';
import assert from 'node:assert/strict';
import { addDays, applyMutations, branchMinutes, canMove, completionMutations, contentProgress, dateKey, emptyState, moveTopicMutations, outlineTopics, parseOutline, planDates, reconcile, streaks, topicProgress, validDate, weekStart, type Change, type Exam, type Session, type Topic } from '../src/core/model';
import { validateBackup, validateEntity, validateRelationships } from '../shared/validation.mjs';
const exam: Exam = { id: 'exam', name: 'My exam', date: '2027-02-07', weeklyTarget: 10, archived: false };
const topic = (id: string, parentId: string | null, completedOn: string | null = null): Topic => ({ id, parentId, completedOn, examId: exam.id, title: id, order: 0, confidence: 'unrated' });
const session = (date: string, minutes = 60, topicId = 'leaf'): Session => ({ id: date, examId: exam.id, topicId, topicPath: topicId, date, minutes, activity: 'Learning', note: '' });

test('subject totals and numbered content roll up without fixed child counts or duplicate credit', () => {
  const root = { ...topic('root', null), lectureCount: 10, testCount: 3 }, a = { ...topic('a', 'root', '2026-10-22'), lectureIds: [1, 2], testIds: [1] }, b = { ...topic('b', 'root'), lectureIds: [2, 3] };
  assert.equal(contentProgress([root, a, b], 'root').lectures.size, 3);
  assert.equal(contentProgress([root, a, b], 'a').tests.size, 1);
  assert.ok(validateEntity('topic', root)); assert.ok(validateEntity('topic', a));
  assert.equal(validateEntity('topic', { ...a, lectureCount: 5 }), false);
  assert.equal(validateEntity('topic', { ...root, lectureCount: 1.5 }), false);
  assert.equal(validateEntity('topic', { ...a, lectureIds: [1, 1] }), false);
  assert.equal(validateEntity('topic', { ...a, lectureIds: null }), false);
  assert.throws(() => validateRelationships([{ kind: 'exam', data: exam }, { kind: 'topic', data: root }, { kind: 'topic', data: { ...a, lectureIds: [11] } }]), /exceed/);
  const changes = completionMutations([root, a, b], 'root', '2026-10-25', [4], [2]).map(m => m.data as Topic);
  assert.equal(changes.find(t => t.id === 'a')?.completedOn, '2026-10-22');
  assert.equal(changes.find(t => t.id === 'b')?.completedOn, '2026-10-25');
  const updated = [root, a, b].map(t => changes.find(n => n.id === t.id) || t);
  assert.equal(contentProgress(updated, 'root').lectures.size, 4);
  const reopened = completionMutations(updated, 'root', '2026-10-26').map(m => m.data as Topic);
  assert.equal(contentProgress(reopened, 'root').lectures.size, 0);
  assert.equal(topicProgress(reopened, 'root').done, 0);
});

test('moving between subjects resets numbered selections but preserves time-independent history', () => {
  const root = { ...topic('root', null), lectureCount: 10 }, other = { ...topic('other', null), lectureCount: 3 }, branch = { ...topic('branch', 'root'), lectureIds: [5] }, leaf = { ...topic('leaf', 'branch', '2026-10-22'), lectureIds: [6] };
  const topics = [root, other, branch, leaf];
  assert.equal((moveTopicMutations(topics, 'leaf', 'root')[0].data as Topic).lectureIds?.[0], 6);
  const moved = moveTopicMutations(topics, 'branch', 'other').map(m => m.data as Topic);
  assert.ok(moved.every(t => !t.lectureIds?.length));
  assert.equal(moved.find(t => t.id === 'leaf')?.completedOn, '2026-10-22');
});

test('detailed plan recurrence picks weekdays and validates time separately from real study', () => {
  assert.deepEqual(planDates('2026-10-26', '2026-11-01', [1, 3, 5]), ['2026-10-26', '2026-10-28', '2026-10-30']);
  assert.deepEqual(planDates('2028-02-29'), ['2028-02-29']);
  assert.throws(() => planDates('2026-10-26', '2026-10-25'));
  assert.throws(() => planDates('2026-10-26', '2028-10-25'));
  assert.throws(() => planDates('2026-10-26', '2026-10-27', []));
  const task = { id: 'plan', examId: exam.id, title: 'Calculus practice', type: 'plan', date: '2026-10-26', done: false, minutes: 45, activity: 'Practice', notes: '15 questions and a review' };
  assert.ok(validateEntity('task', task));
  assert.equal(validateEntity('task', { ...task, date: '' }), false);
  assert.equal(validateEntity('task', { ...task, minutes: 0 }), false);
});

test('shared validation handles ten thousand levels without recursion or repeated ancestor walks', () => {
  const topics = Array.from({ length: 10000 }, (_, i) => topic(`deep-${i}`, i ? `deep-${i - 1}` : null));
  const records = [{ kind: 'exam', data: exam }, ...topics.map(data => ({ kind: 'topic', data }))];
  assert.doesNotThrow(() => validateRelationships(records));
  topics[0].parentId = topics.at(-1)!.id;
  assert.throws(() => validateRelationships(records), /circular/);
});
test('streak stays alive before today’s session and includes every exam', () => {
  assert.deepEqual(streaks([session('2026-10-22'), { ...session('2026-10-23'), examId: 'other' }], '2026-10-24'), { current: 2, longest: 2, activeDays: 2 });
});
test('streak minimum sums entries, ignores future dates, and handles deletion', () => {
  const sessions = [session('2026-10-22'), session('2026-10-23', 10), session('2026-10-23', 15), session('2026-10-24', 5), session('2026-10-25')];
  assert.equal(streaks(sessions, '2026-10-24', 25).current, 2);
  assert.equal(streaks(sessions.filter(s => s.date !== '2026-10-23'), '2026-10-24', 25).current, 0);
});
test('timezone boundaries use the account timezone', () => {
  const instant = new Date('2026-10-23T20:00:00Z');
  assert.equal(dateKey(instant, 'Asia/Kolkata'), '2026-10-24');
  assert.equal(dateKey(instant, 'America/New_York'), '2026-10-23');
});
test('dates handle leap years and reject normalised invalid dates', () => {
  assert.equal(validDate('2026-02-30'), false); assert.equal(validDate('2028-02-29'), true);
  assert.equal(addDays('2028-02-28', 1), '2028-02-29'); assert.equal(weekStart('2026-10-25'), '2026-10-19');
});
test('coverage counts final items and parent completion uses the last closing date', () => {
  const topics = [topic('root', null), topic('group', 'root'), topic('a', 'group', '2026-10-22'), topic('b', 'group', '2026-10-24')];
  assert.deepEqual(topicProgress(topics), { total: 2, done: 2, percent: 100, date: '2026-10-24' });
  assert.equal(topicProgress([...topics, topic('c', 'root')]).percent, 67);
});
test('study time rolls up without double counting', () => {
  const topics = [topic('root', null), topic('branch', 'root'), topic('leaf', 'branch')];
  assert.equal(branchMinutes([session('2026-10-24', 40), session('2026-10-23', 20, 'branch')], topics, 'root'), 60);
});
test('moving a branch cannot create a cycle or cross exam boundaries', () => {
  const topics = [topic('root', null), topic('branch', 'root'), topic('leaf', 'branch'), { ...topic('other', null), examId: 'other-exam' }];
  assert.equal(canMove(topics, 'root', 'leaf'), false); assert.equal(canMove(topics, 'branch', 'branch'), false); assert.equal(canMove(topics, 'branch', 'other'), false); assert.equal(canMove(topics, 'leaf', null), true);
});
test('outline import preserves irregular-width, consistent nesting and deep content', () => {
  const parsed = parseOutline('Math\n    Calculus\n        Integration\n    Algebra\nPhysics\n\tMechanics');
  assert.deepEqual(parsed.map(p => p.depth), [0, 1, 2, 1, 0, 1]);
  const deep = parseOutline(Array.from({ length: 120 }, (_, i) => ' '.repeat(i * 2) + 'Level ' + i).join('\n'));
  const topics = outlineTopics(deep, 'exam'); assert.equal(topics[119].parentId, topics[118].id);
  assert.throws(() => parseOutline('Math\n    Calculus\n  Bad alignment'));
  const appended = outlineTopics(parsed, exam.id, null, Date.now());
  assert.ok(appended.every(t => validateEntity('topic', t)), 'newly appended topics must pass the shared client/server validator');
  assert.ok(validateEntity('topic', { ...topic('new-topic', null), order: Date.now() }));
});
test('edits coalesce before syncing while preserving their server base version', () => {
  let state = applyMutations(emptyState(), [{ kind: 'exam', data: exam }]); const first = state.pending.exam.mutationId;
  state = applyMutations(state, [{ kind: 'exam', data: { ...exam, name: 'Renamed' } }]);
  assert.equal(Object.keys(state.pending).length, 1); assert.equal(state.pending.exam.baseVersion, 0); assert.notEqual(state.pending.exam.mutationId, first);
});
test('a local edit made during sync stays queued against the acknowledged version', () => {
  let state = applyMutations(emptyState(), [{ kind: 'exam', data: exam }]); const sent = state.pending.exam;
  state = applyMutations(state, [{ kind: 'exam', data: { ...exam, name: 'Edited during sync' } }]);
  state = reconcile(state, [sent], { cursor: 1, accepted: [{ id: 'exam', mutationId: sent.mutationId, version: 1 }], conflicts: [], records: [{ id: 'exam', kind: 'exam', data: exam, version: 1, deleted: false }] });
  assert.equal((state.records.exam.data as Exam).name, 'Edited during sync'); assert.equal(state.pending.exam.baseVersion, 1);
});
test('concurrent edits preserve both the local and remote versions', () => {
  let state = applyMutations(emptyState(), [{ kind: 'exam', data: exam }]); const local = state.pending.exam;
  const remote = { id: 'exam', kind: 'exam' as const, data: { ...exam, name: 'Other device' }, version: 2, deleted: false };
  state = reconcile(state, [local], { cursor: 2, accepted: [], conflicts: [{ local, remote }], records: [remote] });
  assert.equal(state.conflicts[0].local.data.id, 'exam'); assert.equal((state.records.exam.data as Exam).name, 'Other device'); assert.equal(Object.keys(state.pending).length, 0);
});
test('backup validation rejects duplicate IDs, invalid values and circular trees', () => {
  assert.equal(validateEntity('session', session('2026-10-24', -10)), false);
  assert.throws(() => validateBackup({ format: 'studyspace', version: 1, records: [{ kind: 'exam', data: exam }, { kind: 'exam', data: exam }] }));
  assert.throws(() => validateBackup({ format: 'studyspace', version: 1, records: [{ kind: 'exam', data: exam }, { kind: 'topic', data: topic('a', 'b') }, { kind: 'topic', data: topic('b', 'a') }] }));
});
