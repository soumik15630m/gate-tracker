import type { Mutation, RecordItem } from '../src/core/model';
export function redistributeMissed(records: Pick<RecordItem,'kind'|'data'|'deleted'>[], today:string,start:string,end:string,examId:string): Mutation[];
