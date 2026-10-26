import { contentProgress, subjectFor, type Session, type Task, type Topic } from './model';
export function lectureDay(sessions: Session[], subjectId: string, date: string) {
  return new Set(sessions.filter(s => s.lectureSubjectId === subjectId && s.date === date).flatMap(s => s.lectureIds || [])).size;
}
export function lecturePlanProgress(plan: Task, sessions: Session[]) {
  const done = lectureDay(sessions, plan.topicId || '', plan.date), target = plan.lectureTarget || 0;
  return { done, target, remaining: Math.max(0, target - done), complete: target > 0 && done >= target };
}
export function availableLectures(topics: Topic[], sessions: Session[], topicId: string, excludingSession?: string) {
  const subject = subjectFor(topics, topicId);
  const used = subject ? contentProgress(topics, subject.id).lectures : new Set<number>();
  for (const session of sessions) if (session.id !== excludingSession && session.lectureSubjectId === subject?.id) for (const n of session.lectureIds || []) used.add(n);
  return { subject, used };
}
