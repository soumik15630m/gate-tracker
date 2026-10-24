import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, copyFileSync, readFileSync, writeFileSync } from 'node:fs';
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
const propertiesFile = resolve('android/gradle.properties');
const jvmArgs = process.env.STUDYSPACE_GRADLE_JVMARGS || '-Xmx3072m -XX:MaxMetaspaceSize=1536m -Dfile.encoding=UTF-8';
if (/[\r\n]/.test(jvmArgs)) throw new Error('STUDYSPACE_GRADLE_JVMARGS must be a single line.');
const properties = readFileSync(propertiesFile, 'utf8');
const memorySetting = 'org.gradle.jvmargs=' + jvmArgs;
writeFileSync(propertiesFile, /^org\.gradle\.jvmargs=.*$/m.test(properties) ? properties.replace(/^org\.gradle\.jvmargs=.*$/m, memorySetting) : properties + '\n' + memorySetting + '\n');
run(process.platform === 'win32' ? 'gradlew.bat' : './gradlew', [':app:createReleaseUpdatesResources', '--rerun', ':app:assembleRelease', '-PreactNativeArchitectures=arm64-v8a', '--no-daemon', '--no-parallel', '--max-workers=2'], resolve('android'));
run(process.execPath, ['scripts/verify-native-runtime.cjs']);
mkdirSync('build', { recursive: true });
copyFileSync('android/app/build/outputs/apk/release/app-release.apk', 'build/studyspace-preview.apk');
console.log('Android preview APK: ' + resolve('build/studyspace-preview.apk'));
