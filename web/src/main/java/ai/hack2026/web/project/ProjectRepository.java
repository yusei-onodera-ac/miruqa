package ai.hack2026.web.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    // テナント分離(U-4): 参照は必ずorganizationIdと一緒に絞り込む。idだけで取得するメソッドは
    // 意図的に用意しない(他組織のIDを直接指定してもアクセスできないことを、型で担保する)。
    List<Project> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);
    Optional<Project> findByIdAndOrganizationId(Long id, Long organizationId);
}
