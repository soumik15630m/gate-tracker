import { createServer } from 'node:http';
import { createCloudHandler, cloudConnectionString } from '../server/cloud.mjs';
import pg from '../server/node_modules/pg/lib/index.js';
import { randomBytes } from 'node:crypto';
import { writeFileSync } from 'node:fs';

const schema = 'studyspace_test_' + randomBytes(16).toString('hex');
if (!/^studyspace_test_[a-f0-9]{32}$/.test(schema)) throw new Error('Invalid test schema.');
const connectionString = cloudConnectionString(true);
const control = new pg.Pool({ connectionString, max: 1 });
await control.query(`CREATE SCHEMA "${schema}"`);
// A single-schema search path cannot fall back to production/public tables.
const pool = new pg.Pool({ connectionString, options: `-c search_path=${schema}`, max: 4 });
writeFileSync('build/cloud-test-schema.txt', schema);
const handler = createCloudHandler({ pool, onError: error => console.error('Isolated fixture:', error.code, error.message) });
const server = createServer((req, res) => {
  if (req.url === '/_fixture/stop' && req.method === 'POST') { res.writeHead(202); res.end('Stopping isolated fixture'); setImmediate(() => cleanup().catch(() => { process.exitCode = 1; })); }
  else handler(req, res);
});
server.listen(3138, '127.0.0.1', () => console.log('Isolated cloud API fixture on localhost:3138; production tables are excluded.'));
let closing = false;
async function cleanup() {
  if (closing) return; closing = true;
  await new Promise(resolve => { server.close(resolve); server.closeAllConnections(); });
  await pool.end();
  await control.query(`DROP SCHEMA "${schema}" CASCADE`);
  await control.end();
  console.log('Removed isolated test schema.');
}
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => cleanup().then(() => process.exit(0), () => process.exit(1)));
