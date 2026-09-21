package local.telemetry.review.review;

import local.telemetry.review.analysis.AnalysisModel;
import local.telemetry.review.analysis.AnalysisService;
import local.telemetry.review.fixture.FixtureImportService;
import local.telemetry.review.repository.ReviewRepository;
import local.telemetry.review.repository.ReviewRepository.ActionRow;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReviewService {
  private final ReviewRepository repository;
  private final AnalysisService analysisService;
  private final FixtureImportService fixtureImportService;

  public ReviewService(
      ReviewRepository repository,
      AnalysisService analysisService,
      FixtureImportService fixtureImportService) {
    this.repository = repository;
    this.analysisService = analysisService;
    this.fixtureImportService = fixtureImportService;
  }

  public AnalysisModel.CaseView rejectFix(String fixId, String reason) {
    String checkedReason = blank(reason) ? "人工拒绝低质量定位" : reason;
    repository.updateFixDecision(fixId, "REJECTED", checkedReason);
    action("REJECT_FIX", "FIX", fixId, Map.of("reason", checkedReason));
    return analysisService.currentCase();
  }

  public AnalysisModel.CaseView acceptFix(String fixId) {
    repository.updateFixDecision(fixId, "ACCEPTED", null);
    action("ACCEPT_FIX", "FIX", fixId, Map.of("quality", "人工恢复"));
    return analysisService.currentCase();
  }

  public AnalysisModel.CaseView confirmGeneration(String generationId, String boundaryAt) {
    String boundary = blank(boundaryAt) ? Instant.now().toString() : boundaryAt;
    repository.confirmGeneration(generationId, boundary);
    action("CONFIRM_GENERATION", "GENERATION", generationId, Map.of("boundaryAt", boundary));
    return analysisService.currentCase();
  }

  public AnalysisModel.CaseView adjustSegment(String segmentId, String candidate, String state, String note) {
    if (!List.of("MOVING", "STAY", "UNCERTAIN").contains(state)) {
      throw new IllegalArgumentException("状态只允许 MOVING、STAY 或 UNCERTAIN");
    }
    String finalCandidate = blank(candidate) ? "CANDIDATE-A-ACCEL" : candidate;
    String finalNote = blank(note) ? "人工调整行为状态" : note;
    String now = Instant.now().toString();
    repository.upsertOverride(segmentId, finalCandidate, state, finalNote, now);
    action("ADJUST_SEGMENT", "SEGMENT", segmentId,
        Map.of("candidate", finalCandidate, "state", state, "note", finalNote));
    return analysisService.currentCase();
  }

  public AnalysisModel.CaseView retainBothCandidates() {
    String value = "CANDIDATE-A-ACCEL|CANDIDATE-B-SPATIAL";
    repository.putDecision("retainedCandidates", value, Instant.now().toString());
    action("RETAIN_CANDIDATES", "CASE", "A01",
        Map.of("candidates", List.of("CANDIDATE-A-ACCEL", "CANDIDATE-B-SPATIAL")));
    return analysisService.currentCase();
  }

  public FixtureImportService.ImportSummary resetAndReimport() {
    FixtureImportService.ImportSummary summary = fixtureImportService.resetAndImport();
    action("RESET_AND_REIMPORT", "CASE", "A01", Map.of(
        "runId", summary.runId(),
        "fixes", summary.fixes(),
        "events", summary.events()));
    return summary;
  }

  private void action(String type, String targetType, String targetId, Map<String, Object> payload) {
    String now = Instant.now().toString();
    repository.addAction(new ActionRow(
        "act-" + UUID.randomUUID(), now, type, targetType, targetId,
        null, "reviewer"), payload);
  }

  private boolean blank(String value) {
    return value == null || value.trim().isBlank();
  }
}
