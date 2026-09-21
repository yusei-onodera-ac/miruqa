package ai.hack2026.web.logging;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcLoggingConfig implements WebMvcConfigurer {

    private final OrgMdcInterceptor orgMdcInterceptor;

    public WebMvcLoggingConfig(OrgMdcInterceptor orgMdcInterceptor) {
        this.orgMdcInterceptor = orgMdcInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(orgMdcInterceptor);
    }
}
