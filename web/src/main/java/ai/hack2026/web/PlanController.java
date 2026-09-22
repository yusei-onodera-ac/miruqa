package ai.hack2026.web;

import ai.hack2026.web.audit.AuditService;
import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.execution.PlanRecord;
import ai.hack2026.web.execution.PlanRecordRepository;
import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.execution.SpecRecordRepository;
import ai.hack2026.web.queue.JobQueueService;
import ai.hack2026.web.usage.UsageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

/**
 * ウィザードの3画面目(FR-37・v0.5): プラン(下見結果)の表示と、テスト項目書の確認・一括承認。
 * 危険度needs_approvalの項目を含め、選んだtestCaseIdだけがそのまま「承認」になる
 * (docs/contracts.md「ワーカーAPI」の POST /api/plans/{id}/approve)。
 * v0.6 P3(前半): PlanRecordで組織に紐付け、他組織のplanIdは404にする(テナント分離)。
 */
@Controller
public class PlanController {

    private final ai.hack2026.web.worker.WorkerClient workerClient;
    private final PlanRecordRepository planRecordRepository;
    private final SpecRecordRepository specRecordRepository;
    private final JobQueueService jobQueueService;
    private final UsageService usageService;
    private final ai.hack2026.web.credit.CreditService creditService;
    private final AuditService auditService;

    public PlanController(
            ai.hack2026.web.worker.WorkerClient workerClient,
            PlanRecordRepository planRecordRepository,
            SpecRecordRepository specRecordRepository,
            JobQueueService jobQueueService,
            UsageService usageService,
            ai.hack2026.web.credit.CreditService creditService,
            AuditService auditService) {
        this.workerClient = workerClient;
        this.planRecordRepository = planRecordRepository;
        this.specRecordRepository = specRecordRepository;
        this.jobQueueService = jobQueueService;
        this.usageService = usageService;
        this.creditService = creditService;
        this.auditService = auditService;
    }

