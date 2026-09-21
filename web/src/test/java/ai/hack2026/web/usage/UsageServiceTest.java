package ai.hack2026.web.usage;

import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.worker.WorkerClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v0.6 P3(B-2・B-3): 使用量の記録の冪等性(AC-B1、run_idにUNIQUE制約による二重計上の防止)と、
 * 組織単位のコスト上限の判定(AC-O3)を確認する。
 */
@SpringBootTest
@ActiveProfiles("test")
class UsageServiceTest {

    @Autowired
    private UsageEventRepository usageEventRepository;

    private static final Long ORG_ID = 9101L;
    private static final Long PROJECT_ID = 8101L;

    @Test
    void recordingTheSameRunTwiceCreatesOnlyOneUsageEvent() {
        WorkerClient workerClient = Mockito.mock(WorkerClient.class);
        UsageService service = new UsageService(usageEventRepository, workerClient, 3.0, 30.0, 0.8);
        RunRecord record = completedRecord("run-double-count-test");
        when(workerClient.getRunCost(record.getWorkerRunId()))
                .thenReturn(Map.of("totalCostUsdSettled", 0.1234, "totalCostUsdInline", 0.1234, "callCount", 5));

        service.recordIfAbsent(record);
        service.recordIfAbsent(record); // 2回目(ポーリングとスケジューラの両方から呼ばれる想定)

        List<UsageEvent> all = usageEventRepository.findAll().stream()
                .filter(e -> e.getRunId().equals(record.getRunId())).toList();
        assertEquals(1, all.size(), "run_idにUNIQUE制約があるため、2回目は記録されないはず");
        assertEquals(0.1234, all.get(0).getCostUsd(), 0.0001);
        verify(workerClient, times(1)).getRunCost(record.getWorkerRunId());
    }

    @Test
    void recordUsesInlineCostWhenSettledIsZero() {
        WorkerClient workerClient = Mockito.mock(WorkerClient.class);
        UsageService service = new UsageService(usageEventRepository, workerClient, 3.0, 30.0, 0.8);
        RunRecord record = completedRecord("run-inline-fallback-test");
        when(workerClient.getRunCost(record.getWorkerRunId()))
                .thenReturn(Map.of("totalCostUsdSettled", 0.0, "totalCostUsdInline", 0.05, "callCount", 2));

        service.recordIfAbsent(record);

        UsageEvent event = usageEventRepository.findByRunId(record.getRunId()).orElseThrow();
        assertEquals(0.05, event.getCostUsd(), 0.0001);
    }

    @Test
    void costCapRejectsWhenDailyLimitReached() {
        WorkerClient workerClient = Mockito.mock(WorkerClient.class);
        UsageService service = new UsageService(usageEventRepository, workerClient, 0.10, 30.0, 0.8);
        Long orgId = ORG_ID + 1;
        recordFixedCost(service, workerClient, orgId, "run-cap-1", 0.06);
        recordFixedCost(service, workerClient, orgId, "run-cap-2", 0.06);

        String reason = service.costCapRejectionReason(orgId);

        assertNotNull(reason, "本日の使用量(0.12)が上限(0.10)を超えているため拒否されるはず");
        assertTrue(reason.contains("本日"));
    }

    @Test
    void costCapAllowsWhenUnderLimit() {
        WorkerClient workerClient = Mockito.mock(WorkerClient.class);
        UsageService service = new UsageService(usageEventRepository, workerClient, 3.0, 30.0, 0.8);
        Long orgId = ORG_ID + 2;
        recordFixedCost(service, workerClient, orgId, "run-under-cap", 0.01);

        String reason = service.costCapRejectionReason(orgId);

        assertNull(reason);
    }

    private void recordFixedCost(UsageService service, WorkerClient workerClient, Long orgId, String runId, double cost) {
        RunRecord record = new RunRecord();
        record.setRunId(runId);
        record.setPlanId("p-x");
        record.setProjectId(PROJECT_ID);
        record.setOrganizationId(orgId);
        record.setWorkerRunId(runId);
        record.setStatus(RunRecord.STATUS_COMPLETED);
        when(workerClient.getRunCost(runId)).thenReturn(Map.of("totalCostUsdSettled", cost, "callCount", 1));
        service.recordIfAbsent(record);
    }

    private static RunRecord completedRecord(String runId) {
        RunRecord record = new RunRecord();
        record.setRunId(runId);
        record.setPlanId("p-1");
        record.setProjectId(PROJECT_ID);
        record.setOrganizationId(ORG_ID);
        record.setWorkerRunId(runId);
        record.setStatus(RunRecord.STATUS_COMPLETED);
        return record;
    }
}
