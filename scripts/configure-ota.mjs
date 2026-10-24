import { readFileSync, writeFileSync } from 'node:fs';
const [projectId, owner, slug] = process.argv.slice(2);
if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(projectId || '') || !/^[a-zA-Z0-9_-]+$/.test(owner || '') || (slug && !/^[a-zA-Z0-9_-]+$/.test(slug))) {
  console.error('Usage: npm run ota:configure -- <Expo project UUID> <Expo owner> [Expo project slug]'); process.exit(1);
}
const config = JSON.parse(readFileSync('release.config.json', 'utf8'));
writeFileSync('release.config.json', JSON.stringify({ ...config, expoProjectId: projectId, expoOwner: owner, ...(slug ? { expoSlug: slug } : {}) }, null, 2) + '\n');
console.log('Expo project linked in release.config.json. Commit this public configuration, add EXPO_TOKEN to GitHub repository secrets, and build a new APK. Never commit the token.');
