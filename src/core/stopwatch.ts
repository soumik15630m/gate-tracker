export interface StudyTimer { id: string; accumulatedMs: number; startedAt: number | null; origin?: string; updatedBy?: string; finished?: boolean }
export function elapsedMs(timer: StudyTimer, now = Date.now()): number {
  return timer.accumulatedMs + (timer.startedAt === null ? 0 : Math.max(0, now - timer.startedAt));
}
export function pauseTimer(timer: StudyTimer, now = Date.now()): StudyTimer {
  return { ...timer, accumulatedMs: elapsedMs(timer, now), startedAt: null };
}
export function resumeTimer(timer: StudyTimer, now = Date.now()): StudyTimer {
  return timer.startedAt === null ? { ...timer, startedAt: now } : timer;
}
export function timerMinutes(timer: StudyTimer, now = Date.now()): number { return Math.max(1, Math.round(elapsedMs(timer, now) / 60000)); }
export function timerClock(ms: number): string {
  const seconds = Math.floor(Math.max(0, ms) / 1000);
  return [Math.floor(seconds / 3600), Math.floor(seconds / 60) % 60, seconds % 60].map(n => String(n).padStart(2, '0')).join(':');
}
export function readTimer(value: string | null): StudyTimer | null {
  try {
    const t = JSON.parse(value || 'null');
    return t && typeof t.id === 'string' && t.id.startsWith('session_') && Number.isFinite(t.accumulatedMs) && t.accumulatedMs >= 0 && (t.startedAt === null || Number.isFinite(t.startedAt) && t.startedAt >= 0) ? t : null;
  } catch { return null; }
}
export function timerStorageKey(server?: string, userId?: string): string {
  return `studyspace.stopwatch.v1.${server && userId ? JSON.stringify([server, userId]) : 'local'}`;
}
