import { createServer } from 'node:http';
import { DatabaseSync } from 'node:sqlite';
import { randomBytes, scrypt as derive, timingSafeEqual, createHash } from 'node:crypto';
import { promisify } from 'node:util';
import { mkdirSync, existsSync, readFileSync, statSync } from 'node:fs';
import { resolve, extname, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import { validateEntity, validateRelationships } from '../shared/validation.mjs';
const scrypt = promisify(derive);
try { process.loadEnvFile(); } catch (e) { if (e.code !== 'ENOENT') throw e; }
const digest = token => createHash('sha256').update(token).digest('hex');
const httpError = (status, message) => Object.assign(new Error(message), { status });
export function createStudyServer(options = {}) {
  const dataDir = resolve(process.env.DATA_DIR || 'data');
  if (!options.database) mkdirSync(dataDir, { recursive: true });
  const db = new DatabaseSync(options.database || resolve(dataDir, 'studyspace.sqlite'));
  db.exec(`PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;
    CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, email TEXT UNIQUE NOT NULL, password TEXT NOT NULL, salt TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE IF NOT EXISTS sessions (token TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), expires INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS records (user_id TEXT NOT NULL, id TEXT NOT NULL, kind TEXT NOT NULL, data TEXT NOT NULL, deleted INTEGER NOT NULL, version INTEGER NOT NULL, changed_at TEXT NOT NULL, PRIMARY KEY(user_id,id));
    CREATE TABLE IF NOT EXISTS receipts (user_id TEXT NOT NULL, mutation_id TEXT NOT NULL, record_id TEXT NOT NULL, version INTEGER NOT NULL, PRIMARY KEY(user_id,mutation_id));
    CREATE INDEX IF NOT EXISTS records_cursor ON records(user_id,version);`);
  const rate = new Map(), staticRoot = resolve(options.staticRoot || 'dist');
  const allowed = new Set((process.env.ALLOWED_ORIGINS || 'http://localhost:8081,http://localhost:3001,http://127.0.0.1:8081,http://127.0.0.1:3001').split(',').map(s => s.trim()));
  const unpack = row => row && ({ id: row.id, kind: row.kind, data: JSON.parse(row.data), deleted: !!row.deleted, version: row.version, changedAt: row.changed_at });
  async function body(req) { let size = 0; const chunks = []; for await (const chunk of req) { size += chunk.length; if (size > 8e6) throw httpError(413, 'This request is too large. Import smaller batches.'); chunks.push(chunk); } try { return JSON.parse(Buffer.concat(chunks).toString()); } catch { throw httpError(400, 'Expected a JSON request.'); } }
  function json(res, status, value) { res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(value)); }
  const server = createServer(async (req, res) => {
    res.setHeader('X-Content-Type-Options', 'nosniff'); res.setHeader('Referrer-Policy', 'same-origin'); res.setHeader('X-Frame-Options', 'DENY');
    const origin = req.headers.origin;
    if (origin && allowed.has(origin)) { res.setHeader('Access-Control-Allow-Origin', origin); res.setHeader('Vary', 'Origin'); res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization'); res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS'); }
    if (req.method === 'OPTIONS') { res.writeHead(origin && !allowed.has(origin) ? 403 : 204); return res.end(); }
    try {
      const url = new URL(req.url, 'http://localhost'), path = url.pathname;
      if (path === '/api/health') return json(res, 200, { ok: true, service: 'studyspace', version: 1 });
      if (path.startsWith('/api/') && origin && !allowed.has(origin) && origin !== `http://${req.headers.host}` && origin !== `https://${req.headers.host}`) throw httpError(403, 'This origin is not allowed by the sync server.');
      if (['/api/auth/register', '/api/auth/login'].includes(path) && req.method === 'POST') {
        const ip = req.socket.remoteAddress || 'unknown', now = Date.now();
        const window = rate.get(ip); if (!window || window.until < now) rate.set(ip, { count: 1, until: now + 60000 }); else if (++window.count > 20) throw httpError(429, 'Too many attempts. Try again in one minute.');
        if (rate.size > 10000) for (const [key, item] of rate) if (item.until < now) rate.delete(key);
        const input = await body(req), email = typeof input.email === 'string' ? input.email.trim().toLowerCase() : '';
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 254 || typeof input.password !== 'string' || input.password.length < 10 || input.password.length > 256) throw httpError(400, 'Enter an email and a password of 10–256 characters.');
        let user = db.prepare('SELECT * FROM users WHERE email=?').get(email);
        if (path.endsWith('register')) {
          if (user) throw httpError(409, 'An account with this email already exists.');
          const salt = randomBytes(16).toString('hex'), password = (await scrypt(input.password, salt, 64)).toString('hex'), id = randomBytes(16).toString('hex');
          try { db.prepare('INSERT INTO users(id,email,password,salt) VALUES(?,?,?,?)').run(id, email, password, salt); } catch { throw httpError(409, 'An account with this email already exists.'); }
          user = { id, email };
        } else {
          const candidate = await scrypt(input.password, user?.salt || 'missing-user-salt', 64);
          if (!user || !timingSafeEqual(Buffer.from(user.password, 'hex'), candidate)) throw httpError(401, 'Email or password is incorrect.');
        }
        const token = randomBytes(32).toString('base64url');
        db.prepare('DELETE FROM sessions WHERE expires < ?').run(now);
        db.prepare('INSERT INTO sessions VALUES(?,?,?)').run(digest(token), user.id, now + 30 * 86400000);
        return json(res, 200, { token, user: { id: user.id, email: user.email } });
      }
      if (path.startsWith('/api/')) {
        const token = req.headers.authorization?.replace(/^Bearer /, '');
        const session = token && db.prepare('SELECT user_id FROM sessions WHERE token=? AND expires>?').get(digest(token), Date.now());
        if (!session) throw httpError(401, 'Sign in again to continue syncing. Your local data is safe.');
        if (path === '/api/auth/logout' && req.method === 'POST') { db.prepare('DELETE FROM sessions WHERE token=?').run(digest(token)); return json(res, 200, { ok: true }); }
        if (path === '/api/sync' && req.method === 'POST') {
          const input = await body(req), accepted = [], conflicts = [];
          if (!Array.isArray(input.changes) || input.changes.length > 20000 || !Number.isSafeInteger(input.cursor) || input.cursor < 0) throw httpError(400, 'Invalid sync request.');
          if (input.changes.some(c => !c) || new Set(input.changes.map(c => c.id)).size !== input.changes.length || new Set(input.changes.map(c => c.mutationId)).size !== input.changes.length) throw httpError(400, 'Sync changes must have unique record and mutation IDs.');
          for (const change of input.changes) if (!validateEntity(change.kind, change.data) || change.id !== change.data.id || typeof change.mutationId !== 'string' || change.mutationId.length > 180 || !Number.isSafeInteger(change.baseVersion) || change.baseVersion < 0 || (change.deleted != null && typeof change.deleted !== 'boolean')) throw httpError(400, 'A study record is invalid.');
          db.exec('BEGIN IMMEDIATE');
          try {
            let revision = db.prepare('SELECT revision FROM users WHERE id=?').get(session.user_id).revision;
            if (input.cursor > revision) throw httpError(409, 'The sync cursor is ahead of this server. Reconnect to the original server.');
            const fresh = input.changes.filter(change => !db.prepare('SELECT 1 FROM receipts WHERE user_id=? AND mutation_id=?').get(session.user_id, change.mutationId));
            const stale = fresh.some(change => (db.prepare('SELECT version FROM records WHERE user_id=? AND id=?').get(session.user_id, change.id)?.version || 0) !== change.baseVersion);
            if (stale) {
              // A branch edit is one unit: never delete a parent while rejecting a child.
              const conflicts = fresh.map(local => ({ local, remote: unpack(db.prepare('SELECT * FROM records WHERE user_id=? AND id=?').get(session.user_id, local.id)) || { id: local.id, kind: local.kind, data: local.data, deleted: true, version: 0 } }));
              const accepted = input.changes.filter(change => !fresh.includes(change)).map(change => { const receipt = db.prepare('SELECT version FROM receipts WHERE user_id=? AND mutation_id=?').get(session.user_id, change.mutationId); return { id: change.id, mutationId: change.mutationId, version: receipt.version }; });
              const records = db.prepare('SELECT * FROM records WHERE user_id=? AND version>? ORDER BY version').all(session.user_id, input.cursor).map(unpack);
              db.exec('COMMIT'); return json(res, 200, { cursor: revision, records, accepted, conflicts });
            }
            for (const change of input.changes) {
              const receipt = db.prepare('SELECT * FROM receipts WHERE user_id=? AND mutation_id=?').get(session.user_id, change.mutationId);
              if (receipt) { if (receipt.record_id !== change.id) throw httpError(400, 'Mutation ID was reused for a different record.'); accepted.push({ id: change.id, mutationId: change.mutationId, version: receipt.version }); continue; }
              const existing = unpack(db.prepare('SELECT * FROM records WHERE user_id=? AND id=?').get(session.user_id, change.id));
              if ((existing?.version || 0) !== change.baseVersion) { conflicts.push({ local: change, remote: existing }); continue; }
              if (existing && existing.kind !== change.kind) throw httpError(400, 'A record cannot change its type.');
              revision++;
              db.prepare('INSERT INTO records VALUES(?,?,?,?,?,?,?) ON CONFLICT(user_id,id) DO UPDATE SET data=excluded.data,deleted=excluded.deleted,version=excluded.version,changed_at=excluded.changed_at').run(session.user_id, change.id, change.kind, JSON.stringify(change.data), change.deleted ? 1 : 0, revision, new Date().toISOString());
              db.prepare('INSERT INTO receipts VALUES(?,?,?,?)').run(session.user_id, change.mutationId, change.id, revision);
              accepted.push({ id: change.id, mutationId: change.mutationId, version: revision });
            }
            validateRelationships(db.prepare('SELECT * FROM records WHERE user_id=?').all(session.user_id).map(unpack));
            db.prepare('UPDATE users SET revision=? WHERE id=?').run(revision, session.user_id);
            const records = db.prepare('SELECT * FROM records WHERE user_id=? AND version>? ORDER BY version').all(session.user_id, input.cursor).map(unpack);
            db.exec('COMMIT'); return json(res, 200, { cursor: revision, records, accepted, conflicts });
          } catch (error) { db.exec('ROLLBACK'); if (!error.status) error.status = 400; throw error; }
        }
        throw httpError(404, 'API route not found.');
      }
      if (req.method !== 'GET' && req.method !== 'HEAD') throw httpError(405, 'Method not allowed.');
      let file = resolve(staticRoot, '.' + decodeURIComponent(path));
      if (file !== staticRoot && !file.startsWith(staticRoot + sep)) throw httpError(403, 'Invalid path.');
      if (!existsSync(file) || !statSync(file).isFile()) { if (extname(path)) throw httpError(404, 'File not found.'); file = resolve(staticRoot, 'index.html'); }
      if (!existsSync(file)) return json(res, 200, { service: 'Studyspace sync is running', next: 'Run npm run build to serve the web app here, or npm run web for development.' });
      const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.ttf': 'font/ttf', '.woff2': 'font/woff2', '.ico': 'image/x-icon' };
      res.writeHead(200, { 'Content-Type': mime[extname(file)] || 'application/octet-stream', 'Cache-Control': extname(file) === '.html' ? 'no-store' : 'public, max-age=3600' }); res.end(req.method === 'HEAD' ? undefined : readFileSync(file));
    } catch (error) { if (!res.headersSent) json(res, error.status || 500, { error: error.status ? error.message : 'The server could not complete this request.' }); if (!error.status) console.error(error); }
  });
  server.on('close', () => db.close());
  return server;
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const server = createStudyServer();
  server.listen(Number(process.env.PORT || 3001), process.env.HOST || '0.0.0.0', () => console.log(`Studyspace · http://localhost:${process.env.PORT || 3001}`));
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => process.exit()));
}
