# Tenant isolation review

Reviewed on 25 October 2026. A tenant is one registered user account; sharing a sync-server address does not share a workspace.

## Enforcement

- The server resolves the account from a random bearer token stored as a SHA-256 digest in the sessions table. Client-supplied account identifiers do not select the account.
- Record reads, writes, tombstones, conflict lookups and mutation receipts include the authenticated `user_id`. Record and receipt primary keys include `user_id`, so accounts can safely use identical IDs, including the fixed `preferences` ID.
- Syllabus relationships are validated against the caller's records. Another account's exam or parent topic cannot satisfy a relationship.
- Each account has its own revision counter. PostgreSQL sync locks that user's row inside a transaction.
- Web cache keys include server and user ID, with an epoch check that discards sync responses after an account switch. Android Room records, outbox, conflicts and recovery points use a scope derived from server and user ID. Android applies an in-flight sync response to its original scope.
- API responses use `Cache-Control: no-store`. The web service worker excludes API responses.

## Executable checks

`npm run test:api` includes `tests/tenant-isolation.test.mjs`. It exercises the local SQLite handler; the same test can target `createCloudHandler` through the existing isolated PostgreSQL fixture.

Result on the review date: all sixteen API test cases passed against SQLite and the isolated PostgreSQL handler, including six tenant-specific subtests and six shared-timer subtests. TypeScript checking, all twenty-seven web domain/search/timer/planner tests, seventeen native unit tests and eleven Android instrumented tests also passed. No cross-account access was found in these tested paths. This is not a claim that every possible attack has been excluded.

The suite creates disposable accounts and checks:

1. Missing, modified and fabricated tokens cannot read or write.
2. User/tenant IDs supplied in headers, request bodies or query parameters cannot select another account.
3. Guessed record IDs and versions do not leak foreign records through conflict responses.
4. An exam present only in another account cannot authorize a child topic.
5. All seven entity types remain separate even with identical record and mutation IDs; retries and revision counters stay account-scoped.
6. Stale-write responses contain only the caller's data, and deleting all of one account's records leaves the other account unchanged.
7. Logout revokes the caller's token only; a body cannot select a different token to revoke. Other sessions remain independent.

Cloud tests use a randomly named `studyspace_test_*` schema with a search path that excludes production tables, then remove the schema. No real preparation data or production accounts are used.

## Boundaries

This is application-enforced isolation in shared tables, not database-enforced row-level security. Database administrators and holders of the server's database credentials can access all accounts. A future API query must retain the account filter; the regression suite helps catch omissions but is not a formal proof.

The bearer token grants access to its account. Browser storage on a shared OS/browser profile is not a security boundary against someone who can inspect that profile: account caches are retained after logout for offline continuity. Android account caches likewise remain on the device, while its login token is encrypted with Android Keystore. This review does not claim protection against compromised devices, stolen tokens, or database administrators.

Shared stopwatches use one `account_timers` row per authenticated user. The API ignores client-supplied identity fields, reads/writes only the session owner, serializes mutations with that user's existing write lock, and rejects stale timer versions. Logging a timer and clearing it occur in the same transaction; retries do not create another entry. Shared timer caches remain separated by server and user on both clients. `tests/shared-timer.test.mjs` covers cross-device controls, colliding actions, unauthorized access and account isolation on SQLite and the isolated PostgreSQL fixture.

Lecture entries use optional subject IDs and numbered lecture attribution. Validation requires a matching subject, lecture numbers within its total, and unique credit; daily targets must reference an owned subject and fit its total. These checks run in both sync handlers and native local storage. New lecture and planner fields do not require deleting or resetting existing records. Android notification intents carry a timer ID and account scope; actions verify both before controlling a timer.


Automatic lecture scheduling runs only on the records fetched under the authenticated user's lock. Generated IDs are deterministic across web, Android and server, but the database primary key remains `(user_id, id)`. Scheduling preferences, locked days, catch-up origins and question-practice counts stay inside each user's existing versioned records. Legacy edits preserve fields they do not send. Planning API tests cover two users with the same subject IDs, count edits, locked targets, timer logging with practice results, legacy updates, and atomic rejection of totals below recorded lecture numbers. No production user records are used in tests.
