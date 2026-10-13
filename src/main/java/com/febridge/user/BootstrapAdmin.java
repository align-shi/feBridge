package com.febridge.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "platform.auth.bootstrap-enabled", havingValue = "true")
public class BootstrapAdmin implements ApplicationRunner {

    private final UserService users;
    private final String username;
    private final String password;

    public BootstrapAdmin(
        UserService users,
        @Value(
            "${PLATFORM_ADMIN_USERNAME:${platform.auth.bootstrap-username:admin}}"
        ) String username,
        @Value("${PLATFORM_ADMIN_PASSWORD:${platform.auth.bootstrap-password:}}") String password
    ) {
        this.users = users;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        users.bootstrap(username, password);
    }
}
