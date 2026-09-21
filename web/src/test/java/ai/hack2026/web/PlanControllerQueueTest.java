package ai.hack2026.web;

import ai.hack2026.web.audit.AuditService;
import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.auth.Role;
import ai.hack2026.web.execution.PlanRecord;
import ai.hack2026.web.execution.PlanRecordRepository;
import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.execution.SpecRecordRepository;
import ai.hack2026.web.queue.JobQueueService;
import ai.hack2026.web.usage.UsageService;
import ai.hack2026.web.worker.WorkerClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** v0.6 P3(O-3・AC-O3): 組織のコスト上限に達しているときは、承認そのものを拒否し、
 * ジョブキューに入れない・ワーカーへも一切連絡しないことを確認する(SpringBootTestは使わず、
 * WorkerClient/JobQueueService/UsageServiceをMockitoでモックした素のユニットテスト。
 * JobControllerConsentTestと同じ方針)。 */
class PlanControllerQueueTest {

    private static final Long ORG_ID = 2L;
    private static final Long PROJECT_ID = 77L;

    private final WorkerClient workerClient = Mockito.mock(WorkerClient.class);
    private final PlanRecordRepository planRecordRepository = Mockito.mock(PlanRecordRepository.class);
    private final SpecRecordRepository specRecordRepository = Mockito.mock(SpecRecordRepository.class);
    private final JobQueueService jobQueueService = Mockito.mock(JobQueueService.class);
    private final UsageService usageService = Mockito.mock(UsageService.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);
    private final PlanController controller = new PlanController(
            workerClient, planRecordRepository, specRecordRepository, jobQueueService, usageService, auditService);
    private final AppUserPrincipal user = new AppUserPrincipal(1L, "test@example.com", "hash", ORG_ID, Role.OWNER, null);

    private void stubPlanRecord() {
        PlanRecord planRecord = Mockito.mock(PlanRecord.class);
        when(planRecord.getProjectId()).thenReturn(PROJECT_ID);
        when(planRecord.getOrganizationId()).thenReturn(ORG_ID);
        when(planRecordRepository.findByPlanIdAndOrganizationId("p-1", ORG_ID)).thenReturn(Optional.of(planRecord));
    }

    @Test
    void rejectsApprovalWhenOrgCostCapReached() {
        stubPlanRecord();
        when(usageService.costCapRejectionReason(ORG_ID)).thenReturn("本日のコスト上限に達しました");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.approve(user, "p-1", List.of("TC-001")));

        assertEquals(402, ex.getStatusCode().value());
        verify(workerClient, never()).approvePlan(anyString(), any());
        verify(jobQueueService, never()).enqueue(anyString(), anyLong(), anyLong());
    }

    @Test
    void enqueuesAndAttemptsDispatchWhenUnderCostCap() {
        stubPlanRecord();
        when(usageService.costCapRejectionReason(ORG_ID)).thenReturn(null);
        RunRecord queuedRecord = new RunRecord();
        queuedRecord.setRunId("run-abc");
        when(jobQueueService.enqueue("p-1", PROJECT_ID, ORG_ID)).thenReturn(queuedRecord);

        String view = controller.approve(user, "p-1", List.of("TC-001"));

        assertEquals("redirect:/runs/run-abc", view);
        verify(workerClient, times(1)).approvePlan("p-1", List.of("TC-001"));
        verify(jobQueueService, times(1)).enqueue("p-1", PROJECT_ID, ORG_ID);
        verify(jobQueueService, times(1)).dispatchQueuedIfCapacity(ORG_ID);
        // 直接ワーカーのstartRunを呼んでいないこと(ジョブキュー経由になったことの確認)
        verify(workerClient, never()).startRun(anyString());
    }
}
