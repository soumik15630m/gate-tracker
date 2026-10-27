const checkpoints=new Map<string,()=>Promise<void>>();
export function registerTimerCheckpoint(key:string,save:()=>Promise<void>) { checkpoints.set(key,save);return ()=>{if(checkpoints.get(key)===save)checkpoints.delete(key);}; }
export async function checkpointTimers() { await Promise.all([...checkpoints.values()].map(save=>save())); }
