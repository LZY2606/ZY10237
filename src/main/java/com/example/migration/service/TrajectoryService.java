package com.example.migration.service;

import com.example.migration.model.Rows.FixRow;
import com.example.migration.model.Rows.ParamVersionRow;
import com.example.migration.model.Rows.PathPiece;
import com.example.migration.model.Rows.SpatialReturn;
import com.example.migration.model.Rows.SpeedRow;
import com.example.migration.model.Rows.TimeAnomaly;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 轨迹层规则：
 * - 速度只在同一代次、未拒绝、时间正向且间隔不超过阈值的相邻定位之间计算；
 * - 长缺测空档不连线（不伪造直线路径）；
 * - 设备时钟回跳（时间倒序）与动物返回旧位置（空间回返）分开识别。
 */
@Service
public class TrajectoryService {

    public static final String TRAJECTORY_CRS = "LOCAL_GRID";
    public static final double RETURN_RADIUS_M = 150.0;

    private final JdbcTemplate jdbc;

    public TrajectoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<FixRow> fixes() {
        return jdbc.query("SELECT * FROM fix ORDER BY id", (rs, i) -> new FixRow(
                rs.getLong("id"), rs.getLong("generation_id"), rs.getLong("device_ts"),
                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("hdop"),
                rs.getString("ellipse_crs"),
                (Double) rs.getObject("ellipse_a"), (Double) rs.getObject("ellipse_b"),
                (Double) rs.getObject("ellipse_theta"),
                rs.getInt("rejected") != 0, rs.getString("reject_reason"), rs.getInt("late") != 0));
    }

    public ParamVersionRow defaultParam() {
        return jdbc.queryForObject(
                "SELECT * FROM param_version WHERE name='default' ORDER BY id LIMIT 1",
                (rs, i) -> mapParam(rs));
    }

    public ParamVersionRow paramById(long id) {
        return jdbc.queryForObject("SELECT * FROM param_version WHERE id=?",
                (rs, i) -> mapParam(rs), id);
    }

    private ParamVersionRow mapParam(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ParamVersionRow(rs.getLong("id"), rs.getString("name"),
                rs.getDouble("speed_threshold"), rs.getLong("gap_threshold_sec"), rs.getString("source"));
    }

    /** 相邻定位（按到达顺序）之间的速度；不允许计算时给出阻断原因。 */
    public List<SpeedRow> speeds(ParamVersionRow param) {
        List<FixRow> fixes = fixes();
        List<SpeedRow> out = new ArrayList<>();
        for (int i = 1; i < fixes.size(); i++) {
            FixRow a = fixes.get(i - 1);
            FixRow b = fixes.get(i);
            if (a.generationId() != b.generationId()) {
                out.add(blocked(a, b, "CROSS_GENERATION"));
                continue;
            }
            if (a.rejected() || b.rejected()) {
                out.add(blocked(a, b, "LOW_QUALITY"));
                continue;
            }
            long dt = b.deviceTs() - a.deviceTs();
            if (dt <= 0) {
                out.add(blocked(a, b, "CLOCK_ROLLBACK"));
                continue;
            }
            if (dt > param.gapThresholdSec()) {
                out.add(blocked(a, b, "LONG_GAP"));
                continue;
            }
            double dist = Math.hypot(b.x() - a.x(), b.y() - a.y());
            out.add(new SpeedRow(a.id(), b.id(), a.generationId(), dt, dist, dist / dt, false, null));
        }
        return out;
    }

    private SpeedRow blocked(FixRow a, FixRow b, String reason) {
        return new SpeedRow(a.id(), b.id(), null, null, null, null, true, reason);
    }

    /** 轨迹分段折线：被阻断的相邻对断开，长空档两端绝不连线。 */
    public List<PathPiece> pathPieces(ParamVersionRow param) {
        List<FixRow> fixes = fixes();
        List<SpeedRow> speeds = speeds(param);
        List<PathPiece> pieces = new ArrayList<>();
        List<Long> currentIds = new ArrayList<>();
        List<double[]> currentPoints = new ArrayList<>();
        long currentGeneration = -1;
        for (int i = 0; i < fixes.size(); i++) {
            FixRow f = fixes.get(i);
            boolean connectToPrevious = i > 0 && !speeds.get(i - 1).blocked();
            if (!connectToPrevious) {
                if (!currentIds.isEmpty()) {
                    pieces.add(new PathPiece(currentGeneration, currentIds, currentPoints));
                }
                currentIds = new ArrayList<>();
                currentPoints = new ArrayList<>();
                currentGeneration = f.generationId();
            }
            currentIds.add(f.id());
            currentPoints.add(new double[]{f.x(), f.y()});
        }
        if (!currentIds.isEmpty()) {
            pieces.add(new PathPiece(currentGeneration, currentIds, currentPoints));
        }
        return pieces;
    }

    /** 时钟回跳：同一代次内，到达顺序中设备时间戳出现倒退。 */
    public List<TimeAnomaly> clockRollbacks() {
        List<FixRow> fixes = fixes();
        List<TimeAnomaly> out = new ArrayList<>();
        for (int i = 1; i < fixes.size(); i++) {
            FixRow a = fixes.get(i - 1);
            FixRow b = fixes.get(i);
            if (a.generationId() == b.generationId() && b.deviceTs() < a.deviceTs()) {
                out.add(new TimeAnomaly(b.id(), b.generationId(), a.deviceTs(), b.deviceTs(), "CLOCK_ROLLBACK"));
            }
        }
        return out;
    }

    /** 空间回返：时间单调前进，但位置回到更早定位附近（与设备时钟回跳无关）。 */
    public List<SpatialReturn> spatialReturns() {
        List<FixRow> fixes = fixes();
        List<SpatialReturn> out = new ArrayList<>();
        for (int i = 0; i < fixes.size(); i++) {
            FixRow b = fixes.get(i);
            if (b.rejected()) {
                continue;
            }
            for (int j = 0; j < i - 1; j++) {
                FixRow a = fixes.get(j);
                if (a.rejected() || a.generationId() != b.generationId() || a.deviceTs() >= b.deviceTs()) {
                    continue;
                }
                double dist = Math.hypot(b.x() - a.x(), b.y() - a.y());
                if (dist <= RETURN_RADIUS_M) {
                    out.add(new SpatialReturn(b.id(), a.id(), dist));
                    break;
                }
            }
        }
        return out;
    }
}
