package ai.hack2026.web;

import ai.hack2026.web.audit.AuditService;
import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.execution.RunRecordRepository;
import ai.hack2026.web.findings.FindingStatus;
import ai.hack2026.web.findings.FindingStatusRecord;
import ai.hack2026.web.findings.FindingStatusRecordRepository;
import ai.hack2026.web.project.Project;
import ai.hack2026.web.project.ProjectRepository;
import ai.hack2026.web.util.DisplayFormat;
import ai.hack2026.web.util.FindingFingerprint;
import ai.hack2026.web.util.PerspectiveLabels;
import ai.hack2026.web.worker.WorkerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 不具合一覧(v0.6 UIチェックポイント)。プロジェクト横断で、実行結果の指摘(Finding)を一覧表示する。
 * v0.7 P6中核で、状態管理(未対応/対応済み/対応しない)と抑制(既定では未対応のみを表示し、
 * 既に対応済み・対応しないと判断した指摘が再実行のたびに一覧を埋めないようにする)を実装した。
 * Finding自体はワーカー側run.jsonが正だが、状態はFinding.id(実行のたびに振り直される)に
 * 依存できないため、{@link FindingFingerprint}(観点・種別・仕様参照または詳細)で識別する。
 */
@Controller
public class FindingsController {

    private static final Logger log = LoggerFactory.getLogger(FindingsController.class);
    private static final int MAX_RUNS_SCANNED = 30;

    private final RunRecordRepository runRecordRepository;
    private final ProjectRepository projectRepository;
    private final WorkerClient workerClient;
    private final FindingStatusRecordRepository findingStatusRecordRepository;
    private final AuditService auditService;

    public FindingsController(
            RunRecordRepository runRecordRepository,
            ProjectRepository projectRepository,
            WorkerClient workerClient,
            FindingStatusRecordRepository findingStatusRecordRepository,
            AuditService auditService) {
        this.runRecordRepository = runRecordRepository;
        this.projectRepository = projectRepository;
        this.workerClient = workerClient;
        this.findingStatusRecordRepository = findingStatusRecordRepository;
        this.auditService = auditService;
    }

    @GetMapping("/findings")
    public String findings(
            @AuthenticationPrincipal AppUserPrincipal user,
            @RequestParam(value = "projectId", required = false) Long filterProjectId,
            @RequestParam(value = "severity", required = false) String filterSeverity,
            @RequestParam(value = "status", required = false, defaultValue = "UNADDRESSED") String filterStatus,
            Model model) {
        Long organizationId = user.getOrganizationId();
        Map<Long, String> projectNames = new HashMap<>();
        for (Project project : projectRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId)) {
            projectNames.put(project.getId(), project.getName());
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        boolean workerError = false;
        List<RunRecord> records = runRecordRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId);
        for (RunRecord record : records.stream().limit(MAX_RUNS_SCANNED).toList()) {
            if (filterProjectId != null && !filterProjectId.equals(record.getProjectId())) {
                continue;
            }
            try {
                Map<String, Object> run = ai.hack2026.web.util.RunDisplay.resolve(workerClient, record);
                Object findingsObj = run.get("findings");
                if (!(findingsObj instanceof List<?> findingsList)) {
                    continue;
                }
                for (Object f : findingsList) {
                    if (!(f instanceof Map<?, ?> rawFinding)) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> finding = (Map<String, Object>) rawFinding;
                    String severity = String.valueOf(finding.get("severity"));
                    if (filterSeverity != null && !filterSeverity.isBlank() && !filterSeverity.equals(severity)) {
                        continue;
                    }
                    String fingerprint = FindingFingerprint.compute(finding);
                    FindingStatus status = findingStatusRecordRepository
                            .findByOrganizationIdAndProjectIdAndFingerprint(organizationId, record.getProjectId(), fingerprint)
                            .map(r -> FindingStatus.fromValue(r.getStatus()))
                            .orElse(FindingStatus.UNADDRESSED);
                    // 「すべて」はALLという明示の値にする(""は空パラメータとしてSpringの
                    // defaultValueが働き、意図せずUNADDRESSEDに戻ってしまうため使わない)。
                    if (filterStatus != null && !"ALL".equals(filterStatus) && !filterStatus.equals(status.name())) {
                        continue;
                    }
                    Object perspective = finding.get("perspective");
                    Map<String, Object> row = new HashMap<>();
                    row.put("runId", record.getRunId());
                    row.put("projectId", record.getProjectId());
                    row.put("projectName", projectNames.getOrDefault(record.getProjectId(), "(削除済みプロジェクト)"));
                    row.put("detectedAtDisplay", DisplayFormat.jst(run.get("startedAt")));
                    row.put("detectedAtSort", DisplayFormat.instantOf(run.get("startedAt")));
                    row.put("perspective", PerspectiveLabels.withLabel(perspective));
                    row.put("severity", severity);
                    row.put("confidence", finding.get("confidence"));
                    row.put("title", finding.get("title"));
                    row.put("url", finding.get("url"));
                    row.put("fingerprint", fingerprint);
                    row.put("status", status.name());
                    row.put("statusLabel", status.getLabel());
                    rows.add(row);
                }
            } catch (RuntimeException e) {
                log.info("不具合一覧用のRun取得に失敗した(runId={}): {}", record.getRunId(), e.toString());
                workerError = true;
            }
        }

        rows.sort((a, b) -> ((java.time.Instant) b.get("detectedAtSort")).compareTo((java.time.Instant) a.get("detectedAtSort")));
        model.addAttribute("findings", rows);
        model.addAttribute("projects", projectRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId));
        model.addAttribute("filterProjectId", filterProjectId);
        model.addAttribute("filterSeverity", filterSeverity);
        model.addAttribute("filterStatus", filterStatus);
        model.addAttribute("statuses", FindingStatus.values());
        model.addAttribute("workerError", workerError);
        return "findings";
    }

    /** v0.7 P6中核: 不具合の状態を変更する(未対応/対応済み/対応しない)。fingerprintが一致する限り、
     * 別の実行で再検出されても状態を引き継ぐ(抑制)。projectIdはこの組織のものであることを確認する
     * (他組織のプロジェクトへは書き込めない。テナント分離)。 */
    @PostMapping("/findings/status")
    @ResponseBody
    public Map<String, Object> updateStatus(
            @AuthenticationPrincipal AppUserPrincipal user,
            @RequestParam("projectId") Long projectId,
            @RequestParam("fingerprint") String fingerprint,
            @RequestParam("status") String statusValue) {
        Long organizationId = user.getOrganizationId();
        projectRepository.findByIdAndOrganizationId(projectId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "プロジェクトが見つかりません"));
        FindingStatus status = FindingStatus.fromValue(statusValue);

        FindingStatusRecord record = findingStatusRecordRepository
                .findByOrganizationIdAndProjectIdAndFingerprint(organizationId, projectId, fingerprint)
                .orElseGet(FindingStatusRecord::new);
        record.setOrganizationId(organizationId);
        record.setProjectId(projectId);
        record.setFingerprint(fingerprint);
        record.setStatus(status.name());
        record.setUpdatedByUserId(user.getUserId());
        record.setUpdatedAt(Instant.now());
        findingStatusRecordRepository.save(record);

        auditService.record(organizationId, user.getUserId(), "finding_status_updated",
                "projectId=" + projectId + " fingerprint=" + fingerprint + " status=" + status.name());

        return Map.of("fingerprint", fingerprint, "status", status.name(), "statusLabel", status.getLabel());
    }
}
