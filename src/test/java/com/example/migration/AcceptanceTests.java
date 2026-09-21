package com.example.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.migration.model.Rows.FixRow;
import com.example.migration.model.Rows.PathPiece;
import com.example.migration.model.Rows.SegmentRow;
import com.example.migration.model.Rows.SpeedRow;
import com.example.migration.service.CrsMismatchException;
import com.example.migration.service.DistanceService;
import com.example.migration.service.ImportService;
import com.example.migration.service.ReviewService;
import com.example.migration.service.SegmentationService;
import com.example.migration.service.TrajectoryService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-migration.db"
})
class AcceptanceTests {

    @Autowired ImportService importService;
    @Autowired TrajectoryService trajectoryService;
    @Autowired SegmentationService segmentationService;
    @Autowired DistanceService distanceService;
    @Autowired ReviewService reviewService;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @BeforeEach
    void cleanDatabase() {
        importService.reset();
    }

    @Test
    void 跨项圈更换不计算速度_含旧设备迟到记录() {
        List<SpeedRow> speeds = trajectoryService.speeds(trajectoryService.defaultParam());
        // 代次 1 → 代次 2 的边界被阻断
        assertTrue(speeds.stream().anyMatch(s -> s.fromFixId() == 11 && s.toFixId() == 12
                && s.blocked() && s.blockReason().equals("CROSS_GENERATION")));
        // 旧设备的迟到记录（#15，属代次 1）即使时间相邻也不跨代计算
        assertTrue(speeds.stream().anyMatch(s -> s.toFixId() == 15
                && s.blocked() && s.blockReason().equals("CROSS_GENERATION")));
        // 所有成功计算的速度都在同一代次内
        Map<Long, FixRow> byId = new java.util.HashMap<>();
        trajectoryService.fixes().forEach(f -> byId.put(f.id(), f));
        for (SpeedRow s : speeds) {
            if (!s.blocked()) {
                assertEquals(byId.get(s.fromFixId()).generationId(), byId.get(s.toFixId()).generationId());
            }
        }
    }

    @Test
    void 长缺测空档不画直线路径() {
        List<SpeedRow> speeds = trajectoryService.speeds(trajectoryService.defaultParam());
        SpeedRow gap = speeds.stream().filter(s -> s.blocked() && s.blockReason().equals("LONG_GAP"))
                .findFirst().orElseThrow();
        assertEquals(8, gap.fromFixId());
        assertEquals(9, gap.toFixId());
        List<PathPiece> pieces = trajectoryService.pathPieces(trajectoryService.defaultParam());
        for (PathPiece piece : pieces) {
            assertFalse(piece.fixIds().contains(gap.fromFixId()) && piece.fixIds().contains(gap.toFixId()),
                    "长空档两端不得出现在同一条折线中");
        }
    }

    @Test
    void 误差椭圆坐标系不符时拒绝距离判定() throws Exception {
        // 定位 #11 的误差椭圆为 WGS84_GEO，与轨迹 LOCAL_GRID 不一致
        assertThrows(CrsMismatchException.class, () -> distanceService.judgeDistanceMeters(10, 11));
        // 两个 LOCAL_GRID 定位之间允许判定
        assertTrue(distanceService.judgeDistanceMeters(9, 10) > 0);
        // HTTP 层返回 409
        mvc.perform(get("/api/distance").param("a", "10").param("b", "11"))
                .andExpect(status().isConflict());
    }

    @Test
    void 时钟回跳与动物返回旧位置相互区分() {
        var rollbacks = trajectoryService.clockRollbacks();
        var returns = trajectoryService.spatialReturns();
        assertTrue(rollbacks.stream().anyMatch(a -> a.fixId() == 8), "定位#8 应识别为时钟回跳");
        assertTrue(returns.stream().anyMatch(r -> r.fixId() == 7 && r.earlierFixId() == 2),
                "定位#7 应识别为空间回返（回到#2 附近）");
        assertTrue(rollbacks.stream().noneMatch(a -> a.fixId() == 7), "空间回返不得误判为时钟回跳");
        assertTrue(returns.stream().noneMatch(r -> r.fixId() == 8), "时钟回跳不得误判为空间回返");
    }

    @Test
    void 缺测窗口时长分别统计上下界() {
        List<SegmentRow> segments = segmentationService.segments();
        assertFalse(segments.isEmpty());
        for (SegmentRow s : segments) {
            assertTrue(s.durationUpperSec() >= s.durationLowerSec(),
                    "上界不得小于下界: segment " + s.id());
        }
        // 长空档之后的片段（定位#9 起）上界应显著大于下界
        assertTrue(segments.stream().anyMatch(s -> s.startFixId() == 9
                && s.durationUpperSec() > s.durationLowerSec()));
    }

    @Test
    void 拒绝低质量定位后相关速度被阻断() {
        reviewService.rejectFix(2, "测试拒绝");
        List<SpeedRow> speeds = trajectoryService.speeds(trajectoryService.defaultParam());
        assertTrue(speeds.stream()
                .filter(s -> s.fromFixId() == 2 || s.toFixId() == 2)
                .allMatch(s -> s.blocked() && s.blockReason().equals("LOW_QUALITY")));
    }

    @Test
    void 两个状态序列候选可保留且参数不同() {
        var candidates = reviewService.candidates();
        assertEquals(2, candidates.size());
        reviewService.retainCandidate(candidates.get(0).id());
        reviewService.retainCandidate(candidates.get(1).id());
        assertTrue(reviewService.candidates().stream().allMatch(c -> c.retained()));
        // 两个候选对同一区域给出不同分段（sensitive 阈值更低，MOVE 覆盖的定位更多）
        long moveDefault = moveFixCount(candidates.get(0).id());
        long moveSensitive = moveFixCount(candidates.get(1).id());
        assertTrue(moveSensitive > moveDefault);
    }

    private long moveFixCount(long candidateId) {
        return segmentationService.segments().stream()
                .filter(s -> s.candidateId() == candidateId && s.state().equals("MOVE"))
                .mapToLong(s -> s.endFixId() - s.startFixId() + 1)
                .sum();
    }

    @Test
    void 运行记录可导出_清空后可重新导入复核() {
        reviewService.rejectFix(6, "HDOP 过高");
        reviewService.confirmGeneration(2);
        var record = reviewService.exportRunRecord(segmentationService.segments());
        List<?> actions = (List<?>) record.get("reviewActions");
        assertEquals(2, actions.size());

        importService.reset();
        assertEquals(15, jdbc.queryForObject("SELECT COUNT(*) FROM fix", Integer.class).intValue());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM review_action", Integer.class).intValue());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM segment_candidate", Integer.class).intValue());
    }
}
