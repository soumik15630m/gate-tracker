// Read-only production check: never signs in or writes preparation records.
const origins = ['https://gate-tracker-blond-two.vercel.app', 'https://stkgatetracker.vercel.app'];
const failures = [];
let checked = 0;
async function check(server, path, options, expectedStatus, expectedOrigin) {
  checked++;
  try {
    const response = await fetch(server + path, { ...options, redirect: 'manual', cache: 'no-store', signal: AbortSignal.timeout(20000) });
    const location = response.headers.get('location');
    const allowed = response.headers.get('access-control-allow-origin');
    await response.arrayBuffer();
    if (response.status !== expectedStatus || location || allowed !== expectedOrigin) {
      failures.push(`${options.method || 'GET'} ${server}${path} from ${options.headers?.Origin || 'native client'}: status=${response.status}, redirect=${location}, allowedOrigin=${allowed}; expected ${expectedStatus} and ${expectedOrigin}`);
    }
  } catch (error) { failures.push(`${server}${path}: ${error.message}`); }
}
for (const server of origins) {
  await check(server, '/api/health', {}, 200, null);
  for (const origin of origins) {
    for (const path of ['/api/sync', '/api/timer', '/api/auth/login']) {
      await check(server, path, { method: 'OPTIONS', headers: { Origin: origin, 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'authorization,content-type' } }, 204, origin);
    }
    for (const path of ['/api/sync', '/api/timer']) {
      await check(server, path, { method: 'POST', headers: { Origin: origin, 'Content-Type': 'application/json' }, body: JSON.stringify(path.endsWith('timer') ? { action: 'read' } : { cursor: 0, changes: [] }) }, 401, origin);
    }
  }
  await check(server, '/api/sync', { method: 'OPTIONS', headers: { Origin: 'https://untrusted.example', 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'authorization,content-type' } }, 403, null);
}
if (failures.length) {
  console.error(failures.join('\n'));
  console.error('Keep both project domains as direct production aliases with no redirects. Include both exact origins in production ALLOWED_ORIGINS, then redeploy. Do not change account storage keys or reset databases to fix domain routing.');
  process.exitCode = 1;
} else console.log(`Passed ${checked} checks: both sync aliases answer directly, both browser origins are allowed, authentication remains required, and untrusted origins are blocked.`);
