import release from './release.config.json';
const projectId = process.env.EAS_PROJECT_ID || process.env.EAS_BUILD_PROJECT_ID || release.expoProjectId || '';
const owner = process.env.EXPO_OWNER || release.expoOwner || undefined;
const channel = process.env.EXPO_UPDATES_CHANNEL || release.updateChannel;
const configured = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(projectId);
if (projectId && !configured) throw new Error('EAS_PROJECT_ID must be a valid Expo project UUID.');
export default {
  expo: {
    name: 'Studyspace', slug: release.expoSlug, owner, version: '1.0.0',
    platforms: ['android', 'web'], scheme: 'studyspace',
    userInterfaceStyle: 'dark', backgroundColor: '#000000',
    android: { package: 'org.studyspace.app', adaptiveIcon: { backgroundColor: '#000000' } },
    web: { bundler: 'metro', name: 'Studyspace', description: 'A quieter space for meaningful progress.', output: 'single' },
    androidStatusBar: { barStyle: 'light-content' },
    plugins: ['expo-font', 'expo-secure-store', ['./plugins/with-local-network.cjs', { allowHttp: process.env.STUDYSPACE_ALLOW_HTTP === '1' }]],
    runtimeVersion: { policy: 'fingerprint' },
    updates: {
      enabled: configured,
      ...(configured ? { url: `https://u.expo.dev/${projectId}` } : {}),
      checkAutomatically: 'ON_LOAD', fallbackToCacheTimeout: 0,
      requestHeaders: { 'expo-channel-name': channel }
    },
    extra: { apiUrl: process.env.EXPO_PUBLIC_API_URL || '', repository: 'https://github.com/soumik15630m/gate-tracker', ...(configured ? { eas: { projectId } } : {}) }
  }
};
