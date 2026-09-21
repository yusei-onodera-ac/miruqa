package ai.hack2026.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** v0.6 P3: 明示的にtestプロファイルを使う(インメモリDB)。指定が無いと既定のapplication.properties
 * (実データの./data/app.mv.db)に接続してしまい、テスト中にJobQueueSchedulerの定期実行が
 * 実データへ触れてしまう不備を発見・是正した(開発時の報告事項)。 */
@SpringBootTest
@ActiveProfiles("test")
class MiruqaWebApplicationTests {

	@Test
	void contextLoads() {
	}

}
