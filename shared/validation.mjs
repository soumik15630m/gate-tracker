export const kinds = ['exam', 'topic', 'session', 'mock', 'task', 'reflection', 'preferences'];
const text = (value, max = 300, required = true) => typeof value === 'string' && value.length <= max && (!required || value.trim().length > 0);
const number = (value, min = 0, max = 1e6) => typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max;
const id = value => typeof value === 'string' && /^[a-zA-Z0-9:_-]{1,180}$/.test(value);
const optionalId = value => value == null || id(value);
export const validDate = value => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(Date.parse(value)) && new Date(value + 'T12:00:00Z').toISOString().slice(0, 10) === value;
const optionalDate = value => value == null || value === '' || validDate(value);
const optionalCount = value => value == null || Number.isInteger(value) && number(value, 0, 10000);
const contentIds = value => value === undefined || Array.isArray(value) && value.length <= 10000 && new Set(value).size === value.length && value.every(n => Number.isInteger(n) && number(n, 1, 10000));
// Older clients omit this new field; only an explicit null should clear a rating.
export function preserveTopicImportance(kind, data, previous) {
  if (kind === 'session' && data.lectureIds === undefined && previous?.lectureIds !== undefined) return { ...data, lectureIds: previous.lectureIds, lectureSubjectId: previous.lectureSubjectId };
  if (kind === 'task' && data.lectureTarget === undefined && previous?.lectureTarget != null) return { ...data, lectureTarget: previous.lectureTarget };
  return kind === 'topic' && data.importance === undefined && previous?.importance !== undefined ? { ...data, importance: previous.importance } : data;
}
export function validateEntity(kind, data) {
  if (!kinds.includes(kind) || !data || typeof data !== 'object' || Array.isArray(data) || !id(data.id)) return false;
  const d = data;
  if (kind !== 'preferences' && !id(d.examId) && kind !== 'exam') return false;
  switch (kind) {
    case 'exam': return text(d.name) && validDate(d.date) && number(d.weeklyTarget, 0, 168) && typeof d.archived === 'boolean' && (d.description == null || text(d.description, 1000, false));
    case 'topic': return text(d.title) && (d.parentId === null || id(d.parentId)) && number(d.order, -Number.MAX_SAFE_INTEGER, Number.MAX_SAFE_INTEGER) && (d.completedOn === null || validDate(d.completedOn)) && ['unrated', 'needs-work', 'getting-there', 'confident'].includes(d.confidence) && optionalDate(d.reviewOn) && optionalDate(d.lastReviewedOn) && optionalDate(d.focusOn) && optionalCount(d.lectureCount) && optionalCount(d.testCount) && (d.parentId === null || d.lectureCount == null && d.testCount == null) && contentIds(d.lectureIds) && contentIds(d.testIds) && (d.importance == null || Number.isInteger(d.importance) && number(d.importance, 1, 5));
    case 'session': return optionalId(d.topicId) && text(d.topicPath, 10000, false) && validDate(d.date) && Number.isInteger(d.minutes) && number(d.minutes, 1, 1440) && ['Learning', 'Revision', 'Practice', 'Mock test'].includes(d.activity) && text(d.note, 10000, false) && optionalId(d.mockId) && contentIds(d.lectureIds) && optionalId(d.lectureSubjectId) && (!(d.lectureIds?.length) || id(d.lectureSubjectId));
    case 'mock': return text(d.name) && text(d.series, 300, false) && validDate(d.date) && number(d.maximum, 0.01) && number(d.score, -d.maximum, d.maximum) && Number.isInteger(d.minutes) && number(d.minutes, 1, 1440) && ['correct', 'incorrect', 'unattempted'].every(k => d[k] == null || (Number.isInteger(d[k]) && number(d[k]))) && Array.isArray(d.sections) && d.sections.length <= 200 && d.sections.every(s => s && typeof s === 'object' && text(s.name) && number(s.maximum, 0.01) && number(s.score, -s.maximum, s.maximum)) && Array.isArray(d.mistakes) && d.mistakes.length <= 300 && d.mistakes.every(m => m && typeof m === 'object' && optionalId(m.topicId) && ['Concept gap', 'Recall', 'Calculation', 'Misreading', 'Time pressure'].includes(m.category) && text(m.note, 5000, false));
    case 'task': return text(d.title, 1000) && ['inbox', 'milestone', 'plan'].includes(d.type) && typeof d.date === 'string' && optionalDate(d.date) && typeof d.done === 'boolean' && optionalId(d.topicId) && (d.notes == null || text(d.notes, 10000, false)) && (d.minutes == null || Number.isInteger(d.minutes) && number(d.minutes, 1, 1440)) && (d.activity == null || ['Learning', 'Revision', 'Practice', 'Mock test'].includes(d.activity)) && (d.lectureTarget == null || Number.isInteger(d.lectureTarget) && number(d.lectureTarget, 1, 10000) && d.type === 'plan' && id(d.topicId)) && (d.type !== 'plan' || validDate(d.date) && (d.lectureTarget != null || d.minutes != null && d.activity != null));
    case 'reflection': return validDate(d.week) && ['worked', 'improve', 'next'].every(k => text(d[k], 10000, false));
    case 'preferences': { if (!text(d.timezone, 100)) return false; try { new Intl.DateTimeFormat('en', { timeZone: d.timezone }); } catch { return false; } return d.id === 'preferences' && number(d.streakMinimum, 1, 1440) && Number.isInteger(d.streakMinimum) && text(d.displayName, 100, false); }
    default: return false;
  }
}
export function validateBackup(input) {
  if (!input || input.format !== 'studyspace' || input.version !== 1 || !Array.isArray(input.records) || input.records.length > 20000) throw new Error('Choose a Studyspace version 1 backup (up to 20,000 records).');
  const ids = new Set();
  for (const record of input.records) { if (!record || !validateEntity(record.kind, record.data) || ids.has(record.data.id)) throw new Error('The backup contains an invalid or duplicate record.'); ids.add(record.data.id); }
  validateRelationships(input.records);
  return input.records;
}
export function validateRelationships(records) {
  validateLectureRecords(records);
  const live = records.filter(r => !r.deleted), exams = new Set(live.filter(r => r.kind === 'exam').map(r => r.data.id));
  const topics = new Map(live.filter(r => r.kind === 'topic').map(r => [r.data.id, r.data]));
  for (const { kind, data } of live) {
    if (kind !== 'preferences' && kind !== 'exam' && !exams.has(data.examId)) throw new Error('A record refers to a missing exam.');
  }
  const checked = new Set(), roots = new Map();
  for (const topic of topics.values()) {
    if (checked.has(topic.id)) continue;
    const path = new Set(); let node = topic;
    while (node && !checked.has(node.id)) {
      if (path.has(node.id)) throw new Error('The syllabus has an invalid parent or circular branch.');
      path.add(node.id);
      if (node.parentId === null) break;
      node = topics.get(node.parentId);
      if (!node || node.examId !== topic.examId) throw new Error('The syllabus has an invalid parent or circular branch.');
    }
    const root = roots.get(node?.id) || node;
    for (const id of path) { checked.add(id); roots.set(id, root); }
  }
  for (const topic of topics.values()) {
    const subject = roots.get(topic.id);
    if ((topic.lectureIds || []).some(n => n > (subject?.lectureCount || 0)) || (topic.testIds || []).some(n => n > (subject?.testCount || 0))) throw new Error('Recorded lecture or test numbers exceed the subject total.');
  }
}

