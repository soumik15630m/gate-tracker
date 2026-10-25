import { createContext, useContext } from 'react';
import type { Exam, Session, Topic, Mock, Activity } from './core/model';
export type Screen = 'Today' | 'Syllabus' | 'Study log' | 'Revision' | 'Mock tests' | 'Insights' | 'Plan' | 'Settings';
export interface Workspace { examId: string; exam?: Exam; exams: Exam[]; topics: Topic[]; sessions: Session[]; mocks: Mock[]; today: string; desktop: boolean; navigate: (screen: Screen) => void; setExamId: (id: string) => void; openLog: (topicId?: string, session?: Session, activity?: Activity) => void; openExam: (exam?: Exam) => void; openMock: (mock?: Mock) => void; completeTopic: (id: string) => void; editContent: (id: string) => void; quickRevision: () => void }
export const WorkspaceContext = createContext<Workspace | null>(null);
export function useWorkspace() { const value = useContext(WorkspaceContext); if (!value) throw new Error('Workspace context is missing.'); return value; }
