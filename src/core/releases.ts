export function hasNewWebRelease(input: unknown, currentScripts: string[]) {
  const value=input as { version?:unknown; bundles?:unknown } | null;
  if(!value || typeof value.version!=='string' || !/^[a-f0-9]{12}$/.test(value.version) || !Array.isArray(value.bundles) || !value.bundles.length || value.bundles.length>20 || !value.bundles.every(p=>typeof p==='string'&&/^\/_expo\/static\/js\/web\/[a-zA-Z0-9._-]+\.js$/.test(p))) throw Error('The release metadata could not be read.');
  return currentScripts.length>0 && value.bundles.some(path=>!currentScripts.includes(path));
}
