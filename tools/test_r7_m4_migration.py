import sqlite3

conn = sqlite3.connect(':memory:')
cur = conn.cursor()
# Minimal representative v3 state: existing production data must survive 3 -> 4.
cur.executescript('''
CREATE TABLE jobs (id TEXT PRIMARY KEY, mode TEXT NOT NULL, targetPackage TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, linksFound INTEGER NOT NULL, occurrences INTEGER NOT NULL, lastError TEXT, lastProgressAt INTEGER NOT NULL);
CREATE TABLE groups (id TEXT PRIMARY KEY, targetPackage TEXT NOT NULL, displayName TEXT NOT NULL, accessibilitySignature TEXT NOT NULL, lastSeenAt INTEGER NOT NULL, discoveryOrder INTEGER NOT NULL, classification TEXT NOT NULL, lastNewOnlyBoundary TEXT);
CREATE TABLE links (normalizedUrl TEXT PRIMARY KEY, rawRepresentative TEXT NOT NULL, domain TEXT NOT NULL, firstSeenAt INTEGER NOT NULL, lastSeenAt INTEGER NOT NULL, occurrenceCount INTEGER NOT NULL);
CREATE TABLE occurrences (id TEXT PRIMARY KEY, normalizedUrl TEXT NOT NULL, groupId TEXT NOT NULL, jobId TEXT NOT NULL, messageFingerprint TEXT NOT NULL, observedAt INTEGER NOT NULL);
CREATE TABLE checkpoints (jobId TEXT NOT NULL, groupId TEXT NOT NULL, phase TEXT NOT NULL, scrollCount INTEGER NOT NULL, lastWindowFingerprint TEXT, oldestEvidenceCount INTEGER NOT NULL, linksFound INTEGER NOT NULL, occurrences INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(jobId, groupId));
INSERT INTO jobs VALUES ('job-old','DEEP','com.whatsapp','PAUSED',1,2,7,9,NULL,2);
INSERT INTO groups VALUES ('g-old','com.whatsapp','Legacy Group','sig',2,0,'GROUPS_FILTER',NULL);
INSERT INTO links VALUES ('https://example.com','https://example.com','example.com',1,2,3);
INSERT INTO occurrences VALUES ('occ-old','https://example.com','g-old','job-old','fp',2);
INSERT INTO checkpoints VALUES ('job-old','g-old','PARTIAL',4,'vp',1,7,9,2);
''')

# R7-M4 MIGRATION_3_4 SQL.
cur.executescript('''
CREATE TABLE IF NOT EXISTS extraction_queue_items (jobId TEXT NOT NULL, groupId TEXT NOT NULL, ordinal INTEGER NOT NULL, targetPackage TEXT NOT NULL, expectedTitle TEXT NOT NULL, expectedIdentitySignature TEXT, discoveryProvenance TEXT NOT NULL, newOnlyBoundaryAtStart TEXT, mode TEXT NOT NULL, status TEXT NOT NULL, retryCount INTEGER NOT NULL, lastState TEXT NOT NULL, checkpointId TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(jobId, groupId));
CREATE INDEX IF NOT EXISTS index_extraction_queue_items_jobId_status ON extraction_queue_items (jobId, status);
CREATE UNIQUE INDEX IF NOT EXISTS index_extraction_queue_items_jobId_ordinal ON extraction_queue_items (jobId, ordinal);
''')

assert cur.execute('SELECT id,status,linksFound,occurrences FROM jobs').fetchone() == ('job-old','PAUSED',7,9)
assert cur.execute('SELECT id,displayName FROM groups').fetchone() == ('g-old','Legacy Group')
assert cur.execute('SELECT normalizedUrl FROM links').fetchone() == ('https://example.com',)
assert cur.execute('SELECT id FROM occurrences').fetchone() == ('occ-old',)
assert cur.execute('SELECT phase,scrollCount FROM checkpoints').fetchone() == ('PARTIAL',4)
cols = [r[1] for r in cur.execute('PRAGMA table_info(extraction_queue_items)')]
required = {'jobId','groupId','ordinal','targetPackage','expectedTitle','expectedIdentitySignature','discoveryProvenance','newOnlyBoundaryAtStart','mode','status','retryCount','lastState','checkpointId','createdAt','updatedAt'}
assert required.issubset(cols)
indexes = {r[1] for r in cur.execute("PRAGMA index_list('extraction_queue_items')")}
assert 'index_extraction_queue_items_jobId_status' in indexes
assert 'index_extraction_queue_items_jobId_ordinal' in indexes
print('R7 M4 MIGRATION TEST PASS')
