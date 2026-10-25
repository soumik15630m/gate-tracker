export type Kind = 'exam' | 'topic' | 'session' | 'mock' | 'task' | 'reflection' | 'preferences';
export type Confidence = 'unrated' | 'needs-work' | 'getting-there' | 'confident';
export type Activity = 'Learning' | 'Revision' | 'Practice' | 'Mock test';
export interface Exam { id: string; name: string; date: string; weeklyTarget: number; archived: boolean; description?: string }
export interface Topic { id: string; examId: string; parentId: string | null; title: string; order: number; completedOn: string | null; confidence: Confidence; reviewOn?: string; lastReviewedOn?: string; focusOn?: string; lectureCount?: number | null; testCount?: number | null; lectureIds?: number[]; testIds?: number[] }
export interface Session { id: string; examId: string; topicId: string | null; topicPath: string; date: string; minutes: number; activity: Activity; note: string; mockId?: string }
export interface MockSection { name: string; score: number; maximum: number }
export interface Mistake { topicId: string | null; category: 'Concept gap' | 'Recall' | 'Calculation' | 'Misreading' | 'Time pressure'; note: string }
export interface Mock { id: string; examId: string; name: string; series: string; date: string; score: number; maximum: number; minutes: number; correct?: number; incorrect?: number; unattempted?: number; sections: MockSection[]; mistakes: Mistake[] }
export interface Task { id: string; examId: string; title: string; type: 'inbox' | 'milestone' | 'plan'; date: string; done: boolean; topicId?: string | null; minutes?: number | null; activity?: Activity | null; notes?: string | null }
export interface Reflection { id: string; examId: string; week: string; worked: string; improve: string; next: string }
export interface Preferences { id: 'preferences'; timezone: string; streakMinimum: number; displayName: string }
export type Entity = Exam | Topic | Session | Mock | Task | Reflection | Preferences;
export interface RecordItem { id: string; kind: Kind; data: Entity; version: number; deleted: boolean; changedAt?: string }
export type Records = Record<string, RecordItem>;
export interface Mutation { kind: Kind; data: Entity; deleted?: boolean }
export interface Change extends Mutation { id: string; baseVersion: number; mutationId: string }
export interface Conflict { local: Change; remote: RecordItem }
export interface LocalState { records: Records; pending: Record<string, Change>; cursor: number; conflicts: Conflict[] }
export const confidenceNames: Record<Confidence, string> = { unrated: 'Not rated', 'needs-work': 'Needs work', 'getting-there': 'Getting there', confident: 'Confident' };
export const activities: Activity[] = ['Learning', 'Revision', 'Practice', 'Mock test'];
export const mistakeCategories: Mistake['category'][] = ['Concept gap', 'Recall', 'Calculation', 'Misreading', 'Time pressure'];
export function uid(prefix = 'id') { return `${prefix}_${Date.now().toString(36)}_${Array.from({ length: 4 }, () => Math.random().toString(36).slice(2, 9)).join('')}`; }
export function list<T extends Entity>(records: Records, kind: Kind): T[] { return Object.values(records).filter(r => r.kind === kind && !r.deleted).map(r => r.data as T); }
export function dateKey(date = new Date(), timezone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC') {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(date);
  const part = (type: string) => parts.find(p => p.type === type)?.value;
  return `${part('year')}-${part('month')}-${part('day')}`;
}
export function validDate(value: string) { return /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(Date.parse(value)) && new Date(value + 'T12:00:00Z').toISOString().slice(0, 10) === value; }
export function dayNumber(key: string) { return Math.floor(Date.parse(key + 'T12:00:00Z') / 86400000); }
export function addDays(key: string, amount: number) { return new Date((dayNumber(key) + amount) * 86400000).toISOString().slice(0, 10); }
export function weekStart(key: string) { const weekday = new Date(key + 'T12:00:00Z').getUTCDay(); return addDays(key, -(weekday === 0 ? 6 : weekday - 1)); }
export function duration(minutes: number) { const h = Math.floor(minutes / 60), m = Math.round(minutes % 60); return [h ? `${h}h` : '', m ? `${m}m` : ''].filter(Boolean).join(' ') || '0m'; }
export function prettyDate(key: string, year = false) { return validDate(key) ? new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', ...(year ? { year: 'numeric' as const } : {}), timeZone: 'UTC' }).format(new Date(key + 'T12:00:00Z')) : 'No date'; }
export function children(topics: Topic[], parentId: string | null) { return topics.filter(t => t.parentId === parentId).sort((a, b) => a.order - b.order || a.title.localeCompare(b.title)); }
export function descendants(topics: Topic[], id: string): Topic[] {
  const result: Topic[] = [], seen = new Set([id]), queue = [id];
  const byParent = new Map<string | null, Topic[]>();
  for (const topic of topics) { const siblings = byParent.get(topic.parentId) || []; siblings.push(topic); byParent.set(topic.parentId, siblings); }
  while (queue.length) { const parent = queue.pop()!; for (const child of byParent.get(parent) || []) { if (seen.has(child.id)) continue; seen.add(child.id); result.push(child); queue.push(child.id); } }
  return result;
}
export function leaves(topics: Topic[]) { const parents = new Set(topics.map(t => t.parentId)); return topics.filter(t => !parents.has(t.id)); }
export function branchLeaves(topics: Topic[], id: string) { const subtree = [topics.find(t => t.id === id), ...descendants(topics, id)].filter(Boolean) as Topic[]; return leaves(subtree); }
export function topicProgress(topics: Topic[], id?: string) { const items = id ? branchLeaves(topics, id) : leaves(topics); const done = items.filter(t => t.completedOn); return { total: items.length, done: done.length, percent: items.length ? Math.round(done.length / items.length * 100) : 0, date: items.length && done.length === items.length ? done.map(t => t.completedOn!).sort().at(-1)! : null }; }
export function topicPath(topics: Topic[], id: string | null) { const path: string[] = [], seen = new Set<string>(); let node = topics.find(t => t.id === id); while (node && !seen.has(node.id)) { seen.add(node.id); path.unshift(node.title); node = topics.find(t => t.id === node!.parentId); } return path.join(' / '); }
export function canMove(topics: Topic[], id: string, parentId: string | null) { const topic = topics.find(t => t.id === id); if (!topic) return false; if (parentId === null) return true; const parent = topics.find(t => t.id === parentId); return !!parent && parent.examId === topic.examId && parentId !== id && !descendants(topics, id).some(t => t.id === parentId); }
export function branchMinutes(sessions: Session[], topics: Topic[], id: string) { const ids = new Set([id, ...descendants(topics, id).map(t => t.id)]); return sessions.filter(s => s.topicId && ids.has(s.topicId)).reduce((sum, s) => sum + s.minutes, 0); }
export function subjectFor(topics: Topic[], id: string) {
  const byId = new Map(topics.map(t => [t.id, t])), seen = new Set<string>(); let node = byId.get(id);
  while (node?.parentId && !seen.has(node.id)) { seen.add(node.id); node = byId.get(node.parentId); }
  return node;
}
export function contentProgress(topics: Topic[], id: string) {
  const nodes = [topics.find(t => t.id === id), ...descendants(topics, id)].filter(Boolean) as Topic[];
  return { lectures: new Set(nodes.flatMap(t => t.lectureIds || [])), tests: new Set(nodes.flatMap(t => t.testIds || [])) };
}
export function moveTopicMutations(topics: Topic[], id: string, parentId: string | null): Mutation[] {
  if (!canMove(topics, id, parentId)) throw new Error('A topic cannot move inside itself or one of its descendants.');
  const topic = topics.find(t => t.id === id)!, oldSubject = subjectFor(topics, id), newSubject = parentId ? subjectFor(topics, parentId) : topic;
  const changingSubject = oldSubject?.id !== newSubject?.id, nodes = [topic, ...descendants(topics, id)];
  return nodes.filter(t => t.id === id || changingSubject && (t.lectureIds?.length || t.testIds?.length)).map(t => ({ kind: 'topic', data: { ...t, ...(t.id === id ? { parentId, order: Date.now(), ...(parentId ? { lectureCount: null, testCount: null } : {}) } : {}), ...(changingSubject ? { lectureIds: [], testIds: [] } : {}) } }));
}
/** Reopening also releases the numbered content assigned to this branch. */
export function completionMutations(topics: Topic[], id: string, today: string, lectureIds: number[] = [], testIds: number[] = []): Mutation[] {
  const topic = topics.find(t => t.id === id); if (!topic) return [];
  const reopening = topicProgress(topics, id).percent === 100, leafIds = new Set(branchLeaves(topics, id).map(t => t.id));
  const nodes = [topic, ...descendants(topics, id)];
  return nodes.filter(t => leafIds.has(t.id) || t.id === id || reopening && (t.lectureIds?.length || t.testIds?.length)).map(t => ({ kind: 'topic', data: { ...t, completedOn: leafIds.has(t.id) ? reopening ? null : t.completedOn || today : t.completedOn, ...(reopening ? { lectureIds: [], testIds: [] } : t.id === id ? { lectureIds, testIds } : {}) } }));
}
export function planDates(start: string, end = start, weekdays?: number[]) {
  if (!validDate(start) || !validDate(end) || dayNumber(end) < dayNumber(start) || dayNumber(end) - dayNumber(start) > 365) throw new Error('Choose a date range of up to one year.');
  const result: string[] = [];
  for (let day = start; day <= end; day = addDays(day, 1)) if (!weekdays || weekdays.includes(new Date(day + 'T12:00:00Z').getUTCDay())) result.push(day);
  if (!result.length) throw new Error('Choose at least one day in this date range.');
  return result;
}
export function studyDays(sessions: Session[]) { const days: Record<string, number> = {}; for (const session of sessions) days[session.date] = (days[session.date] || 0) + session.minutes; return days; }
export function streaks(sessions: Session[], today: string, minimum = 1) {
  const active = Object.entries(studyDays(sessions)).filter(([day, minutes]) => day <= today && minutes >= minimum).map(([day]) => day).sort();
  const set = new Set(active); let current = 0, cursor = set.has(today) ? today : addDays(today, -1);
  while (set.has(cursor)) { current++; cursor = addDays(cursor, -1); }
  let longest = 0, run = 0, previous = '';
  for (const day of active) { run = previous && dayNumber(day) - dayNumber(previous) === 1 ? run + 1 : 1; longest = Math.max(longest, run); previous = day; }
  return { current, longest, activeDays: active.length };
}
export interface OutlineLine { title: string; depth: number }
export function parseOutline(source: string): OutlineLine[] {
  const lines = source.split(/\r?\n/).filter(s => s.trim());
  if (lines.length > 3000) throw new Error('Import up to 3,000 topics at a time.');
  const stack: number[] = [];
  return lines.map((line, index) => {
    const expanded = line.replace(/\t/g, '    '), indent = expanded.match(/^ */)![0].length;
    const title = expanded.trim().replace(/^(?:[-*+]\s+|\d+[.)]\s+|\[[ xX]\]\s*)/, '').trim();
    if (!title || title.length > 300) throw new Error(`Line ${index + 1}: use a topic name of 1–300 characters.`);
    if (!stack.length) stack.push(indent);
    else if (indent > stack.at(-1)!) stack.push(indent);
    else { while (stack.length > 1 && indent < stack.at(-1)!) stack.pop(); if (indent !== stack.at(-1)) throw new Error(`Line ${index + 1}: align this item with an earlier indentation level.`); }
    return { title, depth: stack.length - 1 };
  });
}
export function outlineTopics(lines: OutlineLine[], examId: string, parentId: string | null = null, offset = 0): Topic[] {
  const parents: string[] = []; let rootOrder = offset;
  return lines.map((line, index) => { const id = uid('topic'); const topic: Topic = { id, examId, parentId: line.depth === 0 ? parentId : parents[line.depth - 1], title: line.title, order: line.depth === 0 ? rootOrder++ : index, completedOn: null, confidence: 'unrated' }; parents[line.depth] = id; return topic; });
}
export function csv(rows: unknown[][]) { return rows.map(row => row.map(value => { let text = String(value ?? ''); if (/^[=+@\-]/.test(text)) text = "'" + text; return '"' + text.replace(/"/g, '""') + '"'; }).join(',')).join('\r\n'); }
export function emptyState(): LocalState { return { records: {}, pending: {}, cursor: 0, conflicts: [] }; }
export function applyMutations(state: LocalState, mutations: Mutation[], queue = true): LocalState {
  const records = { ...state.records }, pending = { ...state.pending };
  for (const mutation of mutations) {
    const id = mutation.data.id, existing = records[id];
    records[id] = { id, kind: mutation.kind, data: mutation.data, deleted: !!mutation.deleted, version: existing?.version || 0 };
    if (queue) pending[id] = { ...mutation, id, baseVersion: pending[id]?.baseVersion ?? existing?.version ?? 0, mutationId: uid('mutation') };
  }
  return { ...state, records, pending };
}
export interface SyncResult { cursor: number; records: RecordItem[]; accepted: { id: string; mutationId: string; version: number }[]; conflicts: Conflict[] }
export function reconcile(state: LocalState, sent: Change[], result: SyncResult): LocalState {
  const records = { ...state.records }, pending = { ...state.pending }, conflicts = [...state.conflicts];
  for (const accepted of result.accepted) {
    if (pending[accepted.id]?.mutationId === accepted.mutationId) delete pending[accepted.id];
    else if (pending[accepted.id]) pending[accepted.id] = { ...pending[accepted.id], baseVersion: accepted.version };
    if (records[accepted.id]) records[accepted.id] = { ...records[accepted.id], version: accepted.version };
  }
  for (const conflict of result.conflicts) {
    const local = pending[conflict.local.id] || conflict.local;
    delete pending[local.id];
    records[local.id] = conflict.remote;
    const index = conflicts.findIndex(c => c.local.id === local.id);
    if (index >= 0) conflicts.splice(index, 1);
    conflicts.push({ local, remote: conflict.remote });
  }
  for (const record of result.records) {
    if (!pending[record.id]) records[record.id] = record;
    else records[record.id] = { ...records[record.id], version: record.version };
  }
  return { records, pending, conflicts, cursor: result.cursor };
}
