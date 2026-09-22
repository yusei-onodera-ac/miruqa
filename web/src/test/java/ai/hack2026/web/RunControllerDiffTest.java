package ai.hack2026.web;

import ai.hack2026.web.audit.AuditService;
import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.auth.Role;
import ai.hack2026.web.credit.CreditService;
import ai.hack2026.web.execution.PlanRecord;
import ai.hack2026.web.execution.PlanRecordRepository;
import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.execution.RunRecordRepository;
import ai.hack2026.web.queue.JobQueueService;
import ai.hack2026.web.usage.UsageService;
import ai.hack2026.web.worker.WorkerClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

/** v0.8第4章: 「前回の結果を引き継いだ」実行の、前回との差分(新規・修正済み・継続中)を確認する。
 * SpringBootTestは使わず、依存をすべてMockitoでモックした素のユニットテスト
 * (RunControllerStopTest等と同じ方針)。 */
class RunControllerDiffTest {

    private static final Long ORG_ID = 2L;
    private static final Long OTHER_ORG_ID = 9L;

    private final WorkerClient workerClient = Mockito.mock(WorkerClient.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);
    private final RunRecordRepository runRecordRepository = Mockito.mock(RunRecordRepository.class);
    private final PlanRecordRepository planRecordRepository = Mockito.mock(PlanRecordRepository.class);
    private final JobQueueService jobQueueService = Mockito.mock(JobQueueService.class);
    private final UsageService usageService = Mockito.mock(UsageService.class);
    private final CreditService creditService = Mockito.mock(CreditService.class);
    private final RunController controller = new RunController(
            workerClient, auditService, runRecordRepository, planRecordRepository, jobQueueService, usageService, creditService);
    private final AppUserPrincipal user = new AppUserPrincipal(1L, "test@example.com", "hash", ORG_ID, Role.OWNER, null);

    private static Map<String, Object> finding(String perspective, String kind, String title) {
        return Map.of("perspective", perspective, "kind", kind, "title", title, "specRef", List.of(), "detail", title);
    }

    @Test
    void diffCategorizesNewFixedAndContinuingFindings() {
        RunRecord currentRecord = Mockito.mock(RunRecord.class);
        when(currentRecord.isDispatched()).thenReturn(true);
        when(currentRecord.getWorkerRunId()).thenReturn("worker-run-current");
        when(currentRecord.getPlanId()).thenReturn("p-new");
        when(runRecordRepository.findByRunIdAndOrganizationId("run-new", ORG_ID)).thenReturn(Optional.of(currentRecord));

        PlanRecord planRecord = Mockito.mock(PlanRecord.class);
        when(planRecord.getCarriedOverFromRunId()).thenReturn("run-previous");
        when(planRecordRepository.findByPlanIdAndOrganizationId("p-new", ORG_ID)).thenReturn(Optional.of(planRecord));

        RunRecord previousRecord = Mockito.mock(RunRecord.class);
        when(previousRecord.getWorkerRunId()).thenReturn("worker-run-previous");
        when(previousRecord.getRunId()).thenReturn("run-previous");
        when(runRecordRepository.findByRunIdAndOrganizationId("run-previous", ORG_ID)).thenReturn(Optional.of(previousRecord));

        Map<String, Object> continuing = finding("P-SEC", "duplicate_submission", "二重注文");
        Map<String, Object> fixedOnly = finding("P-TEXT", "typo", "誤字(送量無料)");
        Map<String, Object> newOnly = finding("P-A11Y", "missing_alt", "alt欠落");

        when(workerClient.getRun("worker-run-current")).thenReturn(
                Map.of("status", "completed", "findings", List.of(continuing, newOnly)));
        when(workerClient.getRun("worker-run-previous")).thenReturn(
                Map.of("status", "completed", "findings", List.of(continuing, fixedOnly)));
        when(workerClient.listPendingApprovals()).thenReturn(List.of());
        when(workerClient.getRunCost("worker-run-current")).thenReturn(Map.of());

        Map<String, Object> response = controller.statusJson(user, "run-new");

        @SuppressWarnings("unchecked")
        Map<String, Object> diff = (Map<String, Object>) response.get("previousDiff");
        assertEquals("run-previous", diff.get("previousRunId"));
        assertEquals(1, ((List<?>) diff.get("newFindings")).size());
        assertEquals(1, ((List<?>) diff.get("fixedFindings")).size());
        assertEquals(1, ((List<?>) diff.get("continuingFindings")).size());
    }

    @Test
    void noDiffWhenPlanWasNotCarriedOver() {
        RunRecord currentRecord = Mockito.mock(RunRecord.class);
        when(currentRecord.isDispatched()).thenReturn(true);
        when(currentRecord.getWorkerRunId()).thenReturn("worker-run-current");
        when(currentRecord.getPlanId()).thenReturn("p-fresh");
        when(runRecordRepository.findByRunIdAndOrganizationId("run-fresh", ORG_ID)).thenReturn(Optional.of(currentRecord));

        PlanRecord planRecord = Mockito.mock(PlanRecord.class);
        when(planRecord.getCarriedOverFromRunId()).thenReturn(null);
        when(planRecordRepository.findByPlanIdAndOrganizationId("p-fresh", ORG_ID)).thenReturn(Optional.of(planRecord));

        when(workerClient.getRun("worker-run-current")).thenReturn(Map.of("status", "completed", "findings", List.of()));
        when(workerClient.listPendingApprovals()).thenReturn(List.of());
        when(workerClient.getRunCost("worker-run-current")).thenReturn(Map.of());

        Map<String, Object> response = controller.statusJson(user, "run-fresh");

        assertFalse(response.containsKey("previousDiff"));
    }

    @Test
    void previousRunFromAnotherOrganizationIsNeverUsedForDiff() {
        // v0.8第4章の受入基準: 他組織のデータを参照できないこと。
        // carriedOverFromRunIdが指すrunIdを、"同じ組織"のリポジトリでしか探さないため、
        // 他組織のrunIdが紛れ込んでいても見つからず(Optional.empty)、差分は出ない。
        RunRecord currentRecord = Mockito.mock(RunRecord.class);
        when(currentRecord.isDispatched()).thenReturn(true);
        when(currentRecord.getWorkerRunId()).thenReturn("worker-run-current");
        when(currentRecord.getPlanId()).thenReturn("p-new");
        when(runRecordRepository.findByRunIdAndOrganizationId("run-new", ORG_ID)).thenReturn(Optional.of(currentRecord));

        PlanRecord planRecord = Mockito.mock(PlanRecord.class);
        when(planRecord.getCarriedOverFromRunId()).thenReturn("run-belongs-to-other-org");
        when(planRecordRepository.findByPlanIdAndOrganizationId("p-new", ORG_ID)).thenReturn(Optional.of(planRecord));
        // わざと他組織IDでは登録されているが、ORG_IDでは見つからない状況を再現する
        when(runRecordRepository.findByRunIdAndOrganizationId("run-belongs-to-other-org", OTHER_ORG_ID))
                .thenReturn(Optional.of(Mockito.mock(RunRecord.class)));
        when(runRecordRepository.findByRunIdAndOrganizationId("run-belongs-to-other-org", ORG_ID))
                .thenReturn(Optional.empty());

        when(workerClient.getRun("worker-run-current")).thenReturn(Map.of("status", "completed", "findings", List.of()));
        when(workerClient.listPendingApprovals()).thenReturn(List.of());
        when(workerClient.getRunCost("worker-run-current")).thenReturn(Map.of());

        Map<String, Object> response = controller.statusJson(user, "run-new");

        assertFalse(response.containsKey("previousDiff"));
    }
}
