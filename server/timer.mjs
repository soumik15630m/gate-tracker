import { randomBytes } from 'node:crypto';
import { validateEntity, validateRelationships } from '../shared/validation.mjs';

const fail = (status, message) => Object.assign(new Error(message), { status });
export const emptyTimerState = () => ({ version: 0, timer: null, lastSavedId: null });
export const unpackTimer = row => row ? { version: row.version, timer: JSON.parse(row.state), lastSavedId: row.last_saved_id } : emptyTimerState();
export const timerReply = state => ({ ...state, serverNow: Date.now() });

// Called while holding the same per-user write lock as ordinary study sync.
export function transitionTimer(input, current, now = Date.now()) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw fail(400, 'Invalid timer request.');
  if (input.action === 'read') return current;
  if (!['start', 'import', 'pause', 'resume', 'finish', 'discard', 'log'].includes(input.action) || !Number.isSafeInteger(input.version) || input.version < 0 || !['Web', 'Android'].includes(input.device)) throw fail(400, 'Invalid timer action.');
  if (input.action === 'log' && input.id && input.id === current.lastSavedId) return current;
  if (input.version !== current.version) throw fail(409, 'The timer changed on another device. Your controls have been refreshed.');
  let timer = current.timer;
  if (input.action === 'start' || input.action === 'import') {
    if (timer) throw fail(409, 'A timer is already active on your account.');
    timer = { id: 'session_' + randomBytes(16).toString('hex'), accumulatedMs: 0, startedAt: now, origin: input.device, updatedBy: input.device, finished: false };
    if (input.action === 'import') {
      const old = input.timer;
      if (current.version !== 0 || !old || typeof old.id !== 'string' || !/^session_[a-zA-Z0-9:_-]{1,160}$/.test(old.id) || !Number.isSafeInteger(old.accumulatedMs) || old.accumulatedMs < 0 || old.accumulatedMs > 31536000000 || !(old.startedAt === null || Number.isSafeInteger(old.startedAt) && old.startedAt >= 0 && old.startedAt <= now + 86400000)) throw fail(400, 'The previous local stopwatch could not be imported.');
      timer = { ...timer, id: old.id, accumulatedMs: old.accumulatedMs, startedAt: old.startedAt };
    }
  } else {
    if (!timer || input.id !== timer.id) throw fail(409, 'This timer was already logged, discarded, or replaced on another device.');
    const accumulatedMs = timer.accumulatedMs + (timer.startedAt === null ? 0 : Math.max(0, now - timer.startedAt));
    if (input.action === 'discard') timer = null;
    else if (input.action === 'log') {
      if (!timer.finished || timer.startedAt !== null) throw fail(409, 'Finish the timer again before logging its time.');
      if (!validateEntity('session', input.entry) || input.entry.id !== timer.id || input.entry.mockId != null) throw fail(400, 'Choose an exam, topic and a valid study duration.');
      timer = null;
    } else if (input.action === 'resume') timer = { ...timer, startedAt: timer.startedAt ?? now, finished: false, updatedBy: input.device };
    else timer = { ...timer, accumulatedMs, startedAt: null, finished: input.action === 'finish', updatedBy: input.device };
  }
  return { version: current.version + 1, timer, lastSavedId: input.action === 'log' ? input.id : current.lastSavedId };
}

export function timerLogWrites(input, records) {
  const byId = new Map(records.map(record => [record.id, record]));
  if (byId.has(input.entry.id)) throw fail(409, 'This stopwatch already has a study entry. Refresh your study log.');
  const writes = [{ id: input.entry.id, kind: 'session', data: input.entry, deleted: false }];
  if (input.review != null) {
    const topic = byId.get(input.entry.topicId);
    if (input.entry.activity !== 'Revision' || !topic || topic.deleted || topic.kind !== 'topic') throw fail(400, 'Choose a valid revision topic.');
    const data = { ...topic.data, lastReviewedOn: input.entry.date, reviewOn: input.review.reviewOn };
    if (!validateEntity('topic', data)) throw fail(400, 'Choose a valid next review date.');
    writes.push({ id: topic.id, kind: 'topic', data, deleted: false });
  }
  for (const write of writes) byId.set(write.id, write);
  if ([...byId.values()].filter(record => !record.deleted).length > 20000) throw fail(400, 'A workspace supports up to 20,000 records.');
  try { validateRelationships([...byId.values()]); } catch (error) { throw fail(400, error.message); }
  return writes;
}
