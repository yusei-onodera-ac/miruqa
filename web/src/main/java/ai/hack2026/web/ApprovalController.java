package ai.hack2026.web;

import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.execution.RunRecordRepository;
import ai.hack2026.web.worker.WorkerClient;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * 承認UI(FR-38)。個別アクションの承認と、L4テスト計画の一括承認は、どちらも
 * approvalId 1個への decide で表現される(docs/contracts.md「ワーカーAPI」)。
 * v0.6 P3(前半): RunRecordで組織に紐付け、他組織のrunIdへの承認は404にする(テナント分離)。
 */
@Controller
public class ApprovalController {

    private final WorkerClient workerClient;
    private final RunRecordRepository runRecordRepository;

    public ApprovalController(WorkerClient workerClient, RunRecordRepository runRecordRepository) {
        this.workerClient = workerClient;
        this.runRecordRepository = runRecordRepository;
    }

    @PostMapping("/runs/{runId}/approvals/{approvalId}")
    public String decide(
            @AuthenticationPrincipal AppUserPrincipal user,
            @PathVariable String runId,
            @PathVariable String approvalId,
            @RequestParam String decision) {
        runRecordRepository.findByRunIdAndOrganizationId(runId, user.getOrganizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "実行が見つかりません"));
        workerClient.decideApproval(approvalId, decision);
        return "redirect:/runs/" + runId;
    }
}
