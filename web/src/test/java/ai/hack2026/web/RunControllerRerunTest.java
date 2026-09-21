package ai.hack2026.web;

import ai.hack2026.web.audit.AuditService;
import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.auth.Role;
import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.execution.RunRecordRepository;
import ai.hack2026.web.queue.JobQueueService;
import ai.hack2026.web.usage.UsageService;
import ai.hack2026.web.worker.WorkerClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** v0.7 P6中核(再実行): POST /runs/{id}/rerun が、終了した実行から同じ承認済みPlanに対して
 * 新しいRunをジョブキューへ積み直すこと。RunControllerStopTestと同じ方針
 * (SpringBootTestを使わない素のMockitoユニットテスト)。 */
class RunControllerRerunTest {

    private static final Long ORG_ID = 2L;

    private final WorkerClient workerClient = Mockito.mock(WorkerClient.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);
    private final RunRecordRepository runRecordRepository = Mockito.mock(RunRecordRepository.class);
    private final JobQueueService jobQueueService = Mockito.mock(JobQueueService.class);
    private final UsageService usageService = Mockito.mock(UsageService.class);
    private final RunController controller = new RunController(
            workerClient, auditService, runRecordRepository, jobQueueService, usageService);
    private final AppUserPrincipal user = new AppUserPrincipal(1L, "test@example.com", "hash", ORG_ID, Role.OWNER, null);

    private RunRecord terminalRecord() {
        RunRecord record = Mockito.mock(RunRecord.class);
        when(record.isTerminal()).thenReturn(true);
        when(record.getPlanId()).thenReturn("p-1");
        when(record.getProjectId()).thenReturn(77L);
        when(record.getOrganizationId()).thenReturn(ORG_ID);
        when(runRecordRepository.findByRunIdAndOrganizationId("run-1", ORG_ID)).thenReturn(Optional.of(record));
        return record;
    }

    @Test
    void rerunEnqueuesNewRunAgainstSamePlanAndRecordsAudit() {
        terminalRecord();
        RunRecord newRecord = Mockito.mock(RunRecord.class);
        when(newRecord.getRunId()).thenReturn("run-2");
        when(jobQueueService.enqueue("p-1", 77L, ORG_ID)).thenReturn(newRecord);
        when(usageService.costCapRejectionReason(ORG_ID)).thenReturn(null);

        Map<String, Object> result = controller.rerun(user, "run-1");

        assertEquals("run-2", result.get("runId"));
        verify(jobQueueService, times(1)).enqueue("p-1", 77L, ORG_ID);
        verify(jobQueueService, times(1)).dispatchQueuedIfCapacity(ORG_ID);
        verify(auditService, times(1)).record(eq(ORG_ID), eq(1L), eq("run_rerun_requested"), contains("newRunId=run-2"));
    }

    @Test
    void rerunRejectsNonTerminalRun() {
        RunRecord record = Mockito.mock(RunRecord.class);
        when(record.isTerminal()).thenReturn(false);
        when(runRecordRepository.findByRunIdAndOrganizationId("run-1", ORG_ID)).thenReturn(Optional.of(record));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.rerun(user, "run-1"));

        assertEquals(409, ex.getStatusCode().value());
        verify(jobQueueService, never()).enqueue(anyString(), anyLong(), anyLong());
    }

    @Test
    void rerunRejectsWhenCostCapReached() {
        terminalRecord();
        when(usageService.costCapRejectionReason(ORG_ID)).thenReturn("上限に達しました");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.rerun(user, "run-1"));

        assertEquals(402, ex.getStatusCode().value());
        verify(jobQueueService, never()).enqueue(anyString(), anyLong(), anyLong());
    }

    @Test
    void rerunRejectsOtherOrganizationsRun() {
        when(runRecordRepository.findByRunIdAndOrganizationId("run-1", 9L)).thenReturn(Optional.empty());
        AppUserPrincipal otherOrgUser = new AppUserPrincipal(5L, "other@example.com", "hash", 9L, Role.OWNER, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.rerun(otherOrgUser, "run-1"));

        assertEquals(404, ex.getStatusCode().value());
        verify(jobQueueService, never()).enqueue(anyString(), anyLong(), anyLong());
    }
}
