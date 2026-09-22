package ai.hack2026.web;

import ai.hack2026.web.audit.AuditService;
import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.auth.Organization;
import ai.hack2026.web.auth.OrganizationRepository;
import ai.hack2026.web.auth.User;
import ai.hack2026.web.auth.UserRepository;
import ai.hack2026.web.credit.CreditLedgerEntry;
import ai.hack2026.web.credit.CreditService;
import ai.hack2026.web.execution.PlanRecord;
import ai.hack2026.web.execution.PlanRecordRepository;
import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.execution.RunRecordRepository;
import ai.hack2026.web.project.Project;
import ai.hack2026.web.project.ProjectRepository;
import ai.hack2026.web.util.DisplayFormat;
import ai.hack2026.web.util.RunDisplay;
import ai.hack2026.web.worker.WorkerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.8第5章: マイページ(1画面に集約)。①アカウント情報(名前・組織名の変更のみ。メールは表示のみ、
 * パスワード変更は対象外) ②クレジット残高・チャージへの導線・取引履歴 ③利用履歴(過去の実行)
 * ④プロジェクト一覧(引き継ぎ再テストの入口)。USDは出さない(クレジットのみ)。
 * すべてのデータは、AppUserPrincipalのorganizationIdで絞り込む(他組織のデータは出さない)。
 */
@Controller
public class MyPageController {

    private static final Logger log = LoggerFactory.getLogger(MyPageController.class);
    private static final int RECENT_RUNS_LIMIT = 10;
    private static final int RECENT_CREDIT_ENTRIES_LIMIT = 10;
    private static final int PENDING_PLANS_LIMIT = 10;
    private static final DateTimeFormatter JST = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Tokyo"));

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final CreditService creditService;
    private final RunRecordRepository runRecordRepository;
    private final ProjectRepository projectRepository;
    private final PlanRecordRepository planRecordRepository;
    private final WorkerClient workerClient;
    private final AuditService auditService;

    public MyPageController(
            UserRepository userRepository,
            OrganizationRepository organizationRepository,
            CreditService creditService,
            RunRecordRepository runRecordRepository,
            ProjectRepository projectRepository,
            PlanRecordRepository planRecordRepository,
            WorkerClient workerClient,
            AuditService auditService) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.creditService = creditService;
        this.runRecordRepository = runRecordRepository;
        this.projectRepository = projectRepository;
        this.planRecordRepository = planRecordRepository;
        this.workerClient = workerClient;
        this.auditService = auditService;
    }

    @GetMapping("/mypage")
    public String show(@AuthenticationPrincipal AppUserPrincipal user, Model model) {
        User account = userRepository.findById(user.getUserId())
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "アカウントが見つかりません"));
        Organization organization = organizationRepository.findById(user.getOrganizationId())
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "組織が見つかりません"));

        model.addAttribute("displayName", account.getDisplayName());
        model.addAttribute("organizationName", organization.getName());
        model.addAttribute("email", account.getEmail());

        model.addAttribute("creditBalance", creditService.getBalance(user.getOrganizationId()));
        model.addAttribute("creditMinBalanceToStart", creditService.getMinBalanceToStart());
        List<CreditLedgerEntry> creditHistory = creditService.history(user.getOrganizationId()).stream()
                .limit(RECENT_CREDIT_ENTRIES_LIMIT).toList();
        List<Map<String, Object>> creditRows = creditHistory.stream().map(this::creditRow).toList();
        model.addAttribute("creditEntries", creditRows);

        List<RunRecord> runRecords = runRecordRepository.findByOrganizationIdOrderByCreatedAtDesc(user.getOrganizationId());
        Map<Long, String> projectNames = projectRepository.findByOrganizationIdOrderByCreatedAtDesc(user.getOrganizationId())
                .stream().collect(java.util.stream.Collectors.toMap(Project::getId, Project::getName));
        List<Map<String, Object>> recentRuns = new ArrayList<>();
        for (RunRecord record : runRecords.stream().limit(RECENT_RUNS_LIMIT).toList()) {
            try {
                Map<String, Object> run = RunDisplay.resolve(workerClient, record);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("runId", record.getRunId());
                row.put("projectName", projectNames.getOrDefault(record.getProjectId(), "(不明)"));
                row.put("startedAtDisplay", DisplayFormat.jst(run.get("startedAt")));
                row.put("status", run.get("status"));
                row.put("costDisplay", DisplayFormat.creditsOf(run, creditService.getUsdToJpyRate(), creditService.getMarkupMultiplier()));
                recentRuns.add(row);
            } catch (RuntimeException e) {
                log.info("マイページ用のRun取得に失敗した(runId={}): {}", record.getRunId(), e.toString());
            }
        }
        model.addAttribute("recentRuns", recentRuns);

        List<Project> projects = projectRepository.findByOrganizationIdOrderByCreatedAtDesc(user.getOrganizationId());
        model.addAttribute("projects", projects);

        model.addAttribute("pendingPlans", pendingPlans(user.getOrganizationId(), projectNames));

        return "mypage";
    }

    /** 指揮官バグ報告(2026-09-22)対応: 下見・項目書生成は済んだが、まだ承認・実行していない
     * プラン(=RunRecordがまだ無いPlanRecord)を一覧できるようにする。残高不足等で実行を
     * 開始できなかった場合でも、ここから辿って(下見をやり直さずに)続きから承認・実行できる。 */
    private List<Map<String, Object>> pendingPlans(Long organizationId, Map<Long, String> projectNames) {
        List<PlanRecord> plans = planRecordRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PlanRecord plan : plans) {
            if (rows.size() >= PENDING_PLANS_LIMIT) {
                break;
            }
            if (runRecordRepository.findByPlanIdAndOrganizationId(plan.getPlanId(), organizationId).isPresent()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("planId", plan.getPlanId());
            row.put("projectName", projectNames.getOrDefault(plan.getProjectId(), "(不明)"));
            row.put("createdAtDisplay", JST.format(plan.getCreatedAt()));
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> creditRow(CreditLedgerEntry entry) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("createdAtDisplay", JST.format(entry.getCreatedAt()));
        row.put("kind", entry.getKind());
        row.put("amountCredits", entry.getAmountCredits());
        row.put("runId", entry.getRunId());
        row.put("detail", entry.getDetail());
        return row;
    }

    /** v0.8第5章: 名前・組織名の変更のみ(メール・パスワードは対象外)。 */
    @PostMapping("/mypage/account")
    public String updateAccount(
            @AuthenticationPrincipal AppUserPrincipal user,
            @RequestParam String displayName,
            @RequestParam String organizationName) {
        String trimmedName = displayName == null ? "" : displayName.trim();
        String trimmedOrgName = organizationName == null ? "" : organizationName.trim();
        if (trimmedName.isEmpty() || trimmedOrgName.isEmpty()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "名前・組織名は空にできません。");
        }
        User account = userRepository.findById(user.getUserId())
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "アカウントが見つかりません"));
        Organization organization = organizationRepository.findById(user.getOrganizationId())
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "組織が見つかりません"));
        account.setDisplayName(trimmedName);
        organization.setName(trimmedOrgName);
        userRepository.save(account);
        organizationRepository.save(organization);
        auditService.record(user.getOrganizationId(), user.getUserId(), "account_updated",
                "displayName=" + trimmedName + " organizationName=" + trimmedOrgName);
        return "redirect:/mypage";
    }
}
