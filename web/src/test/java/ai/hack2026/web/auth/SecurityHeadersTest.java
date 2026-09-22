package ai.hack2026.web.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 指揮官バグ報告(2026-09-22)対応: run.htmlが自分自身の/runs/{id}/reportをiframeで
 * 埋め込む機能が、Spring Securityの既定のX-Frame-Options(DENY)によって常にブロックされて
 * いた(実機のPlaywrightで再現し、ブラウザの素のエラー画面が出ることを確認した)。
 * 同一オリジンのiframeは許可(SAMEORIGIN)しつつ、他ドメインからの埋め込みは
 * 引き続き拒否されることを確認する。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityHeadersTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SignupService signupService;
    @Autowired
    private MembershipRepository membershipRepository;

    @Test
    void frameOptionsAllowsSameOriginNotDeny() throws Exception {
        User owner = signupService.signUp("frame-check@example.com", "password123", "担当者", "組織");
        Membership membership = membershipRepository.findByUserId(owner.getId()).get(0);
        AppUserPrincipal principal = new AppUserPrincipal(owner.getId(), owner.getEmail(), owner.getPasswordHash(),
                membership.getOrganization().getId(), Role.OWNER, null);

        mockMvc.perform(get("/dashboard").with(SecurityMockMvcRequestPostProcessors.user(principal)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"));
    }

    /** 指揮官依頼(2026-09-22): ログイン・サインアップ画面にもヘッダーと同じロゴ画像を表示する
     * ようにしたところ、/img/**がpermitAllに入っておらず、未ログインの状態では
     * ロゴが読み込めない(302でログインへリダイレクトされる)ことに気づいて追加した。
     * ログイン前の画面(login/signup)で使う静的画像は、認証なしで取得できる必要がある。 */
    @Test
    void logoImageIsAccessibleWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/img/logo.png"))
                .andExpect(status().isOk());
    }
}
