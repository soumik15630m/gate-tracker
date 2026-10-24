const { readFileSync } = require('node:fs');
const { createFingerprintAsync } = require('expo/fingerprint');

(async () => {
  const expected = await createFingerprintAsync(process.cwd(), {
    platforms: ['android'],
    ignorePaths: ['android/**/*', 'ios/**/*'],
  });
  const embedded = readFileSync('android/app/build/generated/assets/createReleaseUpdatesResources/fingerprint', 'utf8').trim();
  if (embedded !== expected.hash) throw new Error(`Android runtime is stale: ${embedded}; expected ${expected.hash}. Rebuild before distributing the APK.`);
  console.log('Android OTA runtime verified: ' + embedded);
})().catch(error => { console.error(error.message); process.exitCode = 1; });
