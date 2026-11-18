package fun.luqing.dmws.web.fishing;

import fun.luqing.dmws.common.security.FishingWebPassword;
import fun.luqing.dmws.config.FishingWebProperties;
import fun.luqing.dmws.service.dmw.economy.FishReviewException;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Component
@RequiredArgsConstructor
public class FishingWebAccess implements HandlerInterceptor {
    private static final String USER = "fishing.review.user", CSRF = "fishing.review.csrf";
    private final FishingWebProperties properties;
    private final Map<String, Bucket> limits = new HashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'; form-action 'self'");
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/fishing/")) {
            response.setHeader("Cache-Control", "no-store");
            if (!properties.isEnabled()) throw new FishReviewException("DISABLED", "鱼种投稿暂未开放。", 404);
            if (request.getContentLengthLong() > 1048576) throw new FishReviewException("TOO_LARGE", "提交内容过长。", 413);
            if (!request.getMethod().equals("GET") && !request.getMethod().equals("HEAD")) requireSameOrigin(request);
            if (path.equals("/api/fishing/preview") || path.equals("/api/fishing/event-preview")) rateLimit("preview:" + request.getRemoteAddr(), 120, 60000);
            if (path.startsWith("/api/fishing/admin/") && !path.endsWith("/login") && !path.endsWith("/session")) {
                user(request);
                if (!request.getMethod().equals("GET")) {
                    String expected = (String) request.getSession(false).getAttribute(CSRF);
                    String actual = request.getHeader("X-CSRF-Token");
                    if (actual == null || expected == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8)))
                        throw new FishReviewException("CSRF", "页面验证已失效，请刷新后重试。", 403);
                }
            }
        }
        return true;
    }

    public Map<String, Object> session(HttpServletRequest request) {
        var session = request.getSession(false);
        boolean authenticated = session != null && session.getAttribute(USER) instanceof String;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authenticated", authenticated);
        result.put("loginEnabled", FishingWebPassword.configured(properties.getAdminPasswordHash()));
        if (authenticated) { result.put("username", session.getAttribute(USER)); result.put("csrfToken", session.getAttribute(CSRF)); }
        return result;
    }

    public Map<String, Object> login(HttpServletRequest request, String username, String password) {
        rateLimit("login:" + request.getRemoteAddr(), 8, 60000);
        if (!FishingWebPassword.configured(properties.getAdminPasswordHash()))
            throw new FishReviewException("LOGIN_DISABLED", "审核登录尚未配置，请联系站点管理员。", 503);
        boolean valid = FishingWebPassword.matches(password, properties.getAdminPasswordHash());
        if (username == null || !MessageDigest.isEqual(username.getBytes(StandardCharsets.UTF_8), properties.getAdminUsername().getBytes(StandardCharsets.UTF_8)) || !valid)
            throw new FishReviewException("LOGIN_FAILED", "账号或密码不正确。", 401);
        var previous = request.getSession(false); if (previous != null) previous.invalidate();
        var session = request.getSession(true);
        session.setMaxInactiveInterval(Math.clamp(properties.getSessionMinutes(), 5, 120) * 60);
        session.setAttribute(USER, properties.getAdminUsername());
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        session.setAttribute(CSRF, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        return session(request);
    }

    public String user(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute(USER) instanceof String)) throw new FishReviewException("LOGIN_REQUIRED", "请先登录审核账号。", 401);
        return (String) session.getAttribute(USER);
    }
    public void logout(HttpServletRequest request) { var session = request.getSession(false); if (session != null) session.invalidate(); }
    public void submissionLimit(HttpServletRequest request) { rateLimit("submit:" + request.getRemoteAddr(), 10, 60000); }

    private synchronized void rateLimit(String key, int maximum, long duration) {
        long now = System.currentTimeMillis();
        if (limits.size() > 10000) limits.entrySet().removeIf(entry -> entry.getValue().until < now);
        if (!limits.containsKey(key) && limits.size() > 10000) throw new FishReviewException("BUSY", "访问较多，请稍后再试。", 429);
        var bucket = limits.get(key);
        if (bucket == null || bucket.until <= now) { bucket = new Bucket(now + duration); limits.put(key, bucket); }
        if (++bucket.count > maximum) throw new FishReviewException("RATE_LIMITED", "操作过于频繁，请一分钟后再试。", 429);
    }

    private void requireSameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null) return;
        try {
            URI uri = URI.create(origin);
            if (!(uri.getScheme().equals("https") || uri.getScheme().equals("http")) || uri.getUserInfo() != null
                    || !uri.getRawAuthority().equalsIgnoreCase(request.getHeader("Host"))) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new FishReviewException("ORIGIN", "请从本站页面提交。", 403); }
    }
    private static final class Bucket { final long until; int count; Bucket(long until) { this.until = until; } }
}
