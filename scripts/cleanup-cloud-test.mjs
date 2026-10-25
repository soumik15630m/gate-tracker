import pg from '../server/node_modules/pg/lib/index.js';
import { readFileSync } from 'node:fs';
import { cloudConnectionString } from '../server/cloud.mjs';

const schema = readFileSync('build/cloud-test-schema.txt', 'utf8').trim();
if (!/^studyspace_test_[a-f0-9]{32}$/.test(schema)) throw new Error('Refusing to remove a non-test schema.');
const pool = new pg.Pool({ connectionString: cloudConnectionString(true) });
try { await pool.query(`DROP SCHEMA IF EXISTS "${schema}" CASCADE`); console.log('Removed isolated test schema.'); }
finally { await pool.end(); }
