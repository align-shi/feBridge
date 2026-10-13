package com.febridge.security;

import com.febridge.user.UserMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class AccountSessionFilter extends OncePerRequestFilter {

    private final UserMapper users;

    public AccountSessionFilter(UserMapper users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (
            authentication != null && authentication.getPrincipal() instanceof SessionUser session
        ) {
            var user = users.byId(session.id());
            if (
                user == null ||
                !user.enabled() ||
                user.sessionVersion() != session.version() ||
                user.role() != session.role()
            ) {
                SecurityContextHolder.clearContext();
                var httpSession = request.getSession(false);
                if (httpSession != null) {
                    httpSession.invalidate();
                }
                SecurityConfiguration.jsonError(response, 401, "登录状态已失效，请重新登录");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
