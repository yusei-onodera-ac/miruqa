package ai.hack2026.web;

import ai.hack2026.web.audit.AuditService;
import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.auth.Role;
import ai.hack2026.web.credit.CreditService;
import ai.hack2026.web.execution.PlanRecord;
import ai.hack2026.web.execution.PlanRecordRepository;
import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.execution.SpecRecordRepository;
import ai.hack2026.web.queue.JobQueueService;
import ai.hack2026.web.usage.UsageService;
import ai.hack2026.web.worker.WorkerClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * JobControllerConsentTestと同じ方針)。
 * v0.8バグ修正(2026-09-22・指揮官報告): 残高不足・コスト上限は、例外で終わらせず、
 * 下見・項目書生成済みのこのプラン(planId)へリダイレクト+画面上のエラー表示にする
 * (でないと、ユーザーは「新規点検」からやり直すしかなく、下見・生成のLLM費用が無駄になる)。 */
class PlanControllerQueueTest {

    private static final Long ORG_ID = 2L;
    private static final Long PROJECT_ID = 77L;

    private final WorkerClient workerClient = Mockito.mock(WorkerClient.class);
    private final PlanRecordRepository planRecordRepository = Mockito.mock(PlanRecordRepository.class);
    private final SpecRecordRepository specRecordRepository = Mockito.mock(SpecRecordRepository.class);
    private final JobQueueService jobQueueService = Mockito.mock(JobQueueService.class);
    private final UsageService usageService = Mockito.mock(UsageService.class);
    private final CreditService creditService = Mockito.mock(CreditService.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);
    private final PlanController controller = new PlanController(
            workerClient, planRecordRepository, specRecordRepository, jobQueueService, usageService, creditService, auditService);
    private final AppUserPrincipal user = new AppUserPrincipal(1L, "test@example.com", "hash", ORG_ID, Role.OWNER, null);

    {
        // 既定では残高十分(このテストの主眼はコスト上限であり、クレジット残高不足は別テストで確認する)
        when(creditService.hasMinimumBalance(ORG_ID)).thenReturn(true);
    }

    private void stubPlanRecord() {
        PlanRecord planRecord = Mockito.mock(PlanRecord.class);
        when(planRecord.getProjectId()).thenReturn(PROJECT_ID);
        when(planRecord.getOrganizationId()).thenReturn(ORG_ID);
        when(planRecordRepository.findByPlanIdAndOrganizationId("p-1", ORG_ID)).thenReturn(Optional.of(planRecord));
    }

    @Test
    void redirectsBackToThePlanWithErrorWhenOrgCostCapReached() {
        stubPlanRecord();
        when(usageService.costCapRejectionReason(ORG_ID)).thenReturn("本日のコスト上限に達しました");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.approve(user, "p-1", List.of("TC-001"), redirectAttributes);

        // 例外にせず、下見・項目書生成済みのこのプランへ戻す(費用をかけ直さず再試行できる)
        assertEquals("redirect:/plans/p-1", view);
        assertEquals("本日のコスト上限に達しました", redirectAttributes.getFlashAttributes().get("approveError"));
        verify(workerClient, never()).approvePlan(anyString(), any());
        verify(jobQueueService, never()).enqueue(anyString(), anyLong(), anyLong());
    }

    @Test
    void redirectsBackToThePlanWithChargeLinkWhenBalanceInsufficient() {
        stubPlanRecord();
        when(usageService.costCapRejectionReason(ORG_ID)).thenReturn(null);
        when(creditService.hasMinimumBalance(ORG_ID)).thenReturn(false);
        when(creditService.getBalance(ORG_ID)).thenReturn(10L);
        when(creditService.getMinBalanceToStart()).thenReturn(50L);
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.approve(user, "p-1", List.of("TC-001"), redirectAttributes);

        assertEquals("redirect:/plans/p-1", view);
        assertNotNull(redirectAttributes.getFlashAttributes().get("approveError"));
        assertEquals("/plans/p-1", redirectAttributes.getFlashAttributes().get("approveChargeReturnTo"));
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

        String view = controller.approve(user, "p-1", List.of("TC-001"), new RedirectAttributesModelMap());

        assertEquals("redirect:/runs/run-abc", view);
        verify(workerClient, times(1)).approvePlan("p-1", List.of("TC-001"));
        verify(jobQueueService, times(1)).enqueue("p-1", PROJECT_ID, ORG_ID);
        verify(jobQueueService, times(1)).dispatchQueuedIfCapacity(ORG_ID);
        // 直接ワーカーのstartRunを呼んでいないこと(ジョブキュー経由になったことの確認)
        verify(workerClient, never()).startRun(anyString());
    }
}
