import { spawn } from 'node:child_process';
const args = process.argv.slice(2);
const child = spawn(process.execPath, ['node_modules/expo/bin/cli', ...args], { stdio: 'inherit', env: { ...process.env, EXPO_NO_TELEMETRY: '1' } });
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal));
child.on('exit', async code => { if (code === 0 && args[0] === 'export' && args.includes('web')) await import('./finish-web.mjs'); process.exitCode = code || 0; });
