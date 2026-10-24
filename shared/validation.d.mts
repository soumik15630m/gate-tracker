export function validateEntity(kind: string, data: unknown): boolean;
export function validDate(value: unknown): boolean;
export function validateBackup(input: unknown): import('../src/core/model').Mutation[];
export function validateRelationships(records: { kind: string; data: any; deleted?: boolean }[]): void;
