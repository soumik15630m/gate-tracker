import { dateKey, addDays, weekStart, uid, type Exam, type Topic, type Session, type Mock, type Task, type Preferences, type Mutation, applyMutations, emptyState } from './model';
export function sampleState() {
  const today = dateKey(), exam: Exam = { id: 'sample-exam', name: 'GATE preparation', date: addDays(today, 106), weeklyTarget: 20, archived: false, description: 'Small, deliberate steps toward something bigger.' };
  const topics: Topic[] = [];
  const add = (id: string, title: string, parentId: string | null, done = false): Topic => { const item: Topic = { id, examId: exam.id, title, parentId, order: topics.length, completedOn: done ? addDays(today, -Math.ceil(Math.random() * 8)) : null, confidence: done ? 'confident' : 'unrated' }; topics.push(item); return item; };
  add('math', 'Engineering Mathematics', null); add('calculus', 'Calculus', 'math'); add('limits', 'Limits & continuity', 'calculus', true); add('differentiation', 'Differentiation', 'calculus', true); add('integration', 'Integration', 'calculus'); add('indefinite', 'Indefinite integrals', 'integration', true); add('definite', 'Definite integrals', 'integration'); add('properties', 'Properties & theorems', 'definite'); add('practice', 'Practice questions', 'definite'); add('linear', 'Linear algebra', 'math'); add('matrices', 'Matrices & determinants', 'linear', true); add('eigen', 'Eigenvalues & eigenvectors', 'linear'); add('probability', 'Probability & statistics', 'math'); add('distributions', 'Probability distributions', 'probability');
  add('algorithms', 'Algorithms', null); add('complexity', 'Asymptotic analysis', 'algorithms', true); add('sorting', 'Sorting & searching', 'algorithms', true); add('dynamic', 'Dynamic programming', 'algorithms'); add('graphs', 'Graph algorithms', 'algorithms');
  add('os', 'Operating Systems', null); add('processes', 'Processes & threads', 'os', true); add('scheduling', 'CPU scheduling', 'os', true); add('memory', 'Memory management', 'os'); add('deadlocks', 'Deadlocks', 'os');
  topics.find(t => t.id === 'properties')!.focusOn = today; topics.find(t => t.id === 'properties')!.confidence = 'getting-there'; topics.find(t => t.id === 'practice')!.focusOn = today;
  topics.find(t => t.id === 'limits')!.reviewOn = today; topics.find(t => t.id === 'eigen')!.reviewOn = addDays(today, -1); topics.find(t => t.id === 'eigen')!.confidence = 'needs-work';
  const sessions: Session[] = [];
  for (let d = 83; d >= 0; d--) {
    if (d > 8 && (d % 7 === 0 || d % 11 === 0)) continue;
    const topic = topics.filter(t => !['math', 'algorithms', 'os'].includes(t.id) && !topics.some(c => c.parentId === t.id))[(d * 3) % 16];
    sessions.push({ id: uid('session'), examId: exam.id, topicId: topic?.id || 'properties', topicPath: topic?.title || 'Properties & theorems', date: addDays(today, -d), minutes: d === 0 ? 100 : 55 + d % 6 * 20, activity: d % 4 === 0 ? 'Revision' : 'Learning', note: '' });
  }
  const mocks: Mock[] = [42, 48, 51, 59, 64].map((score, i) => ({ id: `mock-${i}`, examId: exam.id, name: `Full syllabus · ${i + 1}`, series: 'Full syllabus', date: addDays(today, -35 + i * 7), score, maximum: 100, minutes: 180, sections: [], mistakes: i === 4 ? [{ topicId: 'eigen', category: 'Concept gap', note: 'Revisit diagonalisation and repeat the missed problems.' }] : [] }));
  const tasks: Task[] = [{ id: 'milestone-1', examId: exam.id, title: 'Complete the first syllabus pass', type: 'milestone', date: addDays(today, 45), done: false }, { id: 'milestone-2', examId: exam.id, title: 'Begin full-length mock practice', type: 'milestone', date: addDays(today, 60), done: false }, { id: 'inbox-1', examId: exam.id, title: 'Revisit last mock’s eigenvalue questions', type: 'inbox', date: today, done: false, topicId: 'eigen' }];
  const preferences: Preferences = { id: 'preferences', timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Kolkata', streakMinimum: 1, displayName: '' };
  const mutations: Mutation[] = [{ kind: 'exam', data: exam }, ...topics.map(data => ({ kind: 'topic' as const, data })), ...sessions.map(data => ({ kind: 'session' as const, data })), ...mocks.map(data => ({ kind: 'mock' as const, data })), ...tasks.map(data => ({ kind: 'task' as const, data })), { kind: 'preferences', data: preferences }];
  return applyMutations(emptyState(), mutations, false);
}
