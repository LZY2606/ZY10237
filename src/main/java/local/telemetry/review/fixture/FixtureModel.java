package local.telemetry.review.fixture;

import java.util.List;
import java.util.Map;

public final class FixtureModel {
  private FixtureModel() {
  }

  public record Fixture(
      String animalId,
      List<GenerationFixture> generations,
      List<ParameterFixture> parameterVersions,
      List<EventFixture> events,
      List<FixFixture> fixes) {
  }

  public record GenerationFixture(
      String id,
      int generationNo,
      String deviceId,
      String boundaryAt,
      boolean confirmed,
      String note) {
  }

  public record ParameterFixture(
      String version,
      String deviceId,
      String label,
      Map<String, Object> config) {
  }

  public record EventFixture(
      String id,
      String deviceId,
      String eventAt,
      String receivedAt,
      String type,
      Double batteryV,
      Map<String, Object> payload) {
  }

  public record FixFixture(
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
      double batteryV) {
  }
}
