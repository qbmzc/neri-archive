PRAGMA journal_mode=WAL;
PRAGMA busy_timeout=10000;
CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS subscriptions (
 id INTEGER PRIMARY KEY, name TEXT NOT NULL DEFAULT '', interval_minutes INTEGER NOT NULL DEFAULT 15,
 enabled INTEGER NOT NULL DEFAULT 1, initialized INTEGER NOT NULL DEFAULT 0, initial_download INTEGER NOT NULL DEFAULT 1,
 policy TEXT NOT NULL DEFAULT 'FIDELITY', auto_upgrade INTEGER NOT NULL DEFAULT 0,
 next_scan INTEGER NOT NULL DEFAULT 0, last_scan INTEGER NOT NULL DEFAULT 0, last_upgrade INTEGER NOT NULL DEFAULT 0,
 error TEXT NOT NULL DEFAULT '', track_count INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS songs (
 id INTEGER PRIMARY KEY, name TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL,
 cover TEXT NOT NULL DEFAULT '', duration INTEGER NOT NULL, track_no INTEGER NOT NULL DEFAULT 0,
 path TEXT, level TEXT, bytes INTEGER, sha256 TEXT, sample_rate INTEGER, bits INTEGER, bitrate INTEGER,
 downloaded_at INTEGER, metadata_warning TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS members (
 playlist_id INTEGER NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
 song_id INTEGER NOT NULL REFERENCES songs(id), position INTEGER NOT NULL,
 PRIMARY KEY(playlist_id,song_id)
);
CREATE TABLE IF NOT EXISTS tasks (
 id INTEGER PRIMARY KEY AUTOINCREMENT, song_id INTEGER NOT NULL REFERENCES songs(id),
 policy TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'QUEUED', requested_level TEXT NOT NULL DEFAULT '',
 actual_level TEXT NOT NULL DEFAULT '', attempts INTEGER NOT NULL DEFAULT 0, next_attempt INTEGER NOT NULL DEFAULT 0,
 bytes_done INTEGER NOT NULL DEFAULT 0, total_bytes INTEGER NOT NULL DEFAULT 0,
 error TEXT NOT NULL DEFAULT '', forced INTEGER NOT NULL DEFAULT 0,
 created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS one_active_song ON tasks(song_id) WHERE status IN ('QUEUED','RUNNING','PAUSED','AUTH_REQUIRED');
CREATE INDEX IF NOT EXISTS task_queue ON tasks(status,next_attempt);
CREATE TABLE IF NOT EXISTS file_inventory (
 path TEXT PRIMARY KEY, bytes INTEGER NOT NULL, modified_at INTEGER NOT NULL,
 sha256 TEXT NOT NULL, scanned_at INTEGER NOT NULL, song_id INTEGER
);
CREATE INDEX IF NOT EXISTS file_inventory_hash ON file_inventory(sha256);
CREATE INDEX IF NOT EXISTS idx_inventory_song ON file_inventory(song_id);
CREATE TABLE IF NOT EXISTS audio_fingerprints (
 path TEXT PRIMARY KEY, duration_seconds REAL NOT NULL, fingerprint_hash INTEGER NOT NULL,
 group_id TEXT NOT NULL DEFAULT '', match_type TEXT NOT NULL DEFAULT '', scanned_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS audio_fingerprint_group ON audio_fingerprints(group_id);
CREATE TABLE IF NOT EXISTS audio_fingerprint_data (
 path TEXT PRIMARY KEY, raw_fingerprint TEXT NOT NULL
);
