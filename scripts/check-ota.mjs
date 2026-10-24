import { readFileSync, appendFileSync } from 'node:fs';
const config = JSON.parse(readFileSync('release.config.json', 'utf8'));
const id = process.env.EAS_PROJECT_ID || config.expoProjectId;
const issue = !id || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)
  ? 'OTA is not linked. Run npm run ota:configure -- <project UUID> <owner> or set the EXPO_PROJECT_ID GitHub repository variable.'
  : !process.env.EXPO_TOKEN ? 'Set EXPO_TOKEN as a GitHub Actions repository secret before publishing.'
  : !['production', 'preview'].includes(process.env.EXPO_UPDATES_CHANNEL || config.updateChannel) ? 'Choose the production or preview update channel.' : '';
if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `enabled=${!issue}\n`);
if (issue) {
  if (process.argv.includes('--optional')) { console.log('OTA delivery skipped: ' + issue); if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, '### OTA setup needed\n\n' + issue + '\nNo update was published.\n'); process.exit(0); }
  console.error(issue); process.exit(1);
}
console.log('OTA project, token presence, and channel validated.');
