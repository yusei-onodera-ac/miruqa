package ai.hack2026.web;

import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.execution.RunRecordRepository;
import ai.hack2026.web.util.DisplayFormat;
import ai.hack2026.web.util.RunDisplay;
import ai.hack2026.web.worker.WorkerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 実行の履歴一覧(FR-41・C)。v0.6 P3(前半): RunRecordで自組織の実行だけに絞り込む(テナント分離)。
 * v0.6 P3: ワーカーの全実行一覧から絞り込む方式(旧)は、ジョブキュー導入でRunRecordの
 * runId(Java側の安定した識別子)とワーカー側の実際のrunIdが一致しなくなったため使えなくなった。
 * 他の一覧画面(ダッシュボード等)と同じく、RunRecordを起点に{@link RunDisplay#resolve}で解決する。 */
@Controller
public class HistoryController {

    private static final Logger log = LoggerFactory.getLogger(HistoryController.class);
    private static final int MAX_RUNS_SCANNED = 50;

    private final WorkerClient workerClient;
    private final RunRecordRepository runRecordRepository;
    private final ai.hack2026.web.credit.CreditService creditService;

    public HistoryController(
            WorkerClient workerClient,
            RunRecordRepository runRecordRepository,
            ai.hack2026.web.credit.CreditService creditService) {
        this.workerClient = workerClient;
        this.runRecordRepository = runRecordRepository;
        this.creditService = creditService;
    }

    @GetMapping("/history")
    public String history(@AuthenticationPrincipal AppUserPrincipal user, Model model) {
        List<RunRecord> records = runRecordRepository.findByOrganizationIdOrderByCreatedAtDesc(user.getOrganizationId());

        List<Map<String, Object>> runs = new ArrayList<>();
        boolean workerError = false;
        for (RunRecord record : records.stream().limit(MAX_RUNS_SCANNED).toList()) {
            try {
                Map<String, Object> run = RunDisplay.resolve(workerClient, record);
                run.put("startedAtDisplay", DisplayFormat.jst(run.get("startedAt")));
                run.put("costDisplay", DisplayFormat.creditsOf(run, creditService.getUsdToJpyRate(), creditService.getMarkupMultiplier()));
                runs.add(run);
            } catch (RuntimeException e) {
                // ワーカーが止まっている・応答しないときも、この画面は落ちない(評価③・AC-21と同じ方針)。
                log.info("履歴一覧用のRun取得に失敗した(runId={}): {}", record.getRunId(), e.toString());
                workerError = true;
            }
        }
        runs.sort((a, b) -> DisplayFormat.instantOf(b.get("startedAt")).compareTo(DisplayFormat.instantOf(a.get("startedAt"))));
        model.addAttribute("runs", runs);
        model.addAttribute("workerError", workerError);
        return "history";
    }
}
