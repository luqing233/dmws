package fun.luqing.dmws.web.fishing;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.web.server.servlet.CookieSameSiteSupplier;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
@RequiredArgsConstructor
public class FishingWebConfiguration implements WebMvcConfigurer {
    private final FishingWebAccess access;
    @Bean CookieSameSiteSupplier fishingReviewSameSite() { return CookieSameSiteSupplier.ofStrict().whenHasName("JSESSIONID"); }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(access).addPathPatterns("/api/fishing/**", "/", "/index.html", "/app.js", "/app.css",
                "/theme.js", "/theme.css", "/combat-tree.js", "/event-editor.js");
    }
    @Override public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }
}
