package ai.hack2026.web;

import ai.hack2026.web.execution.RunRecordRepository;
import ai.hack2026.web.worker.WorkerClient;
import ai.hack2026.web.worker.WorkerUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/** v0.6 P3(O-4): ヘルスチェックが、DB・ワーカーどちらの不調も報告できること。 */
class HealthControllerTest {

    private final RunRecordRepository runRecordRepository = Mockito.mock(RunRecordRepository.class);
    private final WorkerClient workerClient = Mockito.mock(WorkerClient.class);
    private final HealthController controller = new HealthController(runRecordRepository, workerClient);

    @Test
    void reportsOkWhenBothHealthy() {
        when(runRecordRepository.count()).thenReturn(3L);
        when(workerClient.health()).thenReturn(Map.of("status", "ok"));

        ResponseEntity<Map<String, Object>> response = controller.health();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("ok", response.getBody().get("status"));
    }

    @Test
    void reportsDegradedWhenWorkerIsDown() {
        when(runRecordRepository.count()).thenReturn(3L);
        when(workerClient.health()).thenThrow(new WorkerUnavailableException("down"));

        ResponseEntity<Map<String, Object>> response = controller.health();

        assertEquals(503, response.getStatusCode().value());
        assertEquals("degraded", response.getBody().get("status"));
        assertEquals("down", response.getBody().get("worker"));
        assertEquals("ok", response.getBody().get("db"));
    }
}
