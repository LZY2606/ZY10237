package local.telemetry.review.web;

import local.telemetry.review.analysis.AnalysisModel;
import local.telemetry.review.analysis.AnalysisService;
import local.telemetry.review.fixture.FixtureImportService;
import local.telemetry.review.review.ReviewService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ReviewController {
  private final AnalysisService analysisService;
  private final ReviewService reviewService;

  public ReviewController(AnalysisService analysisService, ReviewService reviewService) {
    this.analysisService = analysisService;
    this.reviewService = reviewService;
  }

  @GetMapping("/case")
  public AnalysisModel.CaseView caseView() {
    return analysisService.currentCase();
  }

  @PostMapping("/fixes/{fixId}/reject")
  public AnalysisModel.CaseView rejectFix(
      @PathVariable String fixId,
      @RequestBody(required = false) Map<String, String> body) {
    return reviewService.rejectFix(fixId, body == null ? null : body.get("reason"));
  }

  @PostMapping("/fixes/{fixId}/accept")
  public AnalysisModel.CaseView acceptFix(@PathVariable String fixId) {
    return reviewService.acceptFix(fixId);
  }

  @PostMapping("/generations/{generationId}/confirm")
  public AnalysisModel.CaseView confirmGeneration(
      @PathVariable String generationId,
      @RequestBody(required = false) Map<String, String> body) {
    return reviewService.confirmGeneration(generationId, body == null ? null : body.get("boundaryAt"));
  }

  @PostMapping("/segments/adjust")
  public AnalysisModel.CaseView adjustSegment(@RequestBody Map<String, String> body) {
    return reviewService.adjustSegment(
        body.get("segmentId"),
        body.get("candidate"),
        body.get("state"),
        body.get("note"));
  }

  @PostMapping("/segments/retain-both")
  public AnalysisModel.CaseView retainBoth() {
    return reviewService.retainBothCandidates();
  }

  @PostMapping("/import/reset")
  public FixtureImportService.ImportSummary resetImport() {
    return reviewService.resetAndReimport();
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("error", exception.getMessage()));
  }
}
