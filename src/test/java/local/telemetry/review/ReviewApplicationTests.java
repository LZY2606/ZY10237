package local.telemetry.review;

import local.telemetry.review.analysis.AnalysisModel;
import local.telemetry.review.fixture.FixtureImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReviewApplicationTests {
  @Autowired
  private TestRestTemplate restTemplate;

  @Autowired
  private FixtureImportService fixtureImportService;

  @BeforeEach
  void resetFixture() {
    fixtureImportService.resetAndImport();
  }

  @Test
  void pageShowsChineseWorkbenchTitle() {
    String page = restTemplate.getForObject("/", String.class);
    assertThat(page).contains("迁徙轨迹审理台");
  }

  @Test
  void blocksCrossCollarSpeedCalculation() {
    AnalysisModel.CaseView caseView = currentCase();
    AnalysisModel.EdgeView edge = edge(caseView, "F03", "F05");
    assertThat(edge.crossGeneration()).isTrue();
    assertThat(edge.speedMps()).isNull();
    assertThat(edge.distanceMeters()).isNull();
    assertThat(edge.drawPath()).isFalse();
    assertThat(edge.reasons()).anyMatch(reason -> reason.contains("跨设备代次"));
  }

  @Test
  void doesNotDrawStraightPathAcrossLongGapAndKeepsDurationBounds() {
    AnalysisModel.CaseView caseView = currentCase();
    AnalysisModel.EdgeView edge = edge(caseView, "F05", "F06");
    assertThat(edge.longGap()).isTrue();
    assertThat(edge.distanceJudgmentBlocked()).isTrue();
    assertThat(edge.speedMps()).isNull();
    assertThat(edge.drawPath()).isFalse();
    AnalysisModel.SegmentView gap = caseView.segments().stream()
        .filter(segment -> segment.type().equals("LONG_GAP")
            && segment.fixIds().equals(List.of("F05", "F06")))
        .findFirst().orElseThrow();
    assertThat(gap.lowerBoundSeconds()).isZero();
    assertThat(gap.upperBoundSeconds()).isEqualTo(75000L);
  }

  @Test
  void rejectsDistanceJudgementWhenErrorCoordinateSystemDiffers() {
    AnalysisModel.CaseView caseView = currentCase();
    AnalysisModel.EdgeView edge = edge(caseView, "F06", "F07");
    assertThat(edge.distanceJudgmentBlocked()).isTrue();
    assertThat(edge.distanceMeters()).isNull();
    assertThat(edge.speedMps()).isNull();
    assertThat(edge.drawPath()).isFalse();
    assertThat(edge.reasons()).anyMatch(reason -> reason.contains("坐标系不一致"));
  }

  @Test
  void separatesClockRollbackFromRealReturnToOldLocation() {
    AnalysisModel.CaseView caseView = currentCase();
    assertThat(fix(caseView, "F14").clockRollback()).isTrue();
    assertThat(fix(caseView, "F13").clockRollback()).isFalse();
    assertThat(fix(caseView, "F13").returnToPreviousLocation()).isTrue();
    AnalysisModel.EdgeView edge = edge(caseView, "F12", "F14");
    assertThat(edge.barrierType()).isEqualTo("CLOCK_ANOMALY");
    assertThat(edge.speedMps()).isNull();
    assertThat(edge(caseView, "F14", "F13").barrierType()).isEqualTo("CLOCK_ANOMALY");
    assertThat(edge(caseView, "F14", "F13").speedMps()).isNull();
  }

  @Test
  void rejectsLowQualityFixAndRegeneratesProtectedGap() {
    restTemplate.postForEntity("/api/fixes/F07/reject",
        new HttpEntity<>(Map.of("reason", "测试拒绝低质量点")), AnalysisModel.CaseView.class);
    AnalysisModel.CaseView caseView = currentCase();
    assertThat(fix(caseView, "F07").qualityStatus()).isEqualTo("REJECTED");
    assertThat(caseView.edges())
        .noneMatch(edge -> edge.fromFixId().equals("F07") || edge.toFixId().equals("F07"));
    AnalysisModel.SegmentView rejectedGap = caseView.segments().stream()
        .filter(segment -> segment.type().equals("REJECTED_GAP")
            && segment.fixIds().equals(List.of("F06", "F08")))
        .findFirst().orElseThrow();
    assertThat(rejectedGap.state()).isEqualTo("REJECTED_GAP");
    assertThat(caseView.actions())
        .anyMatch(action -> action.actionType().equals("REJECT_FIX") && action.targetId().equals("F07"));
  }

  @Test
  void confirmsBoundaryAdjustsSegmentAndRetainsBothCandidates() {
    restTemplate.postForEntity("/api/generations/G1/confirm",
        new HttpEntity<>(Map.of("boundaryAt", "2026-04-05T06:00:00Z")),
        AnalysisModel.CaseView.class);
    String segmentId = currentCase().segments().stream()
        .filter(segment -> segment.candidate().equals("CANDIDATE-A-ACCEL")
            && segment.fixIds().equals(List.of("F08", "F09")))
        .map(AnalysisModel.SegmentView::id)
        .findFirst().orElseThrow();
    restTemplate.postForEntity("/api/segments/adjust", new HttpEntity<>(Map.of(
        "segmentId", segmentId,
        "candidate", "CANDIDATE-A-ACCEL",
        "state", "UNCERTAIN",
        "note", "测试调整"
    )), AnalysisModel.CaseView.class);
    restTemplate.postForEntity("/api/segments/retain-both", null, AnalysisModel.CaseView.class);
    AnalysisModel.CaseView caseView = currentCase();
    assertThat(caseView.generations()).filteredOn(generation -> generation.id().equals("G1"))
        .singleElement()
        .extracting(AnalysisModel.GenerationView::confirmed)
        .isEqualTo(true);
    assertThat(caseView.retainedCandidates())
        .containsExactly("CANDIDATE-A-ACCEL", "CANDIDATE-B-SPATIAL");
    assertThat(caseView.segments())
        .filteredOn(segment -> segment.id().equals(segmentId)
            && segment.candidate().equals("CANDIDATE-A-ACCEL"))
        .singleElement()
        .extracting(AnalysisModel.SegmentView::state, AnalysisModel.SegmentView::manuallyAdjusted)
        .containsExactly("UNCERTAIN", true);
  }

  @Test
  void clearsDatabaseAndReimportsSameFixtureForReview() {
    restTemplate.postForEntity("/api/fixes/F07/reject",
        new HttpEntity<>(Map.of("reason", "拒绝后应被清空")), AnalysisModel.CaseView.class);
    ResponseEntity<FixtureImportService.ImportSummary> response = restTemplate.exchange(
        "/api/import/reset", HttpMethod.POST, null,
        new ParameterizedTypeReference<>() {
        });
    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    AnalysisModel.CaseView caseView = currentCase();
    assertThat(fix(caseView, "F07").qualityStatus()).isEqualTo("ACCEPTED");
    assertThat(caseView.fixes()).hasSize(14);
    assertThat(caseView.actions())
        .extracting(AnalysisModel.ActionView::actionType)
        .containsExactly("RESET_AND_REIMPORT");
  }

  @Test
  void everyBehaviorSegmentTracesFixesGenerationAndParameterVersion() {
    AnalysisModel.CaseView caseView = currentCase();
    assertThat(caseView.segments())
        .filteredOn(segment -> segment.type().equals("BEHAVIOR"))
        .isNotEmpty()
        .allSatisfy(segment -> {
          assertThat(segment.fixIds()).isNotEmpty();
          assertThat(segment.generationNos()).isNotEmpty();
          assertThat(segment.deviceIds()).isNotEmpty();
          assertThat(segment.parameterVersions()).isNotEmpty();
          assertThat(segment.importRunId()).startsWith("run-fixed-");
          assertThat(segment.lowerBoundSeconds()).isNotNull();
          assertThat(segment.upperBoundSeconds()).isGreaterThanOrEqualTo(segment.lowerBoundSeconds());
        });
  }

  private AnalysisModel.CaseView currentCase() {
    return restTemplate.getForObject("/api/case", AnalysisModel.CaseView.class);
  }

  private AnalysisModel.EdgeView edge(AnalysisModel.CaseView caseView, String from, String to) {
    return caseView.edges().stream()
        .filter(edge -> edge.fromFixId().equals(from) && edge.toFixId().equals(to))
        .findFirst().orElseThrow();
  }

  private AnalysisModel.FixView fix(AnalysisModel.CaseView caseView, String id) {
    return caseView.fixes().stream()
        .filter(fix -> fix.id().equals(id))
        .findFirst().orElseThrow();
  }
}
