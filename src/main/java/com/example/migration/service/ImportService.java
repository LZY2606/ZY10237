package com.example.migration.service;

import com.example.migration.db.SchemaInitializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 固定 fixture：定位序列横跨项圈更换（COLLAR-A → COLLAR-B），
 * 旧设备在更换后补传一条迟到记录，并含一次长时段无定位。
 * 时间戳为 epoch 秒，坐标为 LOCAL_GRID 米。
 */
@Service
public class ImportService {

    public static final long T0 = 1714521600L; // 2024-05-01T00:00:00Z

    private final JdbcTemplate jdbc;
    private final SegmentationService segmentationService;

    public ImportService(JdbcTemplate jdbc, SegmentationService segmentationService) {
        this.jdbc = jdbc;
        this.segmentationService = segmentationService;
    }

    /** 清空数据库并重新导入固定 fixture（复核重放）。 */
    public void reset() {
        for (String table : SchemaInitializer.TABLES) {
            jdbc.update("DELETE FROM " + table);
        }
        importFixture();
    }

    public void importFixture() {
        jdbc.update("INSERT INTO param_version(id,name,speed_threshold,gap_threshold_sec,source)"
                + " VALUES(1,'default',0.5,1800,'fixture')");
        jdbc.update("INSERT INTO param_version(id,name,speed_threshold,gap_threshold_sec,source)"
                + " VALUES(2,'sensitive',0.2,1800,'fixture')");

        jdbc.update("INSERT INTO device_generation(id,device_id,started_ts,ended_ts,confirmed)"
                + " VALUES(1,'COLLAR-A',?, ?,0)", T0 - 60, T0 + 12600);
        jdbc.update("INSERT INTO device_generation(id,device_id,started_ts,ended_ts,confirmed)"
                + " VALUES(2,'COLLAR-B',?,NULL,0)", T0 + 13200);

        // 代次 1（COLLAR-A）：正常移动 → 停留 → 低质量点 → 空间回返 → 时钟回跳 → 长空档 → 继续移动
        insertFix(1, 1, T0 + 0, 0, 0, 1.2, "LOCAL_GRID");
        insertFix(2, 1, T0 + 600, 800, 100, 1.1, "LOCAL_GRID");
        insertFix(3, 1, T0 + 1200, 1600, 150, 1.3, "LOCAL_GRID");
        insertFix(4, 1, T0 + 1800, 1750, 250, 1.2, "LOCAL_GRID");
        insertFix(5, 1, T0 + 2400, 1770, 260, 1.4, "LOCAL_GRID");
        insertFix(6, 1, T0 + 3000, 1800, 240, 9.8, "LOCAL_GRID"); // 低质量候选
        insertFix(7, 1, T0 + 3600, 900, 120, 1.3, "LOCAL_GRID"); // 动物返回旧位置（非时钟回跳）
        insertFix(8, 1, T0 + 3300, 1000, 150, 1.2, "LOCAL_GRID"); // 设备时钟回跳
        insertFix(9, 1, T0 + 10800, 3000, 2000, 1.5, "LOCAL_GRID"); // 长时段无定位之后
        insertFix(10, 1, T0 + 11400, 3600, 2100, 1.2, "LOCAL_GRID");
        insertFix(11, 1, T0 + 12000, 4200, 2200, 1.1, "WGS84_GEO"); // 误差椭圆坐标系不符

        // 代次 2（COLLAR-B）：项圈更换之后
        insertFix(12, 2, T0 + 13200, 4300, 2250, 1.2, "LOCAL_GRID");
        insertFix(13, 2, T0 + 13800, 4900, 2300, 1.3, "LOCAL_GRID");
        insertFix(14, 2, T0 + 14400, 5500, 2350, 1.2, "LOCAL_GRID");

        // 旧设备在更换之后补传的迟到记录（仍属代次 1）
        jdbc.update("INSERT INTO fix(id,generation_id,device_ts,x,y,hdop,ellipse_crs,"
                        + "ellipse_a,ellipse_b,ellipse_theta,rejected,late)"
                        + " VALUES(15,1,?,?,?,1.4,'LOCAL_GRID',30,20,15,0,1)",
                T0 + 12900, 4250.0, 2240.0);

        jdbc.update("INSERT INTO device_event(id,generation_id,ts,type) VALUES(1,1,?,'POWER_ON')", T0 - 60);
        jdbc.update("INSERT INTO device_event(id,generation_id,ts,type) VALUES(2,1,?,'POWER_OFF')", T0 + 12600);
        jdbc.update("INSERT INTO device_event(id,generation_id,ts,type) VALUES(3,2,?,'POWER_ON')", T0 + 13200);

        insertBattery(1, 1, T0 + 0, 4.1, 98);
        insertBattery(2, 1, T0 + 3600, 3.9, 80);
        insertBattery(3, 1, T0 + 7200, 3.7, 60);
        insertBattery(4, 1, T0 + 12000, 3.5, 35);
        insertBattery(5, 2, T0 + 13200, 4.2, 100);
        insertBattery(6, 2, T0 + 14400, 4.1, 95);

        double[] activity = {0.9, 0.8, 0.2, 0.2, 0.2, 0.85, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.9, 0.9, 0.9};
        for (int i = 0; i < activity.length; i++) {
            jdbc.update("INSERT INTO accel_summary(id,generation_id,window_start,window_end,mean_activity)"
                            + " VALUES(?,?,?,?,?)",
                    i + 1, i < 12 ? 1 : 2, T0 + i * 3600L, T0 + (i + 1) * 3600L, activity[i]);
        }

        long defaultCandidate = segmentationService.runSegmentation(1, "cand-default");
        segmentationService.runSegmentation(2, "cand-sensitive");
        jdbc.update("UPDATE segment_candidate SET active=1 WHERE id=?", defaultCandidate);
    }

    private void insertFix(long id, long generationId, long ts, double x, double y,
                           double hdop, String ellipseCrs) {
        jdbc.update("INSERT INTO fix(id,generation_id,device_ts,x,y,hdop,ellipse_crs,"
                        + "ellipse_a,ellipse_b,ellipse_theta,rejected,late)"
                        + " VALUES(?,?,?,?,?,?,?,30,20,15,0,0)",
                id, generationId, ts, x, y, hdop, ellipseCrs);
    }

    private void insertBattery(long id, long generationId, long ts, double voltage, int percent) {
        jdbc.update("INSERT INTO battery_status(id,generation_id,ts,voltage,percent) VALUES(?,?,?,?,?)",
                id, generationId, ts, voltage, percent);
    }
}
