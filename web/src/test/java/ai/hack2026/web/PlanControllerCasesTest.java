package ai.hack2026.web;

import ai.hack2026.web.audit.AuditService;
import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.auth.Role;
import ai.hack2026.web.execution.PlanRecord;
import ai.hack2026.web.execution.PlanRecordRepository;
import ai.hack2026.web.execution.SpecRecordRepository;
import ai.hack2026.web.queue.JobQueueService;
import ai.hack2026.web.usage.UsageService;
import ai.hack2026.web.worker.WorkerClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v0.7 3.6a(自然言語での項目追加)。開発者が指定したテスト(f)(他組織のプランには追加できない)と、
 * 監査ログ(testcase_add_requested/testcase_added)を、PlanControllerQueueTestと同じ方針
 * (SpringBootTestを使わない素のMockitoユニットテスト)で確認する。
 */
class PlanControllerCasesTest {

    private static final Long ORG_ID = 2L;
    private static final Long OTHER_ORG_ID = 9L;
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
        when(planRecordRepository.findByPlanIdAndOrganizationId("p-1", OTHER_ORG_ID)).thenReturn(Optional.empty());
    }

    @Test
    void proposeForwardsTextToWorkerAndRecordsAudit() {
        stubPlanRecord();
        Map<String, Object> workerResponse = Map.of("planId", "p-1", "proposals", List.of());
        when(workerClient.proposeTestCases("p-1", "退会確認画面を見たい")).thenReturn(workerResponse);

        Map<String, Object> result = controller.proposeCases(user, "p-1", Map.of("text", "退会確認画面を見たい"));

        assertEquals(workerResponse, result);
        verify(workerClient, times(1)).proposeTestCases("p-1", "退会確認画面を見たい");
        verify(auditService, times(1)).record(eq(ORG_ID), eq(1L), eq("testcase_add_requested"), contains("planId=p-1"));
    }

    @Test
    void addCasesForwardsAcceptedIdsAndRecordsAudit() {
        stubPlanRecord();
        Map<String, Object> updatedPlan = Map.of("planId", "p-1", "status", "ready");
        when(workerClient.addTestCases("p-1", List.of("PROP-1"))).thenReturn(updatedPlan);

        Map<String, Object> result = controller.addCases(user, "p-1", Map.of("acceptedIds", List.of("PROP-1")));

        assertEquals(updatedPlan, result);
        verify(workerClient, times(1)).addTestCases("p-1", List.of("PROP-1"));
        verify(auditService, times(1)).record(eq(ORG_ID), eq(1L), eq("testcase_added"), contains("acceptedCount=1"));
    }

    @Test
    void proposeRejectsOtherOrganizationsPlan() {
        stubPlanRecord();
        AppUserPrincipal otherOrgUser = new AppUserPrincipal(5L, "other@example.com", "hash", OTHER_ORG_ID, Role.OWNER, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.proposeCases(otherOrgUser, "p-1", Map.of("text", "何か見たい")));

        assertEquals(404, ex.getStatusCode().value());
        verify(workerClient, never()).proposeTestCases(anyString(), anyString());
    }

    @Test
    void addCasesRejectsOtherOrganizationsPlan() {
        stubPlanRecord();
        AppUserPrincipal otherOrgUser = new AppUserPrincipal(5L, "other@example.com", "hash", OTHER_ORG_ID, Role.OWNER, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.addCases(otherOrgUser, "p-1", Map.of("acceptedIds", List.of("PROP-1"))));

        assertEquals(404, ex.getStatusCode().value());
        verify(workerClient, never()).addTestCases(anyString(), Mockito.anyList());
    }
}
