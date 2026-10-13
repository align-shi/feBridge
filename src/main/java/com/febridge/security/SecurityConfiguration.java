package com.febridge.security;

import com.febridge.user.UserMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
public class SecurityConfiguration {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    public HttpSessionSecurityContextRepository contextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public HttpSessionCsrfTokenRepository csrfRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    public UserDetailsService userDetailsService(UserMapper mapper) {
        return username -> {
            var user = mapper.byUsername(username);
            if (user == null) {
                throw new UsernameNotFoundException("账号不存在");
            }
            return User.withUsername(user.username())
                .password(user.passwordHash())
                .roles(user.role().name())
                .disabled(!user.enabled())
                .build();
        };
    }

    @Bean
    public SecurityFilterChain security(
        HttpSecurity http,
        UserMapper users,
        HttpSessionSecurityContextRepository context,
        HttpSessionCsrfTokenRepository csrf
    ) throws Exception {
        http.securityContext(config -> config.securityContextRepository(context));
        http.csrf(config ->
            config
                .csrfTokenRepository(csrf)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
        );
        http.formLogin(config -> config.disable());
        http.httpBasic(config -> config.disable());
        http.requestCache(config -> config.disable());
        http.authorizeHttpRequests(config ->
            config
                .requestMatchers("/error", "/actuator/health", "/actuator/health/**")
                .permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/csrf")
                .permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login")
                .permitAll()
                .requestMatchers("/api/users", "/api/users/**")
                .hasRole("ADMIN")
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/model-providers",
                    "/api/model-providers/**",
                    "/api/models",
                    "/api/models/**",
                    "/api/mcp",
                    "/api/mcp/**"
                )
                .authenticated()
                .requestMatchers(
                    "/api/model-providers",
                    "/api/model-providers/**",
                    "/api/models",
                    "/api/models/**",
                    "/api/mcp",
                    "/api/mcp/**"
                )
                .hasRole("ADMIN")
                .requestMatchers("/api/**")
                .authenticated()
                .anyRequest()
                .denyAll()
        );
        http.exceptionHandling(config ->
            config
                .authenticationEntryPoint((request, response, exception) ->
                    jsonError(response, 401, "请先登录")
                )
                .accessDeniedHandler((request, response, exception) ->
                    jsonError(response, 403, "无权执行此操作，或页面验证信息已过期，请刷新重试")
                )
        );
        http.logout(config ->
            config
                .logoutUrl("/api/auth/logout")
                .deleteCookies("FEBRIDGE_SESSION")
                .logoutSuccessHandler((request, response, authentication) -> {
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{}");
                })
        );
        http.addFilterAfter(new AccountSessionFilter(users), SecurityContextHolderFilter.class);
        return http.build();
    }

    public static void jsonError(HttpServletResponse response, int status, String detail)
        throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"detail\":\"" + detail + "\"}");
    }
}
