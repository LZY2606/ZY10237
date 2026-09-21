package local.telemetry.review.analysis;

import local.telemetry.review.repository.ReviewRepository.ActionRow;
import local.telemetry.review.repository.ReviewRepository.EventRow;
import local.telemetry.review.repository.ReviewRepository.FixRow;
import local.telemetry.review.repository.ReviewRepository.GenerationRow;
import local.telemetry.review.repository.ReviewRepository.ImportRow;
import local.telemetry.review.repository.ReviewRepository.OverrideRow;
import local.telemetry.review.repository.ReviewRepository.ParameterRow;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AnalysisService {
  private static final long GAP_SECONDS = 6 * 60 * 60L;
  private static final double STAY_SPEED_MPS = 0.03;
  private static final int ACTIVITY_MOVING = 40;
  private static final int ACTIVITY_STAY = 30;
  private static final int RETURN_RADIUS_M = 80;

  private final local.telemetry.review.repository.ReviewRepository repository;

  public AnalysisService(local.telemetry.review.repository.ReviewRepository repository) {
    this.repository = repository;
  }

  public AnalysisModel.CaseView currentCase() {
    List<FixRow> allFixes = repository.fixes();
    List<FixRow> accepted = allFixes.stream()
        .filter(fix -> "ACCEPTED".equals(fix.qualityStatus()))
        .toList();
    Map<String, FixRow> fixById = new LinkedHashMap<>();
    allFixes.forEach(fix -> fixById.put(fix.id(), fix));
    Map<String, Boolean> returnFlags = detectReturnLocations(accepted);
    List<AnalysisModel.FixView> fixViews = allFixes.stream()
        .map(fix -> toFixView(fix, returnFlags))
        .toList();
    List<Edge> edges = buildEdges(accepted, allFixes);
    List<AnalysisModel.EdgeView> edgeViews = edges.stream().map(this::toEdgeView).toList();

    Map<String, OverrideRow> overrides = new LinkedHashMap<>();
    for (OverrideRow override : repository.overrides()) {
      overrides.put(override.segmentId() + "|" + override.candidate(), override);
    }
    List<AnalysisModel.SegmentView> segments = buildSegments(edges, accepted, overrides);
    List<String> retained = parseRetainedCandidates();

    String animalId = allFixes.stream().map(FixRow::animalId).findFirst().orElse("A01");
    ImportRow importRow = repository.latestImport().orElse(null);
    List<String> warnings = buildWarnings(edges, fixViews);
    return new AnalysisModel.CaseView(
        animalId,
        importRow == null ? "未导入" : importRow.source(),
        importRow == null ? null : importRow.startedAt(),
        repository.generations().stream().map(this::toGenerationView).toList(),
        repository.parameters().stream().map(this::toParameterView).toList(),
        repository.events().stream().map(this::toEventView).toList(),
        fixViews,
        edgeViews,
        segments,
        repository.actions().stream().map(this::toActionView).toList(),
        retained,
        warnings);
  }

  private List<Edge> buildEdges(List<FixRow> fixes, List<FixRow> allFixes) {
    List<Edge> edges = new ArrayList<>();
    Map<Integer, List<FixRow>> byGeneration = new LinkedHashMap<>();
    fixes.stream()
        .sorted(Comparator.comparing(FixRow::recordedAt).thenComparing(FixRow::receivedAt))
        .forEach(fix -> byGeneration
            .computeIfAbsent(fix.generationNo(), key -> new ArrayList<>())
            .add(fix));

    List<List<FixRow>> generationGroups = byGeneration.values().stream()
        .sorted(Comparator.comparing(group -> group.get(0).generationNo()))
        .toList();
    Map<Integer, Instant> boundaries = new LinkedHashMap<>();
    for (var generation : repository.generations()) {
      if (generation.boundaryAt() != null) {
        boundaries.put(generation.generationNo(), Instant.parse(generation.boundaryAt()));
      }
    }

    int edgeNumber = 1;
    for (List<FixRow> group : generationGroups) {
      for (int index = 0; index + 1 < group.size(); index++) {
        FixRow from = group.get(index);
        FixRow to = group.get(index + 1);
        Instant generationBoundary = boundaries.get(from.generationNo());
        boolean crossesConfirmedBoundary = generationBoundary != null
            && Instant.parse(from.recordedAt()).isBefore(generationBoundary)
            && !Instant.parse(to.recordedAt()).isBefore(generationBoundary);
        if (!crossesConfirmedBoundary) {
          edges.add(createEdge("E" + edgeNumber++, from, to, allFixes));
        }
      }
    }

    for (int groupIndex = 0; groupIndex + 1 < generationGroups.size(); groupIndex++) {
      List<FixRow> previousGroup = generationGroups.get(groupIndex);
      List<FixRow> nextGroup = generationGroups.get(groupIndex + 1);
      FixRow from = previousGroup.stream()
          .filter(fix -> Instant.parse(fix.recordedAt())
              .isBefore(Instant.parse(nextGroup.get(0).recordedAt())))
          .max(Comparator.comparing(FixRow::recordedAt))
          .orElse(previousGroup.get(previousGroup.size() - 1));
      FixRow to = nextGroup.stream()
          .min(Comparator.comparing(FixRow::recordedAt))
          .orElseThrow();
      String boundaryAt = repository.generations().stream()
          .filter(generation -> generation.generationNo() == from.generationNo())
          .map(generation -> generation.boundaryAt())
          .filter(value -> value != null)
          .findFirst()
          .orElse(from.recordedAt());
      edges.add(crossGenerationEdge("E" + edgeNumber++, from, to, Instant.parse(boundaryAt)));
    }

    edges.sort(Comparator.comparing((Edge edge) -> edge.startAt())
        .thenComparing(edge -> edge.endAt())
        .thenComparing(Edge::id));
    return edges;
  }

  private Edge createEdge(String id, FixRow from, FixRow to, List<FixRow> allFixes) {
    Instant start = Instant.parse(from.recordedAt());
    Instant end = Instant.parse(to.recordedAt());
    long elapsed = Duration.between(start, end).getSeconds();
    boolean rejectedGap = hasRejectedFixBetween(from, to, allFixes);
    boolean clockRollback = from.clockRollback() || to.clockRollback() || elapsed <= 0;
    boolean longGap = elapsed >= GAP_SECONDS;
    boolean errorCrsMismatch = !from.errorCrs().equals(to.errorCrs())
        || !from.errorCrs().equals(from.coordCrs())
        || !to.errorCrs().equals(to.coordCrs());
    boolean drawPath = !rejectedGap && !clockRollback && !longGap && !errorCrsMismatch;
    boolean distanceBlocked = rejectedGap || clockRollback || longGap || errorCrsMismatch;
    Double distance = null;
    Double speed = null;
    if (!distanceBlocked && elapsed > 0) {
      distance = distance(from, to);
      speed = distance / elapsed;
    }
    return new Edge(id, from, to, start, end, elapsed, distance, speed, drawPath,
        false, longGap, clockRollback, distanceBlocked,
        rejectedGap ? "REJECTED_GAP" : barrierType(false, clockRollback, longGap, errorCrsMismatch),
        rejectedGap
            ? List.of("中间定位已拒绝：不用剩余点伪造距离或轨迹")
            : edgeReasons(false, clockRollback, longGap, errorCrsMismatch));
  }

  private boolean hasRejectedFixBetween(FixRow from, FixRow to, List<FixRow> allFixes) {
    if (from.generationNo() != to.generationNo() || !from.deviceId().equals(to.deviceId())) {
      return false;
    }
    Instant start = Instant.parse(from.recordedAt());
    Instant end = Instant.parse(to.recordedAt());
    return allFixes.stream()
        .filter(fix -> "REJECTED".equals(fix.qualityStatus()))
        .filter(fix -> fix.generationNo() == from.generationNo())
        .filter(fix -> fix.deviceId().equals(from.deviceId()))
        .map(fix -> Instant.parse(fix.recordedAt()))
        .anyMatch(time -> time.isAfter(start) && time.isBefore(end));
  }

  private Edge crossGenerationEdge(String id, FixRow from, FixRow to, Instant boundaryAt) {
    long elapsed = Duration.between(boundaryAt, Instant.parse(to.recordedAt())).getSeconds();
    return new Edge(id, from, to, boundaryAt, Instant.parse(to.recordedAt()),
        Math.max(0, elapsed), null, null, false, true, false, false, true,
        "CROSS_GENERATION",
        List.of("跨设备代次：禁止速度计算，不连接轨迹"));
  }

  private Map<String, Boolean> detectReturnLocations(List<FixRow> fixes) {
    Map<String, Boolean> result = new LinkedHashMap<>();
    for (int index = 0; index < fixes.size(); index++) {
      FixRow current = fixes.get(index);
      boolean returned = false;
      if (current.errorCrs().equals(current.coordCrs())) {
        for (int previous = 0; previous <= index - 3; previous++) {
          FixRow old = fixes.get(previous);
          if (old.errorCrs().equals(old.coordCrs())
              && old.generationNo() == current.generationNo()
              && distance(old, current) <= RETURN_RADIUS_M) {
            returned = true;
            break;
          }
        }
      }
      result.put(current.id(), returned);
    }
    return result;
  }

  private List<AnalysisModel.SegmentView> buildSegments(
      List<Edge> edges,
      List<FixRow> fixes,
      Map<String, OverrideRow> overrides) {
    List<AnalysisModel.SegmentView> segments = new ArrayList<>();
    for (Edge edge : edges) {
      if (edge.barrierType() != null) {
        long windowDuration = Math.max(0, edge.elapsedSeconds());
        long upperBound = edge.barrierType().equals("CLOCK_ANOMALY") ? 0 : windowDuration;
        segments.add(new AnalysisModel.SegmentView(
            edge.fromFixId() + "->" + edge.toFixId(),
            "STRUCTURAL",
            edge.barrierType(),
            edge.barrierType(),
            edge.startAt().toString(),
            edge.endAt().toString(),
            0L,
            upperBound,
            List.of(edge.fromFixId(), edge.toFixId()),
            distinct(List.of(edge.from().generationNo(), edge.to().generationNo())),
            List.of(edge.from().deviceId(), edge.to().deviceId()).stream().distinct().toList(),
            List.of(edge.from().parameterVersion(), edge.to().parameterVersion()).stream().distinct().toList(),
            edge.from().importRunId(),
            false,
            String.join("；", edge.reasons()),
            edge.reasons()));
      }
    }

    List<List<FixRow>> runs = splitRuns(edges, fixes);
    for (String candidate : List.of("CANDIDATE-A-ACCEL", "CANDIDATE-B-SPATIAL")) {
      for (List<FixRow> run : runs) {
        segments.addAll(buildCandidateRunSegments(candidate, run, edges, overrides));
      }
    }
    segments.sort(Comparator.comparing(AnalysisModel.SegmentView::startAt)
        .thenComparing(segment -> "STRUCTURAL".equals(segment.candidate()) ? "0" : segment.candidate()));
    return segments;
  }

  private List<List<FixRow>> splitRuns(List<Edge> edges, List<FixRow> fixes) {
    List<List<FixRow>> runs = new ArrayList<>();
    Map<String, List<FixRow>> groups = new LinkedHashMap<>();
    for (FixRow fix : fixes) {
      String key = fix.generationNo() + "|" + fix.deviceId();
      groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(fix);
    }
    for (List<FixRow> group : groups.values()) {
      List<FixRow> current = new ArrayList<>();
      current.add(group.get(0));
      for (int index = 0; index + 1 < group.size(); index++) {
        FixRow from = group.get(index);
        FixRow to = group.get(index + 1);
        Edge edge = findEdge(edges, from.id(), to.id());
        if (edge == null || edge.barrierType() != null) {
          runs.add(current);
          current = new ArrayList<>();
          current.add(to);
        } else {
          current.add(to);
        }
      }
      runs.add(current);
    }
    return runs;
  }

  private Edge findEdge(List<Edge> edges, String fromFixId, String toFixId) {
    return edges.stream()
        .filter(edge -> edge.fromFixId().equals(fromFixId) && edge.toFixId().equals(toFixId))
        .findFirst()
        .orElse(null);
  }

  private List<AnalysisModel.SegmentView> buildCandidateRunSegments(
      String candidate,
      List<FixRow> run,
      List<Edge> edges,
      Map<String, OverrideRow> overrides) {
    List<Edge> runEdges = new ArrayList<>();
    for (int index = 0; index + 1 < run.size(); index++) {
      String fromId = run.get(index).id();
      String toId = run.get(index + 1).id();
      edges.stream()
          .filter(edge -> edge.fromFixId().equals(fromId) && edge.toFixId().equals(toId))
          .findFirst()
          .ifPresent(runEdges::add);
    }

    if (runEdges.isEmpty()) {
      return run.stream()
          .map(fix -> singletonSegment(candidate, fix, edges, overrides))
          .toList();
    }

    List<AnalysisModel.SegmentView> result = new ArrayList<>();
    int startEdge = 0;
    while (startEdge < runEdges.size()) {
      String state = candidateState(candidate, runEdges.get(startEdge));
      int endEdge = startEdge;
      while (endEdge + 1 < runEdges.size()
          && candidateState(candidate, runEdges.get(endEdge + 1)).equals(state)) {
        endEdge++;
      }
      List<Edge> groupEdges = runEdges.subList(startEdge, endEdge + 1);
      result.add(groupSegment(candidate, run, groupEdges, state, edges, overrides));
      startEdge = endEdge + 1;
    }
    return result;
  }

  private AnalysisModel.SegmentView groupSegment(
      String candidate,
      List<FixRow> run,
      List<Edge> groupEdges,
      String state,
      List<Edge> allEdges,
      Map<String, OverrideRow> overrides) {
    FixRow first = groupEdges.get(0).from();
    FixRow last = groupEdges.get(groupEdges.size() - 1).to();
    String segmentId = first.id() + "-" + last.id();
    long lower = Duration.between(Instant.parse(first.recordedAt()), Instant.parse(last.recordedAt())).getSeconds();
    long upper = lower;
    Edge before = findEdgeEndingAt(allEdges, first.id());
    Edge after = findEdgeStartingAt(allEdges, last.id());
    if (before != null && before.barrierType() == null) {
      upper += Math.max(0, before.elapsedSeconds()) / 2;
    }
    if (after != null && after.barrierType() == null) {
      upper += Math.max(0, after.elapsedSeconds()) / 2;
    }
    List<String> fixIds = new ArrayList<>();
    fixIds.add(first.id());
    groupEdges.forEach(edge -> fixIds.add(edge.toFixId()));
    List<FixRow> sourceFixes = run.stream().filter(fix -> fixIds.contains(fix.id())).toList();
    List<String> warnings = segmentWarnings(groupEdges, candidate);
    OverrideRow override = overrides.get(segmentId + "|" + candidate);
    String finalState = override == null ? state : override.state();
    return new AnalysisModel.SegmentView(
        segmentId,
        candidate,
        "BEHAVIOR",
        finalState,
        first.recordedAt(),
        last.recordedAt(),
        lower,
        upper,
        fixIds,
        sourceFixes.stream().map(FixRow::generationNo).distinct().toList(),
        sourceFixes.stream().map(FixRow::deviceId).distinct().toList(),
        sourceFixes.stream().map(FixRow::parameterVersion).distinct().toList(),
        first.importRunId(),
        override != null,
        override == null ? String.join("；", warnings) : override.note(),
        warnings);
  }

  private AnalysisModel.SegmentView singletonSegment(
      String candidate,
      FixRow fix,
      List<Edge> edges,
      Map<String, OverrideRow> overrides) {
    String state = candidate.equals("CANDIDATE-A-ACCEL")
        ? activityState(fix.activityIndex())
        : "UNCERTAIN";
    String segmentId = fix.id() + "-" + fix.id();
    long lower = 0;
    long upper = 0;
    Edge before = findEdgeEndingAt(edges, fix.id());
    Edge after = findEdgeStartingAt(edges, fix.id());
    if (before != null && before.barrierType() == null) {
      upper += Math.max(0, before.elapsedSeconds()) / 2;
    }
    if (after != null && after.barrierType() == null) {
      upper += Math.max(0, after.elapsedSeconds()) / 2;
    }
    OverrideRow override = overrides.get(segmentId + "|" + candidate);
    List<String> warnings = List.of("单点片段：仅能给出时长上界");
    return new AnalysisModel.SegmentView(
        segmentId,
        candidate,
        "BEHAVIOR",
        override == null ? state : override.state(),
        fix.recordedAt(),
        fix.recordedAt(),
        lower,
        upper,
        List.of(fix.id()),
        List.of(fix.generationNo()),
        List.of(fix.deviceId()),
        List.of(fix.parameterVersion()),
        fix.importRunId(),
        override != null,
        override == null ? warnings.get(0) : override.note(),
        warnings);
  }

  private String candidateState(String candidate, Edge edge) {
    if (candidate.equals("CANDIDATE-B-SPATIAL")
        && edge.distanceJudgmentBlocked()
        && !edge.crossGeneration()
        && !edge.longGap()
        && !edge.clockRollback()) {
      return "UNCERTAIN";
    }
    if (candidate.equals("CANDIDATE-A-ACCEL")) {
      int meanActivity = (edge.from().activityIndex() + edge.to().activityIndex()) / 2;
      return activityState(meanActivity);
    }
    if (edge.speedMps() == null) {
      return "UNCERTAIN";
    }
    return edge.speedMps() <= STAY_SPEED_MPS ? "STAY" : "MOVING";
  }

  private String activityState(int activity) {
    if (activity < ACTIVITY_STAY) {
      return "STAY";
    }
    if (activity >= ACTIVITY_MOVING) {
      return "MOVING";
    }
    return "UNCERTAIN";
  }

  private Edge findEdgeStartingAt(List<Edge> edges, String fixId) {
    return edges.stream().filter(edge -> edge.fromFixId().equals(fixId)).findFirst().orElse(null);
  }

  private Edge findEdgeEndingAt(List<Edge> edges, String fixId) {
    return edges.stream().filter(edge -> edge.toFixId().equals(fixId)).findFirst().orElse(null);
  }

  private List<String> segmentWarnings(List<Edge> edges, String candidate) {
    List<String> warnings = new ArrayList<>();
    if (candidate.equals("CANDIDATE-B-SPATIAL")
        && edges.stream().anyMatch(edge -> edge.distanceJudgmentBlocked()
            && !edge.crossGeneration() && !edge.longGap() && !edge.clockRollback())) {
      warnings.add("误差坐标系不一致，空间候选不做距离判定");
    }
    if (edges.stream().anyMatch(edge -> Math.abs(edge.from().activityIndex() - edge.to().activityIndex()) > 35)) {
      warnings.add("加速度摘要跨状态过渡，时长上界按相邻观测各分摊一半");
    }
    return warnings;
  }

  private String barrierType(
      boolean crossGeneration,
      boolean clockRollback,
      boolean longGap,
      boolean errorCrsMismatch) {
    if (crossGeneration) {
      return "CROSS_GENERATION";
    }
    if (clockRollback) {
      return "CLOCK_ANOMALY";
    }
    if (longGap) {
      return "LONG_GAP";
    }
    if (errorCrsMismatch) {
      return null;
    }
    return null;
  }

  private List<String> edgeReasons(
      boolean crossGeneration,
      boolean clockRollback,
      boolean longGap,
      boolean errorCrsMismatch) {
    List<String> reasons = new ArrayList<>();
    if (crossGeneration) {
      reasons.add("跨设备代次：禁止速度计算，不连接轨迹");
    }
    if (clockRollback) {
      reasons.add("设备时钟回跳：保留迟到记录但禁止速度推断");
    }
    if (longGap) {
      reasons.add("长时间缺测：不用直线伪造迁徙路径");
    }
    if (errorCrsMismatch) {
      reasons.add("误差椭圆坐标系与轨迹坐标系不一致：拒绝距离判定");
    }
    return reasons;
  }

  private List<String> buildWarnings(List<Edge> edges, List<AnalysisModel.FixView> fixes) {
    List<String> warnings = new ArrayList<>();
    if (edges.stream().anyMatch(Edge::crossGeneration)) {
      warnings.add("存在跨项圈更换边界，已禁止跨代速度计算。");
    }
    if (edges.stream().anyMatch(Edge::longGap)) {
      warnings.add("存在长缺测窗口，未绘制直线，已分别统计时长上下界。");
    }
    if (edges.stream().anyMatch(Edge::distanceJudgmentBlocked)) {
      warnings.add("存在受保护边，页面不依据该边做距离或速度判断。");
    }
    if (fixes.stream().anyMatch(AnalysisModel.FixView::clockRollback)) {
      warnings.add("存在设备时钟回跳记录；真实返回旧位置按连续时间和重复到访位置另作标记。");
    }
    return warnings;
  }

  private List<String> parseRetainedCandidates() {
    return repository.decision("retainedCandidates")
        .map(value -> List.of(value.split("\\|")))
        .orElseGet(() -> List.of("CANDIDATE-A-ACCEL", "CANDIDATE-B-SPATIAL"));
  }

  private double distance(FixRow from, FixRow to) {
    double dx = to.xM() - from.xM();
    double dy = to.yM() - from.yM();
    return Math.sqrt(dx * dx + dy * dy);
  }

  private List<Integer> distinct(List<Integer> values) {
    return new ArrayList<>(new java.util.LinkedHashSet<>(values));
  }

  private AnalysisModel.GenerationView toGenerationView(GenerationRow row) {
    return new AnalysisModel.GenerationView(row.id(), row.generationNo(), row.deviceId(),
        row.boundaryAt(), row.confirmed(), row.note());
  }

  private AnalysisModel.ParameterView toParameterView(ParameterRow row) {
    return new AnalysisModel.ParameterView(row.version(), row.deviceId(), row.label(), row.configJson());
  }

  private AnalysisModel.EventView toEventView(EventRow row) {
    return new AnalysisModel.EventView(row.id(), row.deviceId(), row.eventAt(), row.receivedAt(),
        row.type(), row.batteryV(), row.payloadJson());
  }

  private AnalysisModel.ActionView toActionView(ActionRow row) {
    return new AnalysisModel.ActionView(row.id(), row.actionAt(), row.actionType(), row.targetType(),
        row.targetId(), row.payloadJson(), row.operator());
  }

  private AnalysisModel.FixView toFixView(FixRow fix, Map<String, Boolean> returnFlags) {
    List<String> warnings = new ArrayList<>();
    if (!fix.errorCrs().equals(fix.coordCrs())) {
      warnings.add("误差椭圆坐标系与轨迹坐标系不一致");
    }
    if (fix.clockRollback()) {
      warnings.add("后收到记录的设备时间早于前一条，识别为时钟回跳");
    }
    if (fix.lateAfterReplacement()) {
      warnings.add("旧设备在更换后上传的迟到记录");
    }
    if (Boolean.TRUE.equals(returnFlags.get(fix.id()))) {
      warnings.add("连续时间内重复到访旧位置，判定为动物返回");
    }
    if ("REVIEW".equals(fix.qualityStatus())) {
      warnings.add("低质量定位，等待人工接受或拒绝");
    }
    if ("REJECTED".equals(fix.qualityStatus())) {
      warnings.add("定位已被拒绝，不参与轨迹边和行为分段");
    }
    if (fix.qualityScore() < 60 && !"REJECTED".equals(fix.qualityStatus())) {
      warnings.add("低质量定位，可由人工拒绝");
    }
    return new AnalysisModel.FixView(
        fix.id(), fix.deviceId(), fix.generationNo(), fix.recordedAt(), fix.receivedAt(),
        fix.xM(), fix.yM(), fix.coordCrs(), fix.errorMajorM(), fix.errorMinorM(),
        fix.errorOrientationDeg(), fix.errorCrs(), fix.satellites(), fix.hdop(),
        fix.activityIndex(), fix.batteryV(), fix.parameterVersion(), fix.qualityScore(),
        fix.qualityStatus(), fix.rejectionReason(), fix.clockRollback(),
        fix.lateAfterReplacement(), Boolean.TRUE.equals(returnFlags.get(fix.id())), warnings);
  }

  private AnalysisModel.EdgeView toEdgeView(Edge edge) {
    return new AnalysisModel.EdgeView(
        edge.id(), edge.fromFixId(), edge.toFixId(), edge.from().generationNo(),
        edge.to().generationNo(), edge.startAt().toString(), edge.endAt().toString(),
        edge.elapsedSeconds(), rounded(edge.distanceMeters()), rounded(edge.speedMps()),
        edge.drawPath(), edge.crossGeneration(), edge.longGap(), edge.clockRollback(),
        edge.distanceJudgmentBlocked(), edge.barrierType(), edge.reasons());
  }

  private Double rounded(Double value) {
    if (value == null) {
      return null;
    }
    return Math.round(value * 1000.0d) / 1000.0d;
  }

  private record Edge(
      String id,
      FixRow from,
      FixRow to,
      Instant startAt,
      Instant endAt,
      long elapsedSeconds,
      Double distanceMeters,
      Double speedMps,
      boolean drawPath,
      boolean crossGeneration,
      boolean longGap,
      boolean clockRollback,
      boolean distanceJudgmentBlocked,
      String barrierType,
      List<String> reasons) {
    String fromFixId() {
      return from.id();
    }

    String toFixId() {
      return to.id();
    }
  }
}
