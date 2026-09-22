package ai.hack2026.web.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/** v0.6 P1: サインアップ・ログイン・CSRF・セッション管理。パスワードはBCrypt。
 * 【踏んだ不具合(実測)】Spring Security 7で、DaoAuthenticationProviderを明示的に登録しないと、
 * Spring Bootの自動設定(InitializeUserDetailsBeanManagerConfigurer)が独自のPasswordEncoderで
 * providerを組み立ててしまい、こちらのBCryptPasswordEncoder Beanと噛み合わず、常にログイン失敗
 * (/login?error)になることを確認した(サービス層単体では passwordEncoder.matches() が
 * trueを返すのに、実際の/loginフィルタチェーンでは失敗する、という形で発覚)。
 * DaoAuthenticationProviderを明示的にBean登録することで解消した。 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(AppUserDetailsService uds, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(uds);
        provider.setPasswordEncoder(encoder);
        return provider;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/signup", "/verify-email", "/forgot-password", "/reset-password",
                        "/style.css", "/favicon.ico", "/error", "/health", "/img/**").permitAll()
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/", true)
                .permitAll())
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .permitAll())
            // CSRFは既定で有効(第8章・U-5)。フォームはThymeleafの th:action がトークンを自動付与する
            .sessionManagement(session -> session
                .maximumSessions(5))
            // 指揮官バグ報告(2026-09-22)対応・根本原因: Spring Securityの既定のX-Frame-Optionsは
            // DENYで、run.htmlが自分自身の/runs/{id}/reportをiframeで埋め込む機能を常に(一時的な
            // 不調とは無関係に)ブロックしていた(実機で再現・確認)。他ドメインからの埋め込みは
            // 引き続き拒否しつつ、同一オリジンのiframeだけ許可する。
            .headers(headers -> headers
                .frameOptions(frame -> frame.sameOrigin()));
        return http.build();
    }
}
