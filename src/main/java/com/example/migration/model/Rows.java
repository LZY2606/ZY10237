package com.example.migration.model;

import java.util.List;

/** 数据库行与 API 视图对象。时间一律为 epoch 秒，坐标一律为 LOCAL_GRID 米。 */
public final class Rows {

    private Rows() {
    }

    public record FixRow(long id, long generationId, long deviceTs, double x, double y,
                         double hdop, String ellipseCrs, Double ellipseA, Double ellipseB,
                         Double ellipseTheta, boolean rejected, String rejectReason, boolean late) {
    }

    public record GenerationRow(long id, String deviceId, long startedTs, long endedTs, boolean confirmed) {
    }

    public record SpeedRow(long fromFixId, long toFixId, Long generationId, Long dtSec,
                           Double distanceM, Double speedMps, boolean blocked, String blockReason) {
    }

    public record PathPiece(long generationId, List<Long> fixIds, List<double[]> points) {
    }

    public record SegmentRow(long id, long candidateId, String state, long generationId,
                             long startFixId, long endFixId, long startTs, long endTs,
                             long durationLowerSec, long durationUpperSec, long paramVersionId) {
    }

    public record CandidateRow(long id, String name, long paramVersionId, boolean retained, boolean active) {
    }

    public record ParamVersionRow(long id, String name, double speedThresholdMps,
                                  long gapThresholdSec, String source) {
    }

    public record TimeAnomaly(long fixId, long generationId, long prevTs, long deviceTs, String kind) {
    }

    public record SpatialReturn(long fixId, long earlierFixId, double distanceM) {
    }

    public record AccelRow(long id, long generationId, long windowStart, long windowEnd, double meanActivity) {
    }

    public record EventRow(long id, long generationId, long ts, String type) {
    }

    public record BatteryRow(long id, long generationId, long ts, double voltage, int percent) {
    }

    public record ReviewActionRow(long id, long ts, String type, String payload) {
    }
}
