import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, copyFileSync } from 'node:fs';
import { resolve } from 'node:path';
const env = { ...process.env, NODE_ENV: 'production', EXPO_NO_TELEMETRY: '1', EXPO_UPDATES_CHANNEL: process.env.EXPO_UPDATES_CHANNEL || 'preview', STUDYSPACE_ALLOW_HTTP: process.env.STUDYSPACE_ALLOW_HTTP || '1' };
if (process.platform === 'win32') {
  const studioJdk = 'C:/Program Files/Android/Android Studio/jbr';
  if (!env.JAVA_HOME && existsSync(studioJdk)) env.JAVA_HOME = studioJdk;
  const sdk = resolve(process.env.LOCALAPPDATA || '', 'Android/Sdk');
  if (!env.ANDROID_HOME && existsSync(sdk)) env.ANDROID_HOME = sdk;
}
function run(command, args, cwd = process.cwd()) { const result = spawnSync(command, args, { cwd, env, stdio: 'inherit', shell: process.platform === 'win32' && command.endsWith('.bat') }); if (result.error) throw result.error; if (result.status) process.exit(result.status); }
run(process.execPath, ['node_modules/expo/bin/cli', 'prebuild', '--platform', 'android', '--no-install']);
run(process.platform === 'win32' ? 'gradlew.bat' : './gradlew', ['assembleRelease', '-PreactNativeArchitectures=arm64-v8a', '--no-daemon'], resolve('android'));
mkdirSync('build', { recursive: true });
copyFileSync('android/app/build/outputs/apk/release/app-release.apk', 'build/studyspace-preview.apk');
console.log('Android preview APK: ' + resolve('build/studyspace-preview.apk'));
