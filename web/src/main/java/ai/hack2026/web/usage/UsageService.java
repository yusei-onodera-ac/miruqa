package ai.hack2026.web.usage;

import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.worker.WorkerApiException;
import ai.hack2026.web.worker.WorkerClient;
import ai.hack2026.web.worker.WorkerUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * v0.6 P3: 使用量の記録(B-2)と、組織単位のコスト上限の判定(B-3・O-3)。
 * 実測の根拠は、ワーカーの{@code GET /api/runs/{id}/cost}(runs/ledger.jsonlから集計、確定額優先)。
 * 台帳そのものはPython側にあるためJavaから直接は読めないが、この値をそのまま1回だけ記録することで
 * (run_idにUNIQUE制約)、突き合わせ・二重計上の防止の両方を担保する。
 */
@Service
public class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    private final UsageEventRepository usageEventRepository;
    private final WorkerClient workerClient;
    private final double orgDailyLimitUsd;
    private final double orgMonthlyLimitUsd;
    private final double warnThreshold;

    public UsageService(
            UsageEventRepository usageEventRepository,
            WorkerClient workerClient,
            @Value("${usage.org-daily-limit-usd:3.0}") double orgDailyLimitUsd,
            @Value("${usage.org-monthly-limit-usd:30.0}") double orgMonthlyLimitUsd,
            @Value("${usage.warn-threshold:0.8}") double warnThreshold) {
        this.usageEventRepository = usageEventRepository;
        this.workerClient = workerClient;
        this.orgDailyLimitUsd = orgDailyLimitUsd;
        this.orgMonthlyLimitUsd = orgMonthlyLimitUsd;
        this.warnThreshold = warnThreshold;
    }

    /** 終了した実行の使用量を、まだ記録していなければ1回だけ記録する(冪等)。 */
    public void recordIfAbsent(RunRecord record) {
        if (record.getWorkerRunId() == null || usageEventRepository.findByRunId(record.getRunId()).isPresent()) {
            return;
        }
        double cost = 0.0;
        Integer callCount = null;
        try {
            Map<String, Object> breakdown = workerClient.getRunCost(record.getWorkerRunId());
            Object settled = breakdown.get("totalCostUsdSettled");
            Object inline = breakdown.get("totalCostUsdInline");
            cost = toDouble(settled) > 0 ? toDouble(settled) : toDouble(inline);
            Object count = breakdown.get("callCount");
            callCount = count instanceof Number n ? n.intValue() : null;
        } catch (WorkerApiException | WorkerUnavailableException e) {
            log.warn("使用量記録用のコスト取得に失敗した(runId={}): {}", record.getRunId(), e.toString());
        }
        UsageEvent event = new UsageEvent();
        event.setOrganizationId(record.getOrganizationId());
        event.setProjectId(record.getProjectId());
        event.setRunId(record.getRunId());
        event.setCostUsd(cost);
        event.setCallCount(callCount);
        event.setOutcomeStatus(record.getStatus());
        try {
            usageEventRepository.save(event);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 同時に2箇所(ポーリングとスケジューラ)から呼ばれても、UNIQUE制約により2重には残らない。
            log.info("使用量は既に記録済みだった(runId={})", record.getRunId());
        }
    }

    private static double toDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    public double orgCostToday(Long organizationId) {
        Instant startOfDay = ZonedDateTime.now(JST).toLocalDate().atStartOfDay(JST).toInstant();
        return sumSince(organizationId, startOfDay);
    }

    public double orgCostThisMonth(Long organizationId) {
        Instant startOfMonth = ZonedDateTime.now(JST).toLocalDate().withDayOfMonth(1).atStartOfDay(JST).toInstant();
        return sumSince(organizationId, startOfMonth);
    }

    private double sumSince(Long organizationId, Instant since) {
        List<UsageEvent> events = usageEventRepository.findByOrganizationIdAndRecordedAtAfter(organizationId, since);
        return events.stream().mapToDouble(UsageEvent::getCostUsd).sum();
    }

    /** B-3: 上限に達していれば、拒否理由を返す(null=上限内)。 */
    public String costCapRejectionReason(Long organizationId) {
        double today = orgCostToday(organizationId);
        double month = orgCostThisMonth(organizationId);
        if (today >= orgDailyLimitUsd) {
            return String.format(
                    "本日のコスト上限(%.2f USD)に達したため、新しい診断を開始できません(本日の使用量: %.4f USD)。日をまたぐか、上限の見直しをお問い合わせください。",
                    orgDailyLimitUsd, today);
        }
        if (month >= orgMonthlyLimitUsd) {
            return String.format(
                    "今月のコスト上限(%.2f USD)に達したため、新しい診断を開始できません(今月の使用量: %.4f USD)。月が変わるか、上限の見直しをお問い合わせください。",
                    orgMonthlyLimitUsd, month);
        }
        return null;
    }

    /** 80%警告(画面表示用。ブロックはしない)。 */
    public boolean isNearDailyLimit(Long organizationId) {
        return orgCostToday(organizationId) >= orgDailyLimitUsd * warnThreshold;
    }

    public boolean isNearMonthlyLimit(Long organizationId) {
        return orgCostThisMonth(organizationId) >= orgMonthlyLimitUsd * warnThreshold;
    }

    public double getOrgDailyLimitUsd() { return orgDailyLimitUsd; }
    public double getOrgMonthlyLimitUsd() { return orgMonthlyLimitUsd; }
}
