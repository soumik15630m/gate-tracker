import { createStudyServer } from '../server/index.mjs';

// Dedicated emulator fixture: all accounts and records disappear when it stops.
const server = createStudyServer({ database: ':memory:' });
server.listen(3137, '0.0.0.0', () => console.log('In-memory native test API listening on port 3137'));
