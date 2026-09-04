package com.smartquery.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.Result;
import com.smartquery.common.UserContextHolder;
import com.smartquery.entity.User;
import com.smartquery.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 鉴权拦截器：校验 Authorization: Bearer <token>，通过则把用户上下文写入 ThreadLocal。
 * 放行路径（login/register/swagger 等）在 WebConfig.addInterceptors 的 excludePathPatterns 中配置。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String header = request.getHeader("Authorization");
        String token = null;
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7).trim();
        } else if (allowsQueryToken(request.getRequestURI())) {
            // <img>/window.open 等浏览器资源请求无法设置 Authorization；仅为这类
            // 资源保留 query token 兼容。任务 SSE 已统一使用带请求头的 fetch 流。
            String queryToken = request.getParameter("token");
            if (queryToken != null && !queryToken.isBlank()) {
                token = queryToken.trim();
            }
        }
        if (token == null) {
            return writeUnauthorized(response, "未登录或缺少认证令牌");
        }
        try {
            Claims claims = jwtUtil.parse(token);
            Long userId = Long.valueOf(claims.getSubject());
            String username = claims.get("username", String.class);
            User user = userMapper.selectById(userId);
            if (user == null || user.getEnabled() == null || user.getEnabled() != 1) {
                return writeUnauthorized(response, "账号不存在或已被禁用");
            }
            UserContextHolder.set(new UserContextHolder.UserContext(userId, username, user.getRole()));
            return true;
        } catch (Exception e) {
            log.debug("[AUTH] token 校验失败: {}", e.getMessage());
            return writeUnauthorized(response, "登录已过期，请重新登录");
        }
    }

    private boolean allowsQueryToken(String uri) {
        return uri != null && (uri.startsWith("/artifacts/")
            || uri.startsWith("/api/v1/word-report/download/"));
    }

    private boolean writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(401, message)));
        return false;
    }
}
