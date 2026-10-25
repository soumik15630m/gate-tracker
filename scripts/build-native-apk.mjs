import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, copyFileSync } from 'node:fs';
import { resolve, join } from 'node:path';

const env = { ...process.env };
if (process.platform === 'win32') {
  const jdk = 'C:/Program Files/Android/Android Studio/jbr';
  const sdk = resolve(env.LOCALAPPDATA || '', 'Android/Sdk');
  if (!env.JAVA_HOME && existsSync(jdk)) env.JAVA_HOME = jdk;
  if (!env.ANDROID_HOME && existsSync(sdk)) env.ANDROID_HOME = sdk;
}
function run(command, args, cwd = process.cwd()) {
  const result = spawnSync(command, args, { cwd, env, stdio: 'inherit', shell: process.platform === 'win32' && command.endsWith('.bat') });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status || 1);
}
if (!env.NATIVE_KEYSTORE_PATH && !existsSync('signing/development.keystore')) {
  mkdirSync('signing', { recursive: true });
  // Keep the former APK certificate when migrating an existing local checkout.
  if (existsSync('android/app/debug.keystore')) copyFileSync('android/app/debug.keystore', 'signing/development.keystore');
  else run(env.JAVA_HOME ? join(env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'keytool.exe' : 'keytool') : 'keytool', [
    '-genkeypair', '-keystore', resolve('signing/development.keystore'), '-storepass', 'android', '-keypass', 'android',
    '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Studyspace Local Development',
  ]);
}
const debug = process.argv.includes('--debug');
const tasks = process.argv.includes('--test') ? [':app:testDebugUnitTest'] : debug ? [':app:assembleDebug'] : [':app:testReleaseUnitTest', ':app:lintRelease', ':app:assembleRelease'];
run(process.platform === 'win32' ? 'gradlew.bat' : './gradlew', [...tasks, '--no-daemon', ...process.argv.filter(arg => arg.startsWith('-P'))], resolve('native-android'));
if (!process.argv.includes('--test')) {
  mkdirSync('build', { recursive: true });
  const variant = debug ? 'debug' : 'release';
  const output = `build/studyspace-android${debug ? '-debug' : ''}.apk`;
  copyFileSync(`native-android/app/build/outputs/apk/${variant}/app-${variant}.apk`, output);
  console.log(`Native Android APK: ${resolve(output)}`);
}
