import { spawn } from 'node:child_process';
const children = [
  spawn(process.execPath, ['server/index.mjs'], { stdio: 'inherit' }),
  spawn(process.execPath, ['node_modules/expo/bin/cli', 'start', '--web', '--port', '8081'], { stdio: 'inherit', env: { ...process.env, EXPO_NO_TELEMETRY: '1' } })
];
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { children.forEach(p => p.kill()); process.exit(); });
children.forEach(p => p.on('exit', code => { if (code) { children.forEach(other => other.kill()); process.exitCode = code; } }));
