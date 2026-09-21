package com.example.migration.db;

import com.example.migration.service.ImportService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 建表；空库时导入固定 fixture。 */
@Component
public class SchemaInitializer implements ApplicationRunner {

    public static final String[] TABLES = {
            "review_action", "segment", "segment_candidate", "battery_status",
            "device_event", "accel_summary", "fix", "device_generation", "param_version"
    };

    private final JdbcTemplate jdbc;
    private final ImportService importService;

    public SchemaInitializer(JdbcTemplate jdbc, ImportService importService) {
        this.jdbc = jdbc;
        this.importService = importService;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS param_version("
                + "id INTEGER PRIMARY KEY, name TEXT NOT NULL, speed_threshold REAL NOT NULL,"
                + "gap_threshold_sec INTEGER NOT NULL, source TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS device_generation("
                + "id INTEGER PRIMARY KEY, device_id TEXT NOT NULL, started_ts INTEGER NOT NULL,"
                + "ended_ts INTEGER, confirmed INTEGER NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fix("
                + "id INTEGER PRIMARY KEY, generation_id INTEGER NOT NULL, device_ts INTEGER NOT NULL,"
                + "x REAL NOT NULL, y REAL NOT NULL, hdop REAL NOT NULL,"
                + "ellipse_crs TEXT NOT NULL, ellipse_a REAL, ellipse_b REAL, ellipse_theta REAL,"
                + "rejected INTEGER NOT NULL DEFAULT 0, reject_reason TEXT,"
                + "late INTEGER NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS accel_summary("
                + "id INTEGER PRIMARY KEY, generation_id INTEGER NOT NULL,"
                + "window_start INTEGER NOT NULL, window_end INTEGER NOT NULL, mean_activity REAL NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS device_event("
                + "id INTEGER PRIMARY KEY, generation_id INTEGER NOT NULL, ts INTEGER NOT NULL, type TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS battery_status("
                + "id INTEGER PRIMARY KEY, generation_id INTEGER NOT NULL, ts INTEGER NOT NULL,"
                + "voltage REAL NOT NULL, percent INTEGER NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS segment_candidate("
                + "id INTEGER PRIMARY KEY, name TEXT NOT NULL, param_version_id INTEGER NOT NULL,"
                + "retained INTEGER NOT NULL DEFAULT 0, active INTEGER NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS segment("
                + "id INTEGER PRIMARY KEY, candidate_id INTEGER NOT NULL, state TEXT NOT NULL,"
                + "generation_id INTEGER NOT NULL, start_fix_id INTEGER NOT NULL, end_fix_id INTEGER NOT NULL,"
                + "start_ts INTEGER NOT NULL, end_ts INTEGER NOT NULL,"
                + "duration_lower INTEGER NOT NULL, duration_upper INTEGER NOT NULL,"
                + "param_version_id INTEGER NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS review_action("
                + "id INTEGER PRIMARY KEY, ts INTEGER NOT NULL, type TEXT NOT NULL, payload TEXT NOT NULL)");

        Integer generations = jdbc.queryForObject("SELECT COUNT(*) FROM device_generation", Integer.class);
        if (generations != null && generations == 0) {
            importService.importFixture();
        }
    }
}
