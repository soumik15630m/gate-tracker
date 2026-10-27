import type { Mutation, RecordItem, Schedule } from '../src/core/model';
export function scheduleDefaults(start: string): Schedule;
export function automaticPlanId(subject: string, date: string): string;
export function lectureCredit(sessions: import('../src/core/model').Session[], id: string, date?: string): number;
export function buildSchedule(records: Pick<RecordItem, 'kind' | 'data' | 'deleted'>[], today: string): { writes: Mutation[]; summaries: { subjectId: string; overbooked: number; remaining: number | null; missingDeadline: boolean; rangeTooLong: boolean; scheduled: number }[] };
