CREATE TABLE IF NOT EXISTS import_runs (
  id TEXT PRIMARY KEY,
  source TEXT NOT NULL,
  started_at TEXT NOT NULL,
  finished_at TEXT NOT NULL,
  status TEXT NOT NULL,
  inserted_fixes INTEGER NOT NULL,
  inserted_events INTEGER NOT NULL,
  notes TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS parameter_versions (
  version TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  label TEXT NOT NULL,
  config_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS device_generations (
  id TEXT PRIMARY KEY,
  animal_id TEXT NOT NULL,
  generation_no INTEGER NOT NULL,
  device_id TEXT NOT NULL,
  boundary_at TEXT,
  confirmed INTEGER NOT NULL DEFAULT 0,
  note TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS device_events (
  id TEXT PRIMARY KEY,
  animal_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  event_at TEXT NOT NULL,
  received_at TEXT NOT NULL,
  type TEXT NOT NULL,
  battery_v REAL,
  payload TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS telemetry_fixes (
  id TEXT PRIMARY KEY,
  animal_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  generation_no INTEGER NOT NULL,
  recorded_at TEXT NOT NULL,
  received_at TEXT NOT NULL,
  x_m INTEGER NOT NULL,
  y_m INTEGER NOT NULL,
  coord_crs TEXT NOT NULL,
  error_major_m REAL NOT NULL,
  error_minor_m REAL NOT NULL,
  error_orientation_deg REAL NOT NULL,
  error_crs TEXT NOT NULL,
  satellites INTEGER NOT NULL,
  hdop REAL NOT NULL,
  activity_index INTEGER NOT NULL,
  battery_v REAL NOT NULL,
  parameter_version TEXT NOT NULL,
  quality_score INTEGER NOT NULL,
  quality_status TEXT NOT NULL,
  rejection_reason TEXT,
  clock_rollback INTEGER NOT NULL DEFAULT 0,
  late_after_replacement INTEGER NOT NULL DEFAULT 0,
  import_run_id TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_fixes_animal_time ON telemetry_fixes(animal_id, recorded_at);
CREATE INDEX IF NOT EXISTS idx_fixes_device_time ON telemetry_fixes(device_id, recorded_at);

CREATE TABLE IF NOT EXISTS review_actions (
  id TEXT PRIMARY KEY,
  action_at TEXT NOT NULL,
  action_type TEXT NOT NULL,
  target_type TEXT NOT NULL,
  target_id TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  operator TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS segment_overrides (
  segment_id TEXT NOT NULL,
  candidate TEXT NOT NULL,
  state TEXT NOT NULL,
  note TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY (segment_id, candidate)
);

CREATE TABLE IF NOT EXISTS case_decisions (
  decision_key TEXT PRIMARY KEY,
  decision_value TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
