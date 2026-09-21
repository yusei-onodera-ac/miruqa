package ai.hack2026.web;

import ai.hack2026.web.audit.AuditService;
import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.execution.RunRecordRepository;
import ai.hack2026.web.queue.JobQueueService;
import ai.hack2026.web.usage.UsageService;
import ai.hack2026.web.worker.WorkerApiException;
import ai.hack2026.web.worker.WorkerClient;
import ai.hack2026.web.worker.WorkerUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 実行状況画面(FR-37)・承認UI(FR-38)・レポート表示(FR-39)・緊急停止(v0.6 P2)。
 * 画面は素のJavaScriptでポーリングする({@code /runs/{id}/status.json})。ワーカーの単一HTMLレポートは
 * そのまま埋め込む/表示する({@code /runs/{id}/report}、iframe)。
 * v0.6 P3(前半): RunRecordで組織に紐付け、他組織のrunIdは404にする(テナント分離)。
 * v0.6 P3: RunRecordはジョブキューのエントリでもある({@code status}/{@code workerRunId}参照)。
 * まだワーカーへ依頼していない(queued)間は、ワーカーへは一切問い合わせず合成の状態を返す。
 */
@Controller
public class RunController {

    private final WorkerClient workerClient;
    private final AuditService auditService;
    private final RunRecordRepository runRecordRepository;
    private final JobQueueService jobQueueService;
    private final UsageService usageService;

    public RunController(
            WorkerClient workerClient,
            AuditService auditService,
            RunRecordRepository runRecordRepository,
            JobQueueService jobQueueService,
            UsageService usageService) {
        this.workerClient = workerClient;
        this.auditService = auditService;
        this.runRecordRepository = runRecordRepository;
        this.jobQueueService = jobQueueService;
        this.usageService = usageService;
    }

    private RunRecord requireRunRecord(String runId, Long organizationId) {
        return runRecordRepository.findByRunIdAndOrganizationId(runId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "実行が見つかりません"));
    }

    @GetMapping("/runs/{runId}")
    public String show(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable String runId, Model model) {
        requireRunRecord(runId, user.getOrganizationId());
        model.addAttribute("runId", runId);
        return "run";
    }

    /** ポーリング用のJSONエンドポイント。実行中のRun(docs/contracts.md)＋この実行の承認待ち一覧を返す。 */
    @GetMapping(value = "/runs/{runId}/status.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> statusJson(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable String runId) {
        RunRecord record = requireRunRecord(runId, user.getOrganizationId());

        if (!record.isDispatched()) {
            // まだワーカーへ依頼していない(キューで待機中)。ワーカーへは問い合わせない。
            int position = jobQueueService.queuePositionOf(record);
            int ahead = Math.max(0, position - 1);
            Map<String, Object> run = new HashMap<>();
            run.put("status", "queued");
            run.put("queuePosition", position);
            run.put("queuedAheadCount", ahead);
            return Map.of("run", run, "pendingApprovals", List.of());
        }

        String workerRunId = record.getWorkerRunId();
        Map<String, Object> run;
        try {
            run = workerClient.getRun(workerRunId);
        } catch (WorkerApiException e) {
            if ("run_not_found".equals(e.getErrorCode())) {
                // ディスパッチ直後、ワーカーがまだ最初のrun.jsonを書く前の一瞬だけ起こりうる。
                return Map.of("run", Map.of("status", "queued"), "pendingApprovals", List.of());
            }
            throw e;
        }
        List<Map<String, Object>> approvals = workerClient.listPendingApprovals().stream()
                .filter(a -> workerRunId.equals(String.valueOf(a.get("runId"))))
                .collect(Collectors.toList());
        Map<String, Object> cost;
        try {
            cost = workerClient.getRunCost(workerRunId);
        } catch (WorkerApiException | WorkerUnavailableException e) {
            // コスト内訳が取れなくても、実行状況の表示自体は落とさない(AC-21)。
            cost = Map.of();
        }
        Map<String, Object> body = new HashMap<>();
        body.put("run", run);
        body.put("pendingApprovals", approvals);
        body.put("costBreakdown", cost);
        return body;
    }

    /** 緊急停止(v0.6 P2)。まだキューで待機中なら、ワーカーへは連絡せずキューから外すだけでよい。
     * 既にディスパッチ済みなら、従来どおりワーカーへキャンセルを要求する(協調的)。 */
    @PostMapping("/runs/{runId}/stop")
    public String stop(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable String runId) {
        RunRecord record = requireRunRecord(runId, user.getOrganizationId());
        if (!record.isDispatched()) {
            record.setStatus(RunRecord.STATUS_CANCELLED);
            record.setFinishedAt(java.time.Instant.now());
            runRecordRepository.save(record);
            usageService.recordIfAbsent(record);
        } else {
            workerClient.cancelRun(record.getWorkerRunId());
        }
        auditService.record(user.getOrganizationId(), user.getUserId(), "run_stop_requested", "runId=" + runId);
        return "redirect:/runs/" + runId;
    }

    /** v0.7 P6中核(再実行): 終了した実行(completed/failed/cancelled/interrupted/timeout)から、
     * 同じ承認済みPlanに対して新しいRunを作る。Plan自体は承認済みのまま変わらないため
     * (execute_plan()はplan.statusを変更しない)、既存のジョブキュー(承認時と同じ経路)へ
     * 積み直すだけでよい。まだ終わっていない実行は対象外(重複実行の混乱を避けるため)。 */
    @PostMapping("/runs/{runId}/rerun")
    @ResponseBody
    public Map<String, Object> rerun(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable String runId) {
        RunRecord record = requireRunRecord(runId, user.getOrganizationId());
        if (!record.isTerminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "この実行はまだ終わっていません。終了してから再実行してください。");
        }
        String costRejection = usageService.costCapRejectionReason(user.getOrganizationId());
        if (costRejection != null) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, costRejection);
        }
        RunRecord newRecord = jobQueueService.enqueue(record.getPlanId(), record.getProjectId(), record.getOrganizationId());
        jobQueueService.dispatchQueuedIfCapacity(user.getOrganizationId());
        auditService.record(user.getOrganizationId(), user.getUserId(), "run_rerun_requested",
                "runId=" + runId + " newRunId=" + newRecord.getRunId());
        return Map.of("runId", newRecord.getRunId());
    }

    @GetMapping(value = "/runs/{runId}/report", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> report(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable String runId) {
        RunRecord record = requireRunRecord(runId, user.getOrganizationId());
        String html = workerClient.getReportHtml(requireDispatched(record));
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    /** テスト項目書・結果のCSV書き出し(S)。ワーカーの/api/runs/{id}/export?format=csvをそのまま中継する。 */
    @GetMapping("/runs/{runId}/export.csv")
    @ResponseBody
    public ResponseEntity<byte[]> exportCsv(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable String runId) {
        RunRecord record = requireRunRecord(runId, user.getOrganizationId());
        byte[] csv = workerClient.getExportCsv(requireDispatched(record)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .header("Content-Disposition", "attachment; filename=\"" + runId + ".csv\"")
                .body(csv);
    }

    private static String requireDispatched(RunRecord record) {
        if (!record.isDispatched()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "この実行はまだ開始していません(キューで待機中です)");
        }
        return record.getWorkerRunId();
    }
}
