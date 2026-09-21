package local.telemetry.review.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import local.telemetry.review.repository.ReviewRepository;
import local.telemetry.review.repository.ReviewRepository.EventRow;
import local.telemetry.review.repository.ReviewRepository.FixRow;
import local.telemetry.review.repository.ReviewRepository.GenerationRow;
import local.telemetry.review.repository.ReviewRepository.ImportRow;
import local.telemetry.review.repository.ReviewRepository.ParameterRow;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class FixtureImportService {
  public static final String FIXTURE_SOURCE = "fixed-fixture:migration-fixture.json";
  private static final String IMPORT_RUN_ID = "run-fixed-20260405";

  private final ReviewRepository repository;
  private final ObjectMapper objectMapper;

  public FixtureImportService(ReviewRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public synchronized ImportSummary ensureFixtureImported() {
    if (repository.hasFixes()) {
      return repository.latestImport()
          .map(row -> new ImportSummary(row.id(), row.source(), false, row.insertedFixes(),
              row.insertedEvents(), "数据库已有定位，保留现有审理结果"))
          .orElseGet(() -> new ImportSummary(IMPORT_RUN_ID, FIXTURE_SOURCE, false, 0, 0, "已有数据"));
    }
    return importFixture(true);
  }

  @Transactional
  public synchronized ImportSummary resetAndImport() {
    repository.clearAll();
    return importFixture(false);
  }

  public FixtureModel.Fixture readFixture() {
    try {
      return objectMapper.readValue(new ClassPathResource("fixtures/migration-fixture.json").getInputStream(),
          FixtureModel.Fixture.class);
    } catch (IOException e) {
      throw new IllegalStateException("无法读取固定 fixture", e);
    }
  }

  private ImportSummary importFixture(boolean preserveIfEmpty) {
    FixtureModel.Fixture fixture = readFixture();
    if (!fixture.generations().isEmpty() && fixture.generations().get(0).boundaryAt() != null) {
      // The marker is retained so generation G1 can be reviewed in chronological context.
    }

    for (FixtureModel.GenerationFixture generation : fixture.generations()) {
      repository.insertGeneration(new GenerationRow(
          generation.id(), fixture.animalId(), generation.generationNo(), generation.deviceId(),
          generation.boundaryAt(), generation.confirmed(), generation.note()));
    }

    for (FixtureModel.ParameterFixture parameter : fixture.parameterVersions()) {
      repository.insertParameter(new ParameterRow(
          parameter.version(), parameter.deviceId(), parameter.label(),
          objectMapper.valueToTree(parameter.config()).toString()), parameter.config());
    }

    for (FixtureModel.EventFixture event : fixture.events()) {
      repository.insertEvent(new EventRow(
          event.id(), fixture.animalId(), event.deviceId(), event.eventAt(), event.receivedAt(),
          event.type(), event.batteryV(), objectMapper.valueToTree(
              event.payload() == null ? Map.of() : event.payload()).toString()),
          event.payload() == null ? Map.of() : event.payload());
    }

    Map<String, Instant> lastRecordedByDevice = new HashMap<>();
    Set<String> clockRollbackIds = new HashSet<>();
    List<FixtureModel.FixFixture> orderedByReceipt = new ArrayList<>(fixture.fixes());
    orderedByReceipt.sort(Comparator.comparing(FixtureModel.FixFixture::receivedAt)
        .thenComparing(FixtureModel.FixFixture::id));
    for (FixtureModel.FixFixture fix : orderedByReceipt) {
      Instant recordedAt = Instant.parse(fix.recordedAt());
      Instant previous = lastRecordedByDevice.get(fix.deviceId());
      if (previous != null && recordedAt.isBefore(previous)) {
        clockRollbackIds.add(fix.id());
      }
      lastRecordedByDevice.put(fix.deviceId(), recordedAt);
    }

    String deviceByGeneration = fixture.generations().stream()
        .filter(generation -> generation.generationNo() == 1)
        .map(FixtureModel.GenerationFixture::deviceId)
    .findFirst().orElse("");
    Instant replacementAt = fixture.generations().stream()
        .filter(generation -> generation.generationNo() == 1 && generation.boundaryAt() != null)
        .map(generation -> Instant.parse(generation.boundaryAt()))
        .findFirst().orElse(null);

    int insertedFixes = 0;
    for (FixtureModel.FixFixture fix : fixture.fixes()) {
      int score = qualityScore(fix);
      String status = "ACCEPTED";
      boolean late = replacementAt != null
          && fix.deviceId().equals(deviceByGeneration)
          && Instant.parse(fix.receivedAt()).isAfter(replacementAt);
      String parameterVersion = fix.deviceId().equals(deviceByGeneration) ? "P-OLD-1" : "P-NEW-2";
      repository.insertFix(new FixRow(
          fix.id(), fixture.animalId(), fix.deviceId(), fix.generationNo(), fix.recordedAt(),
          fix.receivedAt(), fix.xM(), fix.yM(), fix.coordCrs(), fix.errorMajorM(),
          fix.errorMinorM(), fix.errorOrientationDeg(), fix.errorCrs(), fix.satellites(),
          fix.hdop(), fix.activityIndex(), fix.batteryV(), parameterVersion, score, status,
          null, clockRollbackIds.contains(fix.id()), late, IMPORT_RUN_ID));
      insertedFixes++;
    }

    String now = Instant.now().toString();
    String notes = preserveIfEmpty
        ? "空库自动导入固定 fixture；定位按记录时间排序，跨代边界仍待人工确认"
        : "清空数据库后重新导入固定 fixture，可复核同一审理场景";
    repository.insertImport(new ImportRow(
        IMPORT_RUN_ID, FIXTURE_SOURCE, now, now, "COMPLETED", insertedFixes,
        fixture.events().size(), notes));
    return new ImportSummary(IMPORT_RUN_ID, FIXTURE_SOURCE, true, insertedFixes,
        fixture.events().size(), notes);
  }

  private int qualityScore(FixtureModel.FixFixture fix) {
    int score = 100;
    score -= (int) Math.round(Math.max(0, fix.hdop() - 1.0) * 10);
    if (fix.satellites() <= 4) {
      score -= 20;
    } else if (fix.satellites() <= 6) {
      score -= 10;
    }
    if (fix.errorMajorM() > 200) {
      score -= 15;
    } else if (fix.errorMajorM() > 120) {
      score -= 8;
    }
    if (!fix.errorCrs().equals(fix.coordCrs())) {
      score -= 15;
    }
    return Math.max(0, Math.min(100, score));
  }

  public record ImportSummary(
      String runId,
      String source,
      boolean inserted,
      int fixes,
      int events,
      String notes) {
  }
}
