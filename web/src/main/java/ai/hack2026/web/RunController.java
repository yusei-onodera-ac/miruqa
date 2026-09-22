package ai.hack2026.web;

import ai.hack2026.web.audit.AuditService;
import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.execution.PlanRecord;
import ai.hack2026.web.execution.PlanRecordRepository;
import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.execution.RunRecordRepository;
import ai.hack2026.web.queue.JobQueueService;
import ai.hack2026.web.usage.UsageService;
import ai.hack2026.web.util.FindingFingerprint;
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
import java.util.Set;
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
    private final PlanRecordRepository planRecordRepository;
    private final JobQueueService jobQueueService;
    private final UsageService usageService;
    private final ai.hack2026.web.credit.CreditService creditService;

    public RunController(
            WorkerClient workerClient,
            AuditService auditService,
            RunRecordRepository runRecordRepository,
            PlanRecordRepository planRecordRepository,
            JobQueueService jobQueueService,
            UsageService usageService,
            ai.hack2026.web.credit.CreditService creditService) {
        this.workerClient = workerClient;
        this.auditService = auditService;
        this.runRecordRepository = runRecordRepository;
        this.planRecordRepository = planRecordRepository;
        this.jobQueueService = jobQueueService;
        this.usageService = usageService;
        this.creditService = creditService;
    }

    private RunRecord requireRunRecord(String runId, Long organizationId) {
        return runRecordRepository.findByRunIdAndOrganizationId(runId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "実行が見つかりません"));
    }

    /** v0.8第4章: このRunの元になったPlanが「前回の結果を引き継いだ」ものであれば、前回のRunの
     * findingsと突き合わせ、新規・修正済み・継続中に分けて返す(FindingFingerprintで同一視。
     * organizationIdで絞り込んだリポジトリ経由のため、他組織のRunを比較対象にはできない)。
     * 前回データが取れない場合はnull(呼び出し元は表示しないだけで、実行自体は落とさない)。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> computeDiffAgainstPrevious(RunRecord record, Map<String, Object> currentRun, Long organizationId) {
        PlanRecord planRecord = planRecordRepository.findByPlanIdAndOrganizationId(record.getPlanId(), organizationId).orElse(null);
        if (planRecord == null || planRecord.getCarriedOverFromRunId() == null) {
            return null;
        }
        RunRecord previousRecord = runRecordRepository
                .findByRunIdAndOrganizationId(planRecord.getCarriedOverFromRunId(), organizationId).orElse(null);
        if (previousRecord == null || previousRecord.getWorkerRunId() == null) {
            return null;
        }
        Map<String, Object> previousRun;
        try {
            previousRun = workerClient.getRun(previousRecord.getWorkerRunId());
        } catch (WorkerApiException | WorkerUnavailableException e) {
            return null;
        }
        List<Map<String, Object>> currentFindings = (List<Map<String, Object>>) currentRun.getOrDefault("findings", List.of());
        List<Map<String, Object>> previousFindings = (List<Map<String, Object>>) previousRun.getOrDefault("findings", List.of());
        Set<String> previousFingerprints = previousFindings.stream().map(FindingFingerprint::compute).collect(Collectors.toSet());
        Set<String> currentFingerprints = currentFindings.stream().map(FindingFingerprint::compute).collect(Collectors.toSet());

        List<Map<String, Object>> newFindings = currentFindings.stream()
                .filter(f -> !previousFingerprints.contains(FindingFingerprint.compute(f))).toList();
        List<Map<String, Object>> fixedFindings = previousFindings.stream()
                .filter(f -> !currentFingerprints.contains(FindingFingerprint.compute(f))).toList();
        List<Map<String, Object>> continuingFindings = currentFindings.stream()
                .filter(f -> previousFingerprints.contains(FindingFingerprint.compute(f))).toList();

        Map<String, Object> diff = new HashMap<>();
        diff.put("previousRunId", previousRecord.getRunId());
        diff.put("newFindings", newFindings);
        diff.put("fixedFindings", fixedFindings);
        diff.put("continuingFindings", continuingFindings);
        return diff;
    }

    @GetMapping("/runs/{runId}")
    public String show(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable String runId, Model model) {
        requireRunRecord(runId, user.getOrganizationId());
        model.addAttribute("runId", runId);
        // v0.8第2章: クレジットへの換算は、画面側(JS)でも同じ計算をできるよう定数を渡す
        // (USDはユーザー向け画面に出さない。表示専用で、実際の消費計上はJobQueueServiceが行う)。
        model.addAttribute("creditRate", creditService.getUsdToJpyRate());
        model.addAttribute("creditMarkup", creditService.getMarkupMultiplier());
        return "run";
    }

    /** ポーリング用のJSONエンドポイント。実行中のRun(docs/contracts.md)＋この実行の承認待ち一覧を返す。 */
    @GetMapping(value = "/runs/{runId}/status.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> statusJson(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable String runId) {
        RunRecord record = requireRunRecord(runId, user.getOrganizationId());

        if (!record.isDispatched()) {
            if (record.isPaused()) {
                // v0.8第6章: まだワーカーへ依頼していない段階で停止された(キュー待ちのまま)場合。
                Map<String, Object> run = new HashMap<>();
                run.put("status", "paused");
                run.put("pauseReason", record.getPauseReason());
                return Map.of("run", run, "pendingApprovals", List.of(),
                        "creditBalance", creditService.getBalance(user.getOrganizationId()));
            }
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
        // v0.8第2章: 既存のポーリングに、組織のクレジット残高を乗せる(実行中に減っていくのが
        // 見えるようにする。実際の消費計上はJobQueueServiceの同期処理が行う。ここは表示専用)。
        body.put("creditBalance", creditService.getBalance(user.getOrganizationId()));
        // v0.8第4章: 「前回の結果を引き継いだ」実行なら、前回との差分(新規・修正済み・継続中)を乗せる。
        Map<String, Object> diff = computeDiffAgainstPrevious(record, run, user.getOrganizationId());
        if (diff != null) {
            body.put("previousDiff", diff);
        }
        return body;
    }

    /** 緊急停止(v0.6 P2)。まだキューで待機中なら、ワーカーへは連絡せずキューから外すだけでよい。
     * 既にディスパッチ済みなら、従来どおりワーカーへキャンセルを要求する(協調的)。 */
    @PostMapping("/runs/{runId}/stop")
    public String stop(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable String runId) {
        RunRecord record = requireRunRecord(runId, user.getOrganizationId());
        if (!record.isDispatched()) {
            // v0.8第6章: まだワーカーへ依頼していない(キュー待ち)場合も、失敗にせず
            // 「中断(paused)」で確定する。再開は、単に(未実行のまま)キューへ戻すだけでよい。
            record.setStatus(RunRecord.STATUS_PAUSED);
            record.setPauseReason("user_stop");
            record.setFinishedAt(java.time.Instant.now());
            runRecordRepository.save(record);
        } else {
            workerClient.cancelRun(record.getWorkerRunId(), "user_stop");
        }
        auditService.record(user.getOrganizationId(), user.getUserId(), "run_stop_requested", "runId=" + runId);
        return "redirect:/runs/" + runId;
    }

    /** v0.8第6章: 中断(paused)した実行を、完了済みの項目を再実行せず再開する。 */
    @PostMapping("/runs/{runId}/resume")
    public String resumeRun(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable String runId) {
        RunRecord record = requireRunRecord(runId, user.getOrganizationId());
        if (!record.isPaused()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "再開できるのは、一時停止した実行のみです。");
        }
        if ("credit_exhausted".equals(record.getPauseReason()) && !creditService.hasMinimumBalance(user.getOrganizationId())) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "残高が不足しているため再開できません。チャージしてください(残高: "
                            + creditService.getBalance(user.getOrganizationId()) + "クレジット)。");
        }
        jobQueueService.resume(record);
        auditService.record(user.getOrganizationId(), user.getUserId(), "run_resume_requested", "runId=" + runId);
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
