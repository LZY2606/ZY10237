package com.example.migration.service;

import com.example.migration.model.Rows.CandidateRow;
import com.example.migration.model.Rows.ReviewActionRow;
import com.example.migration.model.Rows.SegmentRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 审理台人工操作：拒绝定位、确认代次边界、调整分段、保留候选；全部留痕可导出。 */
@Service
public class ReviewService {

    private final JdbcTemplate jdbc;

    public ReviewService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void rejectFix(long fixId, String reason) {
        jdbc.update("UPDATE fix SET rejected=1, reject_reason=? WHERE id=?", reason, fixId);
        log("REJECT_FIX", Map.of("fixId", fixId, "reason", reason == null ? "" : reason));
    }

    public void confirmGeneration(long generationId) {
        jdbc.update("UPDATE device_generation SET confirmed=1 WHERE id=?", generationId);
        log("CONFIRM_BOUNDARY", Map.of("generationId", generationId));
    }

    public void adjustSegment(long segmentId, String newState) {
        if (!newState.equals("MOVE") && !newState.equals("STAY")) {
            throw new IllegalArgumentException("state must be MOVE or STAY");
        }
        jdbc.update("UPDATE segment SET state=? WHERE id=?", newState, segmentId);
        log("ADJUST_SEGMENT", Map.of("segmentId", segmentId, "newState", newState));
    }

    public void retainCandidate(long candidateId) {
        jdbc.update("UPDATE segment_candidate SET retained=1 WHERE id=?", candidateId);
        log("RETAIN_CANDIDATE", Map.of("candidateId", candidateId));
    }

    public void activateCandidate(long candidateId) {
        jdbc.update("UPDATE segment_candidate SET active=0");
        jdbc.update("UPDATE segment_candidate SET active=1 WHERE id=?", candidateId);
        log("ACTIVATE_CANDIDATE", Map.of("candidateId", candidateId));
    }

    public void log(String type, Map<String, ?> payload) {
        jdbc.update("INSERT INTO review_action(ts, type, payload) VALUES(?,?,?)",
                System.currentTimeMillis() / 1000, type, payload.toString());
    }

    public List<ReviewActionRow> actions() {
        return jdbc.query("SELECT * FROM review_action ORDER BY id",
                (rs, i) -> new ReviewActionRow(rs.getLong("id"), rs.getLong("ts"),
                        rs.getString("type"), rs.getString("payload")));
    }

    public List<CandidateRow> candidates() {
        return jdbc.query("SELECT * FROM segment_candidate ORDER BY id",
                (rs, i) -> new CandidateRow(rs.getLong("id"), rs.getString("name"),
                        rs.getLong("param_version_id"), rs.getInt("retained") != 0,
                        rs.getInt("active") != 0));
    }

    /** 运行记录导出：审理动作 + 当前分段与候选快照。 */
    public Map<String, Object> exportRunRecord(List<SegmentRow> segments) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exportedAtEpochSec", System.currentTimeMillis() / 1000);
        out.put("reviewActions", actions());
        out.put("candidates", candidates());
        out.put("segments", segments);
        return out;
    }
}
