package local.telemetry.review.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public class ReviewRepository {
  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public ReviewRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  public record ImportRow(
      String id,
      String source,
      String startedAt,
      String finishedAt,
      String status,
      int insertedFixes,
      int insertedEvents,
      String notes) {
  }

  public record GenerationRow(
      String id,
      String animalId,
      int generationNo,
      String deviceId,
      String boundaryAt,
      boolean confirmed,
      String note) {
  }

  public record ParameterRow(
      String version,
      String deviceId,
      String label,
      String configJson) {
  }

  public record EventRow(
      String id,
      String animalId,
      String deviceId,
      String eventAt,
      String receivedAt,
      String type,
      Double batteryV,
      String payloadJson) {
  }

  public record FixRow(
      String id,
      String animalId,
      String deviceId,
      int generationNo,
      String recordedAt,
      String receivedAt,
      int xM,
      int yM,
      String coordCrs,
      double errorMajorM,
      double errorMinorM,
      double errorOrientationDeg,
      String errorCrs,
      int satellites,
      double hdop,
      int activityIndex,
      double batteryV,
      String parameterVersion,
      int qualityScore,
      String qualityStatus,
      String rejectionReason,
      boolean clockRollback,
      boolean lateAfterReplacement,
      String importRunId) {
  }

  public record ActionRow(
      String id,
      String actionAt,
      String actionType,
      String targetType,
      String targetId,
      String payloadJson,
      String operator) {
  }

  public record OverrideRow(
      String segmentId,
      String candidate,
      String state,
      String note,
      String updatedAt) {
  }

  public boolean hasFixes() {
    Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM telemetry_fixes", Integer.class);
    return count != null && count > 0;
  }

  public Optional<ImportRow> latestImport() {
    return jdbcTemplate.query("SELECT * FROM import_runs ORDER BY started_at DESC LIMIT 1", importMapper())
        .stream().findFirst();
  }

  public List<GenerationRow> generations() {
    return jdbcTemplate.query("SELECT * FROM device_generations ORDER BY generation_no", generationMapper());
  }

  public List<ParameterRow> parameters() {
    return jdbcTemplate.query("SELECT * FROM parameter_versions ORDER BY device_id, version", parameterMapper());
  }

  public List<EventRow> events() {
    return jdbcTemplate.query("SELECT * FROM device_events ORDER BY event_at, received_at", eventMapper());
  }

  public List<FixRow> fixes() {
    return jdbcTemplate.query("SELECT * FROM telemetry_fixes ORDER BY recorded_at, received_at, id", fixMapper());
  }

  public List<ActionRow> actions() {
    return jdbcTemplate.query("SELECT * FROM review_actions ORDER BY action_at, id", actionMapper());
  }

  public List<OverrideRow> overrides() {
    return jdbcTemplate.query("SELECT * FROM segment_overrides ORDER BY updated_at", overrideMapper());
  }

  public Optional<String> decision(String key) {
    return jdbcTemplate.queryForList("SELECT decision_value FROM case_decisions WHERE decision_key = ?",
        String.class, key).stream().findFirst();
  }

  @Transactional
  public void clearAll() {
    for (String table : List.of(
        "review_actions",
        "segment_overrides",
        "case_decisions",
        "telemetry_fixes",
        "device_events",
        "device_generations",
        "parameter_versions",
        "import_runs")) {
      jdbcTemplate.update("DELETE FROM " + table);
    }
  }

  @Transactional
  public void insertImport(ImportRow row) {
    jdbcTemplate.update("""
        INSERT INTO import_runs
        (id, source, started_at, finished_at, status, inserted_fixes, inserted_events, notes)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, row.id(), row.source(), row.startedAt(), row.finishedAt(), row.status(),
        row.insertedFixes(), row.insertedEvents(), row.notes());
  }

  @Transactional
  public void insertGeneration(GenerationRow row) {
    jdbcTemplate.update("""
        INSERT INTO device_generations
        (id, animal_id, generation_no, device_id, boundary_at, confirmed, note)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """, row.id(), row.animalId(), row.generationNo(), row.deviceId(),
        row.boundaryAt(), row.confirmed() ? 1 : 0, row.note());
  }

  @Transactional
  public void insertParameter(ParameterRow row, Object config) {
    jdbcTemplate.update("""
        INSERT INTO parameter_versions (version, device_id, label, config_json)
        VALUES (?, ?, ?, ?)
        """, row.version(), row.deviceId(), row.label(), writeJson(config));
  }

  @Transactional
  public void insertEvent(EventRow row, Object payload) {
    jdbcTemplate.update("""
        INSERT INTO device_events
        (id, animal_id, device_id, event_at, received_at, type, battery_v, payload)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, row.id(), row.animalId(), row.deviceId(), row.eventAt(), row.receivedAt(),
        row.type(), row.batteryV(), writeJson(payload));
  }

  @Transactional
  public void insertFix(FixRow row) {
    jdbcTemplate.update("""
        INSERT INTO telemetry_fixes
        (id, animal_id, device_id, generation_no, recorded_at, received_at, x_m, y_m,
         coord_crs, error_major_m, error_minor_m, error_orientation_deg, error_crs,
         satellites, hdop, activity_index, battery_v, parameter_version, quality_score,
         quality_status, rejection_reason, clock_rollback, late_after_replacement, import_run_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, row.id(), row.animalId(), row.deviceId(), row.generationNo(), row.recordedAt(),
        row.receivedAt(), row.xM(), row.yM(), row.coordCrs(), row.errorMajorM(), row.errorMinorM(),
        row.errorOrientationDeg(), row.errorCrs(), row.satellites(), row.hdop(),
        row.activityIndex(), row.batteryV(), row.parameterVersion(), row.qualityScore(),
        row.qualityStatus(), row.rejectionReason(), row.clockRollback() ? 1 : 0,
        row.lateAfterReplacement() ? 1 : 0, row.importRunId());
  }

  @Transactional
  public void updateFixDecision(String fixId, String status, String reason) {
    jdbcTemplate.update("UPDATE telemetry_fixes SET quality_status = ?, rejection_reason = ? WHERE id = ?",
        status, reason, fixId);
  }

  @Transactional
  public void confirmGeneration(String generationId, String boundaryAt) {
    jdbcTemplate.update(
        "UPDATE device_generations SET confirmed = 1, boundary_at = ? WHERE id = ?",
        boundaryAt, generationId);
  }

  @Transactional
  public void upsertOverride(String segmentId, String candidate, String state, String note, String updatedAt) {
    jdbcTemplate.update("""
        INSERT INTO segment_overrides (segment_id, candidate, state, note, updated_at)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(segment_id, candidate) DO UPDATE SET
          state = excluded.state,
          note = excluded.note,
          updated_at = excluded.updated_at
        """, segmentId, candidate, state, note, updatedAt);
  }

  @Transactional
  public void putDecision(String key, String value, String updatedAt) {
    jdbcTemplate.update("""
        INSERT INTO case_decisions (decision_key, decision_value, updated_at)
        VALUES (?, ?, ?)
        ON CONFLICT(decision_key) DO UPDATE SET
          decision_value = excluded.decision_value,
          updated_at = excluded.updated_at
        """, key, value, updatedAt);
  }

  @Transactional
  public void addAction(ActionRow row, Object payload) {
    jdbcTemplate.update("""
        INSERT INTO review_actions
        (id, action_at, action_type, target_type, target_id, payload_json, operator)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """, row.id(), row.actionAt(), row.actionType(), row.targetType(), row.targetId(),
        writeJson(payload), row.operator());
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("JSON serialization failed", e);
    }
  }

  private RowMapper<ImportRow> importMapper() {
    return (rs, rowNum) -> new ImportRow(
        rs.getString("id"), rs.getString("source"), rs.getString("started_at"),
        rs.getString("finished_at"), rs.getString("status"), rs.getInt("inserted_fixes"),
        rs.getInt("inserted_events"), rs.getString("notes"));
  }

  private RowMapper<GenerationRow> generationMapper() {
    return (rs, rowNum) -> new GenerationRow(
        rs.getString("id"), rs.getString("animal_id"), rs.getInt("generation_no"),
        rs.getString("device_id"), rs.getString("boundary_at"),
        rs.getInt("confirmed") == 1, rs.getString("note"));
  }

  private RowMapper<ParameterRow> parameterMapper() {
    return (rs, rowNum) -> new ParameterRow(
        rs.getString("version"), rs.getString("device_id"),
        rs.getString("label"), rs.getString("config_json"));
  }

  private RowMapper<EventRow> eventMapper() {
    return (rs, rowNum) -> new EventRow(
        rs.getString("id"), rs.getString("animal_id"), rs.getString("device_id"),
        rs.getString("event_at"), rs.getString("received_at"), rs.getString("type"),
        (Double) rs.getObject("battery_v"), rs.getString("payload"));
  }

  private RowMapper<FixRow> fixMapper() {
    return (rs, rowNum) -> new FixRow(
        rs.getString("id"), rs.getString("animal_id"), rs.getString("device_id"),
        rs.getInt("generation_no"), rs.getString("recorded_at"), rs.getString("received_at"),
        rs.getInt("x_m"), rs.getInt("y_m"), rs.getString("coord_crs"),
        rs.getDouble("error_major_m"), rs.getDouble("error_minor_m"),
        rs.getDouble("error_orientation_deg"), rs.getString("error_crs"),
        rs.getInt("satellites"), rs.getDouble("hdop"), rs.getInt("activity_index"),
        rs.getDouble("battery_v"), rs.getString("parameter_version"),
        rs.getInt("quality_score"), rs.getString("quality_status"),
        rs.getString("rejection_reason"), rs.getInt("clock_rollback") == 1,
        rs.getInt("late_after_replacement") == 1, rs.getString("import_run_id"));
  }

  private RowMapper<ActionRow> actionMapper() {
    return (rs, rowNum) -> new ActionRow(
        rs.getString("id"), rs.getString("action_at"), rs.getString("action_type"),
        rs.getString("target_type"), rs.getString("target_id"),
        rs.getString("payload_json"), rs.getString("operator"));
  }

  private RowMapper<OverrideRow> overrideMapper() {
    return (rs, rowNum) -> new OverrideRow(
        rs.getString("segment_id"), rs.getString("candidate"), rs.getString("state"),
        rs.getString("note"), rs.getString("updated_at"));
  }
}
