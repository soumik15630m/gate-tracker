import pg from 'pg';
import { attachDatabasePool } from '@vercel/functions';
import { randomBytes, scrypt as derive, timingSafeEqual, createHash } from 'node:crypto';
import { promisify } from 'node:util';
import { preserveTopicImportance, validateEntity, validateRelationships } from '../shared/validation.mjs';

const scrypt = promisify(derive);
const digest = value => createHash('sha256').update(value).digest('hex');
const fail = (status, message) => Object.assign(new Error(message), { status });
export function cloudConnectionString(direct = false) {
  const source = direct ? process.env.DATABASE_URL_UNPOOLED || process.env.POSTGRES_URL_NON_POOLING || process.env.DATABASE_URL || process.env.POSTGRES_URL : process.env.DATABASE_URL || process.env.POSTGRES_URL;
  if (!source) throw fail(503, 'Cloud sync is not configured yet. Your local data is safe.');
  const url = new URL(source);
  if (['prefer', 'require', 'verify-ca'].includes(url.searchParams.get('sslmode'))) url.searchParams.set('sslmode', 'verify-full');
  return url.toString();
}
const unpack = row => ({ id: row.id, kind: row.kind, data: JSON.parse(row.data), deleted: !!row.deleted, version: row.version, changedAt: row.changed_at });
const schema = `
  CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, email TEXT UNIQUE NOT NULL, password TEXT NOT NULL, salt TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0);
  CREATE TABLE IF NOT EXISTS sessions (token TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, expires BIGINT NOT NULL);
  CREATE TABLE IF NOT EXISTS records (user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, id TEXT NOT NULL, kind TEXT NOT NULL, data TEXT NOT NULL, deleted INTEGER NOT NULL, version INTEGER NOT NULL, changed_at TEXT NOT NULL, PRIMARY KEY(user_id,id));
  CREATE TABLE IF NOT EXISTS receipts (user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, mutation_id TEXT NOT NULL, record_id TEXT NOT NULL, version INTEGER NOT NULL, PRIMARY KEY(user_id,mutation_id));
  CREATE TABLE IF NOT EXISTS auth_buckets (id TEXT PRIMARY KEY, attempts INTEGER NOT NULL, reset_at BIGINT NOT NULL);
  CREATE INDEX IF NOT EXISTS records_cursor ON records(user_id,version);
  CREATE INDEX IF NOT EXISTS sessions_expiry ON sessions(expires);
  CREATE INDEX IF NOT EXISTS auth_buckets_expiry ON auth_buckets(reset_at);`;

async function body(req) {
  let value;
  if (req.body !== undefined) {
    const raw = Buffer.isBuffer(req.body) ? req.body.toString() : req.body;
    try { value = typeof raw === 'string' ? JSON.parse(raw) : raw; } catch { throw fail(400, 'Expected a JSON request.'); }
    if (Buffer.byteLength(JSON.stringify(value)) > 8e6) throw fail(413, 'This request is too large. Import smaller batches.');
  } else {
    let size = 0; const chunks = [];
    for await (const chunk of req) { size += chunk.length; if (size > 8e6) throw fail(413, 'This request is too large. Import smaller batches.'); chunks.push(chunk); }
    try { value = JSON.parse(Buffer.concat(chunks).toString()); } catch { throw fail(400, 'Expected a JSON request.'); }
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw fail(400, 'Expected a JSON object.');
  return value;
}
function json(res, status, value) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(value));
}

