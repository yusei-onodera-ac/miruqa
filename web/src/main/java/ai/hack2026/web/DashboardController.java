package ai.hack2026.web;

import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.domain.Domain;
import ai.hack2026.web.domain.DomainRepository;
import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.execution.RunRecordRepository;
import ai.hack2026.web.project.Project;
import ai.hack2026.web.project.ProjectRepository;
import ai.hack2026.web.util.DisplayFormat;
import ai.hack2026.web.worker.WorkerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ダッシュボード(v0.6 UIチェックポイント)。最近の実行・登録プロジェクト数・今月の実行回数・
 * 見つかった指摘の件数を表示する。数値は、実測できる範囲だけを出す(ダミーの数字・意味のない
 * グラフは出さない。第3a章)。組織単位のコスト集計はワーカー側に実装が無いため、今回は出さない
 * (docs/RESUME.mdに次の課題として記録)。
 */
@Controller
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);
    private static final int RECENT_RUNS_LIMIT = 10;

    private final ProjectRepository projectRepository;
    private final RunRecordRepository runRecordRepository;
    private final DomainRepository domainRepository;
    private final WorkerClient workerClient;
    private final ai.hack2026.web.credit.CreditService creditService;
    private final String planName;

    public DashboardController(
            ProjectRepository projectRepository,
            RunRecordRepository runRecordRepository,
            DomainRepository domainRepository,
            WorkerClient workerClient,
            ai.hack2026.web.credit.CreditService creditService,
            @Value("${PLAN_NAME:スタンダード}") String planName) {
        this.projectRepository = projectRepository;
        this.runRecordRepository = runRecordRepository;
        this.domainRepository = domainRepository;
        this.workerClient = workerClient;
        this.creditService = creditService;
        this.planName = planName;
    }

    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal AppUserPrincipal user, Model model) {
        Long organizationId = user.getOrganizationId();
        List<Project> projects = projectRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId);
        List<RunRecord> runRecords = runRecordRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId);

        Instant startOfMonth = Instant.now().atZone(ZoneId.systemDefault())
                .toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        long runsThisMonth = runRecords.stream().filter(r -> !r.getCreatedAt().isBefore(startOfMonth)).count();

        long unverifiedDomainCount = projects.stream()
                .filter(p -> !isDomainVerified(organizationId, p))
                .count();

        List<Map<String, Object>> recentRuns = new ArrayList<>();
        int totalFindings = 0;
        boolean workerError = false;
        for (RunRecord record : runRecords.stream().limit(RECENT_RUNS_LIMIT).toList()) {
            try {
                Map<String, Object> run = ai.hack2026.web.util.RunDisplay.resolve(workerClient, record);
                run.put("startedAtDisplay", DisplayFormat.jst(run.get("startedAt")));
                run.put("costDisplay", DisplayFormat.creditsOf(run, creditService.getUsdToJpyRate(), creditService.getMarkupMultiplier()));
                Object findings = run.get("findings");
                if (findings instanceof List<?> list) {
                    totalFindings += list.size();
                }
                recentRuns.add(run);
            } catch (RuntimeException e) {
                // ワーカーが止まっている・この実行がまだ無い等でも、ダッシュボード自体は落ちない
                log.info("ダッシュボード用のRun取得に失敗した(runId={}): {}", record.getRunId(), e.toString());
                workerError = true;
            }
        }

        recentRuns.sort((a, b) -> DisplayFormat.instantOf(b.get("startedAt"))
                .compareTo(DisplayFormat.instantOf(a.get("startedAt"))));

        model.addAttribute("projectCount", projects.size());
        model.addAttribute("runsThisMonth", runsThisMonth);
        model.addAttribute("unverifiedDomainCount", unverifiedDomainCount);
        model.addAttribute("totalFindings", totalFindings);
        model.addAttribute("recentRuns", recentRuns);
        model.addAttribute("workerError", workerError);

        // v0.7 P4(縮小)→v0.8第2章: プラン名とクレジット残高の表示。USDはユーザー向け画面に
        // 出さない(組織の日次・月次のUSD上限は、内部の安全弁としてUsageServiceに残るのみ)。
        model.addAttribute("planName", planName);
        model.addAttribute("creditBalance", creditService.getBalance(organizationId));
        model.addAttribute("creditMinBalanceToStart", creditService.getMinBalanceToStart());
        return "dashboard";
    }

    private boolean isDomainVerified(Long organizationId, Project project) {
        String hostname = hostnameOf(project.getTargetUrl());
        if (hostname == null) {
            return false;
        }
        return domainRepository.findByOrganizationIdAndHostname(organizationId, hostname)
                .map(Domain::isDiagnosisAllowed)
                .orElse(false);
    }

    private static String hostnameOf(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            if (uri.getHost() == null) {
                return null;
            }
            return uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
