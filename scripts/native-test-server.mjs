import { createStudyServer } from '../server/index.mjs';

// Dedicated emulator fixture: all accounts and records disappear when it stops.
const server = createStudyServer({ database: ':memory:' });
const port = Number(process.env.TEST_API_PORT || 3137);
server.listen(port, '127.0.0.1', () => console.log(`In-memory native test API listening on port ${port}`));