// Lecture history is bounded by subject totals and cannot credit the same lecture twice.
export function validateLectureRecords(records) {
  const topics = new Map(records.filter(r => r.kind === 'topic' && !r.deleted).map(r => [r.data.id, r.data]));
  const removed = new Set(records.filter(r => r.kind === 'topic' && r.deleted).map(r => r.data.id));
  const credited = new Set(), targets = new Set();
  for (const record of records.filter(r => !r.deleted)) {
    const d = record.data;
    if (record.kind === 'session' && d.lectureIds?.length) {
      const subject = topics.get(d.lectureSubjectId);
      if (!subject) { if (removed.has(d.lectureSubjectId)) continue; throw new Error('Choose a subject for completed lectures.'); }
      if (subject.parentId !== null || subject.examId !== d.examId || d.lectureIds.some(n => n > (subject.lectureCount || 0))) throw new Error('Completed lectures exceed the subject total.');
      let selected = topics.get(d.topicId); const ancestors = new Set();
      while (selected?.parentId && !ancestors.has(selected.id)) { ancestors.add(selected.id); selected = topics.get(selected.parentId); }
      if (selected && selected.id !== subject.id) throw new Error('Completed lectures belong to a different subject than the selected topic.');
      for (const n of d.lectureIds) { const key = `${subject.id}:${n}`; if (credited.has(key)) throw new Error('This lecture already has a study log. Choose unlogged lectures.'); credited.add(key); }
    }
    if (record.kind === 'task' && d.lectureTarget != null) {
      const subject = topics.get(d.topicId);
      if (!subject && removed.has(d.topicId)) continue;
      if (!subject || subject.parentId !== null || subject.examId !== d.examId || d.lectureTarget > (subject.lectureCount || 0)) throw new Error('Daily lecture targets must fit the subject lecture total.');
      const key = `${d.topicId}:${d.date}`; if (targets.has(key)) throw new Error('Edit the existing lecture target for this subject and day.'); targets.add(key);
    }
  }
}
