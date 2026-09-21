package com.example.migration.service;

import com.example.migration.model.Rows.FixRow;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 距离判定守卫：任一定位的误差椭圆坐标系与轨迹坐标系不一致时拒绝。 */
@Service
public class DistanceService {

    private final TrajectoryService trajectoryService;

    public DistanceService(TrajectoryService trajectoryService) {
        this.trajectoryService = trajectoryService;
    }

    public double judgeDistanceMeters(long fixAId, long fixBId) {
        Map<Long, FixRow> byId = trajectoryService.fixes().stream()
                .collect(Collectors.toMap(FixRow::id, Function.identity()));
        FixRow a = byId.get(fixAId);
        FixRow b = byId.get(fixBId);
        if (a == null || b == null) {
            throw new IllegalArgumentException("unknown fix id");
        }
        if (!TrajectoryService.TRAJECTORY_CRS.equals(a.ellipseCrs())
                || !TrajectoryService.TRAJECTORY_CRS.equals(b.ellipseCrs())) {
            throw new CrsMismatchException("误差椭圆坐标系与轨迹坐标系不一致，禁止距离判定: fix "
                    + a.id() + "=" + a.ellipseCrs() + ", fix " + b.id() + "=" + b.ellipseCrs()
                    + ", 轨迹=" + TrajectoryService.TRAJECTORY_CRS);
        }
        return Math.hypot(a.x() - b.x(), a.y() - b.y());
    }
}
