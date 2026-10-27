import { buildSchedule } from '../../shared/planner.mjs';
import { addDays, applyMutations, contentProgress, dateKey, list, subjectFor, type LocalState, type Mutation, type Session, type Topic, type Preferences } from './model';
export function scheduledMutations(state: LocalState, mutations: Mutation[]): Mutation[] {
  let next = applyMutations(state, mutations, false);
  if(!list<Preferences>(next.records,'preferences').length && list<Topic>(next.records,'topic').some(t=>(t.schedulePace||0)>0)) { mutations=[...mutations,{kind:'preferences',data:{id:'preferences',timezone:Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',displayName:'',streakMinimum:1}}]; next=applyMutations(state,mutations,false); }
  const timezone = list<Preferences>(next.records,'preferences')[0]?.timezone || 'UTC';
  const generated = buildSchedule(Object.values(next.records), dateKey(new Date(),timezone)).writes;
  return [...new Map([...mutations,...generated].map(m => [m.data.id,m])).values()];
}
export function lectureForecast(root: Topic, topics: Topic[], sessions: Session[], today: string) {
  const entries = sessions.filter(s => s.lectureSubjectId === root.id), done = contentProgress(topics,root.id).lectures;
  entries.forEach(s => s.lectureIds?.forEach(n => done.add(n)));
  const recent = new Set(entries.filter(s => s.date >= addDays(today,-27) && s.date <= today).flatMap(s => s.lectureIds || [])).size;
  const remaining = root.lectureCount ? Math.max(0,root.lectureCount-done.size) : null;
  return { done: done.size, remaining, recent, finish: remaining != null && remaining > 0 && recent >= 3 ? addDays(today,Math.ceil(remaining * 28 / recent)) : null };
}
export function practiceStats(sessions: Session[]) {
  const correct = sessions.reduce((n,s) => n+(s.practice?.correct || 0),0), incorrect = sessions.reduce((n,s) => n+(s.practice?.incorrect || 0),0), skipped = sessions.reduce((n,s) => n+(s.practice?.skipped || 0),0), attempted = correct+incorrect;
  return { correct, incorrect, skipped, attempted, accuracy: attempted ? Math.round(correct/attempted*100) : null };
}
export function subjectSessions(topics: Topic[], sessions: Session[], root: string) { return sessions.filter(s => s.topicId && subjectFor(topics,s.topicId)?.id === root); }
