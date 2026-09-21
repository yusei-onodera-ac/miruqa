package ai.hack2026.web.queue;

import ai.hack2026.web.execution.RunRecord;
import ai.hack2026.web.execution.RunRecordRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * v0.6 P3: ジョブキューの定期実行(ディスパッチ・タイムアウト検知)。
 * {@code PlanController.approve()}も、承認直後に一度だけ{@link JobQueueService#dispatchQueuedIfCapacity}
 * を呼ぶため、空きがある通常時はここを待たずに実行が始まる。このスケジューラは、
 * (1) 空きがない時にキューで待っていたジョブを、空きができ次第拾い上げる、
 * (2) 実行ごとのタイムアウトを検知する、の2つを担当する。
 */
@Component
@ConditionalOnProperty(prefix = "queue", name = "scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class JobQueueScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobQueueScheduler.class);

    private final RunRecordRepository runRecordRepository;
    private final JobQueueService jobQueueService;

    public JobQueueScheduler(RunRecordRepository runRecordRepository, JobQueueService jobQueueService) {
        this.runRecordRepository = runRecordRepository;
        this.jobQueueService = jobQueueService;
    }

    @PostConstruct
    void onStartup() {
        jobQueueService.resyncAllActiveOnStartup();
    }

    @Scheduled(fixedDelayString = "${queue.poll-interval-ms:3000}")
    public void tick() {
        List<RunRecord> active = runRecordRepository.findByStatusIn(
                List.of(RunRecord.STATUS_QUEUED, RunRecord.STATUS_RUNNING));
        Set<Long> organizationIds = active.stream().map(RunRecord::getOrganizationId).collect(Collectors.toSet());
        try {
            jobQueueService.checkTimeouts();
        } catch (RuntimeException e) {
            log.warn("[job-queue] タイムアウト検知中にエラー(処理は継続): {}", e.toString());
        }
        for (Long organizationId : organizationIds) {
            try {
                jobQueueService.dispatchQueuedIfCapacity(organizationId);
            } catch (RuntimeException e) {
                log.warn("[job-queue] ディスパッチ中にエラー(organizationId={}、処理は継続): {}", organizationId, e.toString());
            }
        }
    }
}
