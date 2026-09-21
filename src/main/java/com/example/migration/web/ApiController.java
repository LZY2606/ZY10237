package com.example.migration.web;

import com.example.migration.model.Rows.AccelRow;
import com.example.migration.model.Rows.BatteryRow;
import com.example.migration.model.Rows.EventRow;
import com.example.migration.model.Rows.GenerationRow;
import com.example.migration.model.Rows.ParamVersionRow;
import com.example.migration.service.DistanceService;
import com.example.migration.service.ImportService;
import com.example.migration.service.ReviewService;
import com.example.migration.service.SegmentationService;
import com.example.migration.service.TrajectoryService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final JdbcTemplate jdbc;
    private final TrajectoryService trajectoryService;
    private final SegmentationService segmentationService;
    private final ReviewService reviewService;
    private final DistanceService distanceService;
    private final ImportService importService;

    public ApiController(JdbcTemplate jdbc, TrajectoryService trajectoryService,
                         SegmentationService segmentationService, ReviewService reviewService,
                         DistanceService distanceService, ImportService importService) {
        this.jdbc = jdbc;
        this.trajectoryService = trajectoryService;
        this.segmentationService = segmentationService;
        this.reviewService = reviewService;
        this.distanceService = distanceService;
        this.importService = importService;
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        ParamVersionRow param = trajectoryService.defaultParam();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trajectoryCrs", TrajectoryService.TRAJECTORY_CRS);
        out.put("param", param);
        out.put("params", jdbc.query("SELECT * FROM param_version ORDER BY id",
                (rs, i) -> new ParamVersionRow(rs.getLong("id"), rs.getString("name"),
                        rs.getDouble("speed_threshold"), rs.getLong("gap_threshold_sec"),
                        rs.getString("source"))));
        out.put("generations", jdbc.query("SELECT * FROM device_generation ORDER BY id",
                (rs, i) -> new GenerationRow(rs.getLong("id"), rs.getString("device_id"),
                        rs.getLong("started_ts"), rs.getLong("ended_ts"), rs.getInt("confirmed") != 0)));
        out.put("fixes", trajectoryService.fixes());
        out.put("speeds", trajectoryService.speeds(param));
        out.put("pathPieces", trajectoryService.pathPieces(param));
        out.put("clockRollbacks", trajectoryService.clockRollbacks());
        out.put("spatialReturns", trajectoryService.spatialReturns());
        out.put("candidates", reviewService.candidates());
        out.put("segments", segmentationService.segments());
        out.put("accel", jdbc.query("SELECT * FROM accel_summary ORDER BY id",
                (rs, i) -> new AccelRow(rs.getLong("id"), rs.getLong("generation_id"),
                        rs.getLong("window_start"), rs.getLong("window_end"),
                        rs.getDouble("mean_activity"))));
        out.put("events", jdbc.query("SELECT * FROM device_event ORDER BY id",
                (rs, i) -> new EventRow(rs.getLong("id"), rs.getLong("generation_id"),
                        rs.getLong("ts"), rs.getString("type"))));
        out.put("battery", jdbc.query("SELECT * FROM battery_status ORDER BY id",
                (rs, i) -> new BatteryRow(rs.getLong("id"), rs.getLong("generation_id"),
                        rs.getLong("ts"), rs.getDouble("voltage"), rs.getInt("percent"))));
        out.put("reviewActions", reviewService.actions());
        return out;
    }

    @PostMapping("/fixes/{id}/reject")
    public Map<String, Object> rejectFix(@PathVariable long id, @RequestBody(required = false) Map<String, String> body) {
        reviewService.rejectFix(id, body == null ? "低质量" : body.getOrDefault("reason", "低质量"));
        return Map.of("ok", true);
    }

    @PostMapping("/generations/{id}/confirm")
    public Map<String, Object> confirmGeneration(@PathVariable long id) {
        reviewService.confirmGeneration(id);
        return Map.of("ok", true);
    }

    @PostMapping("/segments/{id}/adjust")
    public Map<String, Object> adjustSegment(@PathVariable long id, @RequestBody Map<String, String> body) {
        reviewService.adjustSegment(id, body.get("state"));
        return Map.of("ok", true);
    }

    @PostMapping("/candidates/{id}/retain")
    public Map<String, Object> retainCandidate(@PathVariable long id) {
        reviewService.retainCandidate(id);
        return Map.of("ok", true);
    }

    @PostMapping("/candidates/{id}/activate")
    public Map<String, Object> activateCandidate(@PathVariable long id) {
        reviewService.activateCandidate(id);
        return Map.of("ok", true);
    }

    @GetMapping("/distance")
    public Map<String, Object> distance(@RequestParam long a, @RequestParam long b) {
        double meters = distanceService.judgeDistanceMeters(a, b);
        return Map.of("a", a, "b", b, "distanceMeters", meters);
    }

    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> export() {
        Map<String, Object> record = reviewService.exportRunRecord(segmentationService.segments());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=run-record.json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(record);
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        importService.reset();
        Integer fixes = jdbc.queryForObject("SELECT COUNT(*) FROM fix", Integer.class);
        return Map.of("ok", true, "fixes", fixes == null ? 0 : fixes);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "app", "迁徙轨迹审理台");
    }
}
