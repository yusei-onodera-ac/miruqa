package ai.hack2026.web.logging;

import ai.hack2026.web.auth.AppUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * v0.6 P3(O-4): 構造化ログ。ログイン中のユーザーのorganizationId・userIdをMDCに入れ、
 * ログの各行に付くようにする(application.propertiesのlogging.patternで%X{orgId}等を出す)。
 * runIdは、実行に紐づく処理(JobQueueService等)側で個別にMDCへ積む。
 */
@Component
public class OrgMdcInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal principal) {
            MDC.put("orgId", String.valueOf(principal.getOrganizationId()));
            MDC.put("userId", String.valueOf(principal.getUserId()));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        MDC.remove("orgId");
        MDC.remove("userId");
    }
}
