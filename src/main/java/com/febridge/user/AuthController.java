package com.febridge.user;

import static com.febridge.user.UserDtos.*;

import com.febridge.security.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService users;
    private final HttpSessionSecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository csrf;

    public AuthController(
        UserService users,
        HttpSessionSecurityContextRepository contexts,
        HttpSessionCsrfTokenRepository csrf
    ) {
        this.users = users;
        this.contexts = contexts;
        this.csrf = csrf;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @PostMapping("/login")
    public UserView login(
        @Valid @RequestBody Login input,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        var user = users.authenticate(input);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
            SessionUser.from(user),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()))
        );
        new ChangeSessionIdAuthenticationStrategy().onAuthentication(
            authentication,
            request,
            response
        );
        new CsrfAuthenticationStrategy(csrf).onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return user.view();
    }

    @GetMapping("/me")
    public UserView me() {
        return users.find(current().id()).view();
    }

    @PostMapping("/password")
    public Map<String, Object> password(
        @Valid @RequestBody PasswordChange input,
        HttpServletRequest request
    ) {
        users.changePassword(current().id(), input);
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return Map.of("changed", true);
    }

    public static SessionUser current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof SessionUser user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return user;
    }
}
