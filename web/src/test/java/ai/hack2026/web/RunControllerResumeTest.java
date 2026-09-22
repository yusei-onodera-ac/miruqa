package ai.hack2026.web;

import ai.hack2026.web.audit.AuditService;
import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.auth.Role;
import ai.hack2026.web.credit.CreditService;
import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.execution.RunRecordRepository;
import ai.hack2026.web.queue.JobQueueService;
import ai.hack2026.web.usage.UsageService;
import ai.hack2026.web.worker.WorkerClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** v0.8第6章: POST /runs/{id}/resume の分岐(再開可能・残高不足・pausedでない)を確認する。
 * SpringBootTestは使わず、依存をすべてMockitoでモックした素のユニットテスト
 * (RunControllerStopTest/RerunTestと同じ方針)。 */
class RunControllerResumeTest {

    private static final Long ORG_ID = 2L;

    private final WorkerClient workerClient = Mockito.mock(WorkerClient.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);
    private final RunRecordRepository runRecordRepository = Mockito.mock(RunRecordRepository.class);
    private final ai.hack2026.web.execution.PlanRecordRepository planRecordRepository =
            Mockito.mock(ai.hack2026.web.execution.PlanRecordRepository.class);
    private final JobQueueService jobQueueService = Mockito.mock(JobQueueService.class);
    private final UsageService usageService = Mockito.mock(UsageService.class);
    private final CreditService creditService = Mockito.mock(CreditService.class);
    private final RunController controller = new RunController(
            workerClient, auditService, runRecordRepository, planRecordRepository, jobQueueService, usageService, creditService);
    private final AppUserPrincipal user = new AppUserPrincipal(1L, "test@example.com", "hash", ORG_ID, Role.OWNER, null);

    @Test
    void resumeOnPausedRunDelegatesToJobQueueService() {
        RunRecord record = Mockito.mock(RunRecord.class);
        when(record.isPaused()).thenReturn(true);
        when(record.getPauseReason()).thenReturn("user_stop");
        when(runRecordRepository.findByRunIdAndOrganizationId("run-1", ORG_ID)).thenReturn(Optional.of(record));

        String view = controller.resumeRun(user, "run-1");

        assertEquals("redirect:/runs/run-1", view);
        verify(jobQueueService, times(1)).resume(record);
        verify(auditService, times(1)).record(anyLong(), anyLong(), org.mockito.ArgumentMatchers.eq("run_resume_requested"), anyString());
    }

    @Test
    void resumeRejectedWhenNotPaused() {
        RunRecord record = Mockito.mock(RunRecord.class);
        when(record.isPaused()).thenReturn(false);
        when(runRecordRepository.findByRunIdAndOrganizationId("run-2", ORG_ID)).thenReturn(Optional.of(record));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.resumeRun(user, "run-2"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(jobQueueService, never()).resume(Mockito.any());
    }

    @Test
    void resumeRejectedWhenCreditExhaustedAndStillInsufficient() {
        RunRecord record = Mockito.mock(RunRecord.class);
        when(record.isPaused()).thenReturn(true);
        when(record.getPauseReason()).thenReturn("credit_exhausted");
        when(runRecordRepository.findByRunIdAndOrganizationId("run-3", ORG_ID)).thenReturn(Optional.of(record));
        when(creditService.hasMinimumBalance(ORG_ID)).thenReturn(false);
        when(creditService.getBalance(ORG_ID)).thenReturn(0L);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.resumeRun(user, "run-3"));

        assertEquals(HttpStatus.PAYMENT_REQUIRED, ex.getStatusCode());
        verify(jobQueueService, never()).resume(Mockito.any());
    }

    @Test
    void resumeAllowedAfterChargeWhenCreditExhaustedButNowSufficient() {
        RunRecord record = Mockito.mock(RunRecord.class);
        when(record.isPaused()).thenReturn(true);
        when(record.getPauseReason()).thenReturn("credit_exhausted");
        when(runRecordRepository.findByRunIdAndOrganizationId("run-4", ORG_ID)).thenReturn(Optional.of(record));
        when(creditService.hasMinimumBalance(ORG_ID)).thenReturn(true);

        String view = controller.resumeRun(user, "run-4");

        assertEquals("redirect:/runs/run-4", view);
        verify(jobQueueService, times(1)).resume(record);
    }
}