    private PlanRecord requirePlanRecord(String planId, Long organizationId) {
        return planRecordRepository.findByPlanIdAndOrganizationId(planId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "プランが見つかりません"));
    }

    @GetMapping("/plans/{planId}")
    public String show(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable String planId, Model model) {
        requirePlanRecord(planId, user.getOrganizationId());
        model.addAttribute("planId", planId);
        return "plan";
    }

    @GetMapping(value = "/plans/{planId}/status.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> statusJson(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable String planId) {
        requirePlanRecord(planId, user.getOrganizationId());
        return workerClient.getPlan(planId);
    }

    /** 仕様トレーサビリティタブ用(結果画面がPlanと合わせて仕様項目のテキストを表示するために使う)。
     * v0.6 P3(前半・指揮官指摘): SpecRecordで組織に紐付け、他組織のspecIdは404にする
     * (組織の確認なしに任意のspecIdの中身を取得できてしまっていたIDORの是正)。 */
    @GetMapping(value = "/specs/{specId}.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> specJson(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable String specId) {
        specRecordRepository.findBySpecIdAndOrganizationId(specId, user.getOrganizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "仕様書が見つかりません"));
        return workerClient.getSpec(specId);
    }

    /** コスト上限等で途中打ち切りになった項目書生成の続きを行う(判断23)。非同期(下見はやり直さない)。 */
    @PostMapping("/plans/{planId}/resume")
    public String resume(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable String planId) {
        requirePlanRecord(planId, user.getOrganizationId());
        workerClient.resumePlan(planId);
        return "redirect:/plans/" + planId;
    }

    /** v0.6 P3(O-1・O-3): 承認と同時に即実行はせず、まずジョブキューに入れる。
     * 組織のコスト上限(日次・月次)に達している場合は、キューに入れる前に拒否する(AC-O3)。
     * 同時実行数の上限に空きがあれば、この場で即ディスパッチされる(体感としては従来どおり)。
     * 空きが無ければ、queued状態のまま/runs/{runId}へ遷移し、run.htmlが「待機中」と表示する。 */
    @PostMapping("/plans/{planId}/approve")
    public String approve(
            @AuthenticationPrincipal AppUserPrincipal user,
            @PathVariable String planId,
            @RequestParam(value = "testCaseIds", required = false) List<String> testCaseIds,
            RedirectAttributes redirectAttributes) {
        PlanRecord planRecord = requirePlanRecord(planId, user.getOrganizationId());

        // 指揮官バグ報告(2026-09-22): 残高不足・上限到達を例外で終わらせると、下見・項目書生成
        // 済みのプラン(planId)へ戻る手段が無く、ユーザーは「新規点検」からやり直すしかなくなり、
        // 直前のLLM費用(下見・生成)が無駄になっていた。このプランへリダイレクトし、
        // エラーをこの画面上に表示する(実行前のプランなので、費用をかけ直さず何度でも再試行できる)。
        String costRejection = usageService.costCapRejectionReason(user.getOrganizationId());
        if (costRejection != null) {
            redirectAttributes.addFlashAttribute("approveError", costRejection);
            return "redirect:/plans/" + planId;
        }
        // v0.8第2章: 開始前に、残高が最低額未満なら開始させない(ユーザーに見える上限は残高だけ)。
        if (!creditService.hasMinimumBalance(user.getOrganizationId())) {
            redirectAttributes.addFlashAttribute("approveError",
                    "残高が不足しているため、実行を開始できません(残高: " + creditService.getBalance(user.getOrganizationId())
                            + "クレジット、必要な最低残高: " + creditService.getMinBalanceToStart() + "クレジット)。"
                            + "チャージすると、このプランからそのまま実行できます。");
            redirectAttributes.addFlashAttribute("approveChargeReturnTo", "/plans/" + planId);
            return "redirect:/plans/" + planId;
        }

        workerClient.approvePlan(planId, testCaseIds == null ? List.of() : testCaseIds);
        RunRecord runRecord = jobQueueService.enqueue(planId, planRecord.getProjectId(), planRecord.getOrganizationId());
        jobQueueService.dispatchQueuedIfCapacity(planRecord.getOrganizationId());

        return "redirect:/runs/" + runRecord.getRunId();
    }

    /** v0.7 3.6a: 自然言語での項目追加(1)。文章から追加案(最大5件、未採用)を作る。
     * 入力文はデータとして扱う(ワーカー側で危険度・下見範囲のルールを変えない。第3.6a節5)。 */
    @PostMapping(value = "/plans/{planId}/cases/propose",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> proposeCases(
            @AuthenticationPrincipal AppUserPrincipal user,
            @PathVariable String planId,
            @RequestBody Map<String, Object> body) {
        requirePlanRecord(planId, user.getOrganizationId());
        String text = String.valueOf(body.getOrDefault("text", ""));
        auditService.record(user.getOrganizationId(), user.getUserId(), "testcase_add_requested",
                "planId=" + planId + " textLength=" + text.length());
        return workerClient.proposeTestCases(planId, text);
    }

    /** v0.7 3.6a: 自然言語での項目追加(3)(4)。追加案のうち、利用者がONにしたものだけを採用する。
     * 戻り値は更新後のPlan全体(plan.htmlは、これでテスト項目書を再描画する)。 */
    @PostMapping(value = "/plans/{planId}/cases",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> addCases(
            @AuthenticationPrincipal AppUserPrincipal user,
            @PathVariable String planId,
            @RequestBody Map<String, Object> body) {
        requirePlanRecord(planId, user.getOrganizationId());
        @SuppressWarnings("unchecked")
        List<String> acceptedIds = (List<String>) body.getOrDefault("acceptedIds", List.of());
        Map<String, Object> updatedPlan = workerClient.addTestCases(planId, acceptedIds);
        auditService.record(user.getOrganizationId(), user.getUserId(), "testcase_added",
                "planId=" + planId + " acceptedCount=" + acceptedIds.size());
        return updatedPlan;
    }
}
