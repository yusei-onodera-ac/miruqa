package ai.hack2026.web;

import ai.hack2026.web.execution.RunRecordRepository;
import ai.hack2026.web.worker.WorkerClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v0.6 P3(O-4): ヘルスチェック。DBとワーカーの両方の状態を返す(認証不要。監視ツールから
 * 定期的に叩かれる想定)。どちらかが不調でも、この呼び出し自体は例外を投げずJSONで報告する。
 */
@RestController
public class HealthController {

    private final RunRecordRepository runRecordRepository;
    private final WorkerClient workerClient;

    public HealthController(RunRecordRepository runRecordRepository, WorkerClient workerClient) {
        this.runRecordRepository = runRecordRepository;
        this.workerClient = workerClient;
    }

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean dbOk = checkDb();
        boolean workerOk = checkWorker();
        body.put("status", dbOk && workerOk ? "ok" : "degraded");
        body.put("db", dbOk ? "ok" : "error");
        body.put("worker", workerOk ? "ok" : "down");
        return dbOk && workerOk ? ResponseEntity.ok(body) : ResponseEntity.status(503).body(body);
    }

    private boolean checkDb() {
        try {
            runRecordRepository.count();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean checkWorker() {
        try {
            workerClient.health();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