/** PostgreSQL persists outside the serverless filesystem; the local SQLite server stays independent. */
export function createCloudHandler(options = {}) {
  let pool = options.pool, ready;
  async function database() {
    if (!pool) {
      const connectionString = cloudConnectionString();
      pool = new pg.Pool({ connectionString, max: 4, idleTimeoutMillis: 5000, connectionTimeoutMillis: 10000 });
      pool.on('error', () => console.error('An idle cloud database connection closed.'));
      attachDatabasePool(pool);
    }
    if (!ready) ready = (async () => {
      const migrationPool = options.pool || new pg.Pool({ connectionString: cloudConnectionString(true), max: 1, connectionTimeoutMillis: 10000 });
      let client;
      try {
        client = await migrationPool.connect();
        await client.query('BEGIN');
        await client.query('SELECT pg_advisory_xact_lock(813426, 1)');
        await client.query(schema);
        await client.query('COMMIT');
      } catch (error) { if (client) await client.query('ROLLBACK').catch(() => {}); throw error; }
      finally { client?.release(); if (!options.pool) await migrationPool.end(); }
    })().catch(error => { ready = undefined; throw error; });
    await ready;
    return pool;
  }
  return async function handler(req, res) {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'same-origin');
    res.setHeader('X-Frame-Options', 'DENY');
    const origin = req.headers.origin;
    const allowed = new Set((process.env.ALLOWED_ORIGINS || '').split(',').map(value => value.trim()).filter(Boolean));
    const host = req.headers['x-forwarded-host'] || req.headers.host;
    const ownOrigin = origin === `https://${host}` || !process.env.VERCEL && origin === `http://${host}`;
    if (origin && (ownOrigin || allowed.has(origin))) {
      res.setHeader('Access-Control-Allow-Origin', origin); res.setHeader('Vary', 'Origin');
      res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
      res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    }
    if (req.method === 'OPTIONS') { res.writeHead(origin && !ownOrigin && !allowed.has(origin) ? 403 : 204); return res.end(); }
    try {
      if (origin && !ownOrigin && !allowed.has(origin)) throw fail(403, 'This origin is not allowed by the sync server.');
      const url = new URL(req.url, 'http://localhost');
      const route = req.query?.route || url.searchParams.get('route');
      const path = route ? '/api/' + (Array.isArray(route) ? route.join('/') : route) : url.pathname;
      const db = await database();
      if (path === '/api/health') { await db.query('SELECT 1'); return json(res, 200, { ok: true, service: 'studyspace', version: 1, storage: 'postgres' }); }
      if (req.method !== 'POST') throw fail(405, 'Method not allowed.');
      if (path === '/api/auth/register' || path === '/api/auth/login') {
        const now = Date.now();
        const ip = process.env.VERCEL ? req.headers['x-real-ip'] || req.socket?.remoteAddress || 'unknown' : req.socket?.remoteAddress || 'unknown';
        const bucket = await db.query(`INSERT INTO auth_buckets VALUES($1,1,$2) ON CONFLICT(id) DO UPDATE SET attempts=CASE WHEN auth_buckets.reset_at <= $3 THEN 1 ELSE auth_buckets.attempts+1 END, reset_at=CASE WHEN auth_buckets.reset_at <= $3 THEN $2 ELSE auth_buckets.reset_at END RETURNING attempts`, [digest(String(ip)), now + 60000, now]);
        if (bucket.rows[0].attempts > 20) throw fail(429, 'Too many attempts. Try again in one minute.');
        await db.query('DELETE FROM auth_buckets WHERE reset_at < $1', [now]);
        const input = await body(req), email = typeof input.email === 'string' ? input.email.trim().toLowerCase() : '';
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 254 || typeof input.password !== 'string' || input.password.length < 10 || input.password.length > 256) throw fail(400, 'Enter an email and a password of 10–256 characters.');
        let user = (await db.query('SELECT * FROM users WHERE email=$1', [email])).rows[0];
        if (path.endsWith('register')) {
          if (user) throw fail(409, 'An account with this email already exists.');
          const salt = randomBytes(16).toString('hex'), password = (await scrypt(input.password, salt, 64)).toString('hex'), id = randomBytes(16).toString('hex');
          try { await db.query('INSERT INTO users(id,email,password,salt) VALUES($1,$2,$3,$4)', [id, email, password, salt]); }
          catch (error) { if (error.code === '23505') throw fail(409, 'An account with this email already exists.'); throw error; }
          user = { id, email };
        } else {
          const candidate = await scrypt(input.password, user?.salt || 'missing-user-salt', 64);
          if (!user || !timingSafeEqual(Buffer.from(user.password, 'hex'), candidate)) throw fail(401, 'Email or password is incorrect.');
        }
        const token = randomBytes(32).toString('base64url');
        await db.query('DELETE FROM sessions WHERE expires < $1', [now]);
        await db.query('INSERT INTO sessions VALUES($1,$2,$3)', [digest(token), user.id, now + 30 * 86400000]);
        return json(res, 200, { token, user: { id: user.id, email: user.email } });
      }
      const token = req.headers.authorization?.replace(/^Bearer /, '');
      const session = token && (await db.query('SELECT user_id FROM sessions WHERE token=$1 AND expires>$2', [digest(token), Date.now()])).rows[0];
      if (!session) throw fail(401, 'Sign in again to continue syncing. Your local data is safe.');
      if (path === '/api/auth/logout') { await db.query('DELETE FROM sessions WHERE token=$1', [digest(token)]); return json(res, 200, { ok: true }); }
      if (path !== '/api/sync') throw fail(404, 'API route not found.');
      const input = await body(req);
      if (!Array.isArray(input.changes) || input.changes.length > 20000 || !Number.isSafeInteger(input.cursor) || input.cursor < 0) throw fail(400, 'Invalid sync request.');
      if (input.changes.some(change => !change) || new Set(input.changes.map(change => change.id)).size !== input.changes.length || new Set(input.changes.map(change => change.mutationId)).size !== input.changes.length) throw fail(400, 'Sync changes must have unique record and mutation IDs.');
      for (const change of input.changes) if (!validateEntity(change.kind, change.data) || change.id !== change.data.id || typeof change.mutationId !== 'string' || !change.mutationId.length || change.mutationId.length > 180 || !Number.isSafeInteger(change.baseVersion) || change.baseVersion < 0 || (change.deleted != null && typeof change.deleted !== 'boolean')) throw fail(400, 'A study record is invalid.');
      const client = await db.connect();
      let result;
      try {
        await client.query('BEGIN');
        const user = (await client.query('SELECT revision FROM users WHERE id=$1 FOR UPDATE', [session.user_id])).rows[0];
        if (!user) throw fail(401, 'Sign in again to continue syncing.');
        let revision = user.revision;
        if (input.cursor > revision) throw fail(409, 'The sync cursor is ahead of this server. Reconnect to the original server.');
        if (!input.changes.length) {
          const records = (await client.query('SELECT * FROM records WHERE user_id=$1 AND version>$2 ORDER BY version', [session.user_id, input.cursor])).rows.map(unpack);
          result = { cursor: revision, records, accepted: [], conflicts: [] };
        } else {
          const existing = new Map((await client.query('SELECT * FROM records WHERE user_id=$1', [session.user_id])).rows.map(row => [row.id, unpack(row)]));
          const receipts = new Map((await client.query('SELECT * FROM receipts WHERE user_id=$1 AND mutation_id=ANY($2::text[])', [session.user_id, input.changes.map(change => change.mutationId)])).rows.map(row => [row.mutation_id, row]));
          for (const change of input.changes) if (receipts.has(change.mutationId) && receipts.get(change.mutationId).record_id !== change.id) throw fail(400, 'Mutation ID was reused for a different record.');
          const fresh = input.changes.filter(change => !receipts.has(change.mutationId));
          const accepted = input.changes.filter(change => receipts.has(change.mutationId)).map(change => ({ id: change.id, mutationId: change.mutationId, version: receipts.get(change.mutationId).version }));
          const stale = fresh.some(change => (existing.get(change.id)?.version || 0) !== change.baseVersion);
          if (stale) {
            result = { cursor: revision, records: [...existing.values()].filter(record => record.version > input.cursor).sort((a, b) => a.version - b.version), accepted,
              conflicts: fresh.map(local => ({ local, remote: existing.get(local.id) || { id: local.id, kind: local.kind, data: local.data, deleted: true, version: 0 } })) };
          } else {
            const writes = [], newReceipts = [], changedAt = new Date().toISOString();
            for (const change of fresh) {
              if (existing.has(change.id) && existing.get(change.id).kind !== change.kind) throw fail(400, 'A record cannot change its type.');
              revision++;
              const record = { id: change.id, kind: change.kind, data: preserveTopicImportance(change.kind, change.data, existing.get(change.id)?.data), deleted: !!change.deleted, version: revision, changedAt };
              existing.set(record.id, record);
              writes.push({ ...record, data: JSON.stringify(record.data), deleted: record.deleted ? 1 : 0, changed_at: changedAt });
              newReceipts.push({ mutation_id: change.mutationId, record_id: change.id, version: revision });
              accepted.push({ id: change.id, mutationId: change.mutationId, version: revision });
            }
            try { validateRelationships([...existing.values()]); } catch (error) { throw fail(400, error.message); }
            if ([...existing.values()].filter(record => !record.deleted).length > 20000) throw fail(400, 'A workspace supports up to 20,000 records.');
            if (writes.length) {
              await client.query(`INSERT INTO records SELECT $1,r.id,r.kind,r.data,r.deleted,r.version,r.changed_at FROM jsonb_to_recordset($2::jsonb) AS r(id text,kind text,data text,deleted integer,version integer,changed_at text) ON CONFLICT(user_id,id) DO UPDATE SET data=excluded.data,deleted=excluded.deleted,version=excluded.version,changed_at=excluded.changed_at`, [session.user_id, JSON.stringify(writes)]);
              await client.query(`INSERT INTO receipts SELECT $1,r.mutation_id,r.record_id,r.version FROM jsonb_to_recordset($2::jsonb) AS r(mutation_id text,record_id text,version integer)`, [session.user_id, JSON.stringify(newReceipts)]);
              await client.query('UPDATE users SET revision=$1 WHERE id=$2', [revision, session.user_id]);
            }
            result = { cursor: revision, records: [...existing.values()].filter(record => record.version > input.cursor).sort((a, b) => a.version - b.version), accepted, conflicts: [] };
          }
        }
        await client.query('COMMIT');
      } catch (error) { await client.query('ROLLBACK').catch(() => {}); throw error; }
      finally { client.release(); }
      return json(res, 200, result);
    } catch (error) {
      options.onError?.(error);
      if (!error.status) console.error('Cloud sync failed:', error.code || 'unexpected error');
      if (!res.headersSent) json(res, error.status || 500, { error: error.status ? error.message : 'The server could not complete this request.' });
    }
  };
}
