package ai.hack2026.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** {@code @EnableScheduling}: v0.6 P3のジョブキュー(JobQueueScheduler)がディスパッチ・
 * タイムアウト検知・使用量記録の定期実行に使う。 */
@SpringBootApplication
@EnableScheduling
public class MiruqaWebApplication {

	public static void main(String[] args) {
		SpringApplication.run(MiruqaWebApplication.class, args);
	}

}
