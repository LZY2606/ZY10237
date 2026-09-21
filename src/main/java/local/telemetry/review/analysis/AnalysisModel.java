package local.telemetry.review.analysis;

import java.util.List;

public final class AnalysisModel {
  private AnalysisModel() {
  }

  public record CaseView(
      String animalId,
      String source,
      String importedAt,
      List<GenerationView> generations,
      List<ParameterView> parameters,
      List<EventView> events,
      List<FixView> fixes,
      List<EdgeView> edges,
      List<SegmentView> segments,
      List<ActionView> actions,
      List<String> retainedCandidates,
      List<String> warnings) {
  }

  public record GenerationView(
      String id,
      int generationNo,
      String deviceId,
      String boundaryAt,
      boolean confirmed,
      String note) {
  }

  public record ParameterView(
      String version,
      String deviceId,
      String label,
      String configJson) {
  }

  public record EventView(
      String id,
      String deviceId,
      String eventAt,
      String receivedAt,
      String type,
      Double batteryV,
      String payloadJson) {
  }

  public record FixView(
      String id,
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
      boolean returnToPreviousLocation,
      List<String> warnings) {
  }

  public record EdgeView(
      String id,
      String fromFixId,
      String toFixId,
      int fromGenerationNo,
      int toGenerationNo,
      String startAt,
      String endAt,
      Long elapsedSeconds,
      Double distanceMeters,
      Double speedMps,
      boolean drawPath,
      boolean crossGeneration,
      boolean longGap,
      boolean clockRollback,
      boolean distanceJudgmentBlocked,
      String barrierType,
      List<String> reasons) {
  }

  public record SegmentView(
      String id,
      String candidate,
      String type,
      String state,
      String startAt,
      String endAt,
      Long lowerBoundSeconds,
      Long upperBoundSeconds,
      List<String> fixIds,
      List<Integer> generationNos,
      List<String> deviceIds,
      List<String> parameterVersions,
      String importRunId,
      boolean manuallyAdjusted,
      String note,
      List<String> warnings) {
  }

  public record ActionView(
      String id,
      String actionAt,
      String actionType,
      String targetType,
      String targetId,
      String payloadJson,
      String operator) {
  }
}
