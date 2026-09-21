package com.example.migration.service;

import com.example.migration.model.Rows.FixRow;
import com.example.migration.model.Rows.ParamVersionRow;
import com.example.migration.model.Rows.SegmentRow;
import com.example.migration.model.Rows.SpeedRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 行为分段：按参数版本的速度阈值把定位分为 MOVE/STAY，
 * 同一代次内连续同状态且相邻可连的定位合并为片段。
 * 时长下界 = 片段内首末定位实测跨度；上界 = 扩展到相邻观测点的跨度（缺测不确定度）。
 */
@Service
public class SegmentationService {

    private final JdbcTemplate jdbc;
    private final TrajectoryService trajectoryService;

    public SegmentationService(JdbcTemplate jdbc, TrajectoryService trajectoryService) {
        this.jdbc = jdbc;
        this.trajectoryService = trajectoryService;
    }

    /** 用指定参数版本生成一个新的状态序列候选及其全部片段。 */
    public long runSegmentation(long paramVersionId, String candidateName) {
        ParamVersionRow param = trajectoryService.paramById(paramVersionId);
        jdbc.update("INSERT INTO segment_candidate(name,param_version_id,retained,active) VALUES(?,?,0,0)",
                candidateName, paramVersionId);
        Long candidateId = jdbc.queryForObject("SELECT MAX(id) FROM segment_candidate", Long.class);

        List<FixRow> fixes = trajectoryService.fixes();
        List<SpeedRow> speeds = trajectoryService.speeds(param);
        Map<Long, SpeedRow> intervalByFromId = new HashMap<>();
        for (SpeedRow s : speeds) {
            intervalByFromId.put(s.fromFixId(), s);
        }

        String[] states = new String[fixes.size()];
        for (int i = 0; i < fixes.size(); i++) {
            SpeedRow interval = intervalByFromId.get(fixes.get(i).id());
            if (interval != null && !interval.blocked()) {
                states[i] = interval.speedMps() >= param.speedThresholdMps() ? "MOVE" : "STAY";
            } else if (i > 0) {
                states[i] = states[i - 1];
            } else {
                states[i] = "STAY";
            }
        }

        List<long[]> spans = new ArrayList<>(); // [startIdx, endIdx]
        int start = 0;
        for (int i = 1; i <= fixes.size(); i++) {
            boolean brk = i == fixes.size()
                    || fixes.get(i).rejected() || fixes.get(i - 1).rejected()
                    || fixes.get(i).generationId() != fixes.get(i - 1).generationId()
                    || intervalByFromId.get(fixes.get(i - 1).id()).blocked()
                    || !states[i].equals(states[i - 1]);
            if (brk) {
                spans.add(new long[]{start, i - 1});
                start = i;
            }
        }

        for (long[] span : spans) {
            FixRow first = fixes.get((int) span[0]);
            FixRow last = fixes.get((int) span[1]);
            long lower = last.deviceTs() - first.deviceTs();
            long prevTs = span[0] > 0 ? fixes.get((int) span[0] - 1).deviceTs() : first.deviceTs();
            long nextTs = span[1] < fixes.size() - 1 ? fixes.get((int) span[1] + 1).deviceTs() : last.deviceTs();
            long upper = Math.max(lower, nextTs - prevTs);
            jdbc.update("INSERT INTO segment(candidate_id,state,generation_id,start_fix_id,end_fix_id,"
                            + "start_ts,end_ts,duration_lower,duration_upper,param_version_id)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                    candidateId, states[(int) span[0]], first.generationId(), first.id(), last.id(),
                    first.deviceTs(), last.deviceTs(), lower, upper, paramVersionId);
        }
        return candidateId;
    }

    public List<SegmentRow> segments() {
        return jdbc.query("SELECT * FROM segment ORDER BY candidate_id, id", (rs, i) -> new SegmentRow(
                rs.getLong("id"), rs.getLong("candidate_id"), rs.getString("state"),
                rs.getLong("generation_id"), rs.getLong("start_fix_id"), rs.getLong("end_fix_id"),
                rs.getLong("start_ts"), rs.getLong("end_ts"),
                rs.getLong("duration_lower"), rs.getLong("duration_upper"),
                rs.getLong("param_version_id")));
    }
}
