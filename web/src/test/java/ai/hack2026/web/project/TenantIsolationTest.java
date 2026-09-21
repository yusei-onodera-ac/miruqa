package ai.hack2026.web.project;

import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.auth.Membership;
import ai.hack2026.web.auth.MembershipRepository;
import ai.hack2026.web.auth.Role;
import ai.hack2026.web.auth.SignupService;
import ai.hack2026.web.auth.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AC-U1: 他組織のプロジェクトIDを直接指定しても、アクセスできない(404)ことを確認する。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantIsolationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SignupService signupService;
    @Autowired
    private MembershipRepository membershipRepository;
    @Autowired
    private ProjectRepository projectRepository;

    private AppUserPrincipal principalFor(User user) {
        Membership membership = membershipRepository.findByUserId(user.getId()).get(0);
        return new AppUserPrincipal(user.getId(), user.getEmail(), user.getPasswordHash(),
                membership.getOrganization().getId(), Role.OWNER, null);
    }

    @Test
    void otherOrgProjectIdIsNotAccessible() throws Exception {
        User ownerA = signupService.signUp("tenant-a@example.com", "password123", "組織A担当", "組織A");
        User ownerB = signupService.signUp("tenant-b@example.com", "password123", "組織B担当", "組織B");
        AppUserPrincipal principalA = principalFor(ownerA);
        AppUserPrincipal principalB = principalFor(ownerB);

        Project projectA = new Project();
        projectA.setOrganizationId(principalA.getOrganizationId());
        projectA.setName("組織Aのプロジェクト");
        projectA.setTargetUrl("http://127.0.0.1:8765/");
        projectA = projectRepository.save(projectA);

        // 組織Bのユーザーとして、組織Aのプロジェクト(連番ID)へ直接アクセス -> 404であること
        mockMvc.perform(get("/projects/{id}", projectA.getId())
                        .with(SecurityMockMvcRequestPostProcessors.user(principalB)))
                .andExpect(status().isNotFound());

        // 本人(組織A)からは通常通りアクセスできることも確認(過剰な遮断になっていないか)
        mockMvc.perform(get("/projects/{id}", projectA.getId())
                        .with(SecurityMockMvcRequestPostProcessors.user(principalA)))
                .andExpect(status().isOk());
    }

    @Test
    void loginPageIsPubliclyAccessibleButProjectsRequireAuth() throws Exception {
        mockMvc.perform(get("/login")).andExpect(status().isOk());
        mockMvc.perform(get("/projects")).andExpect(status().is3xxRedirection());
    }
}
