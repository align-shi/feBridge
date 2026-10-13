package com.febridge.user;

import static com.febridge.user.UserDtos.*;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService {

    private final UserMapper mapper;
    private final PasswordEncoder passwords;
    private final String dummyHash;

    public UserService(UserMapper mapper, PasswordEncoder passwords) {
        this.mapper = mapper;
        this.passwords = passwords;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public UserRecord find(UUID id) {
        var user = mapper.byId(id);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    public List<UserView> list() {
        return mapper.list().stream().map(UserRecord::view).toList();
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public UserRecord authenticate(Login input) {
        var user = mapper.lockByUsername(normalize(input.username()));
        String hash = user == null ? dummyHash : user.passwordHash();
        boolean matched = passwords.matches(input.password(), hash);
        if (user == null || !user.enabled() || user.locked()) {
            throw invalidLogin();
        }
        if (!matched) {
            mapper.failedLogin(user.id());
            throw invalidLogin();
        }
        mapper.successfulLogin(user.id());
        return user;
    }

    private ResponseStatusException invalidLogin() {
        return new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "账号或密码错误，或账号暂不可用；连续错误可稍后重试"
        );
    }

    @Transactional
    public UserView create(CreateUser input) {
        mapper.lockChanges();
        String username = normalize(input.username());
        if (mapper.byUsername(username) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "账号已存在");
        }
        UUID id = UUID.randomUUID();
        try {
            mapper.insert(
                id,
                username,
                input.displayName().trim(),
                passwords.encode(input.password()),
                input.role().name()
            );
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "账号已存在");
        }
        return find(id).view();
    }

    @Transactional
    public UserView update(UUID actorId, UUID id, UpdateUser input) {
        mapper.lockChanges();
        var old = find(id);
        if (actorId.equals(id) && (!input.enabled() || input.role() != Role.ADMIN)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "不能禁用自己或取消自己的管理员角色"
            );
        }
        if (
            old.enabled() &&
            old.role() == Role.ADMIN &&
            (!input.enabled() || input.role() != Role.ADMIN) &&
            mapper.enabledAdmins() <= 1
        ) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "必须保留至少一个启用的管理员"
            );
        }
        // Profile-only edits preserve sessions; privilege changes invalidate them.
        if (
            old.role() == input.role() &&
            old.enabled() == input.enabled() &&
            old.displayName().equals(input.displayName().trim())
        ) {
            return old.view();
        }
        mapper.update(id, input.displayName().trim(), input.role().name(), input.enabled());
        return find(id).view();
    }

    @Transactional
    public void changePassword(UUID id, PasswordChange input) {
        mapper.lockChanges();
        var user = find(id);
        if (!passwords.matches(input.currentPassword(), user.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前密码不正确");
        }
        if (input.currentPassword().equals(input.newPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码不能与当前密码相同");
        }
        mapper.password(id, passwords.encode(input.newPassword()));
    }

    @Transactional
    public void resetPassword(UUID actorId, UUID id, PasswordReset input) {
        if (actorId.equals(id)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "请通过修改密码功能修改自己的密码"
            );
        }
        mapper.lockChanges();
        find(id);
        mapper.password(id, passwords.encode(input.password()));
    }

    @Transactional
    public void bootstrap(String username, String password) {
        mapper.lockChanges();
        if (mapper.count() != 0) {
            return;
        }
        if (password == null || password.length() < 10 || password.length() > 128) {
            throw new IllegalStateException(
                "首次启动需配置 PLATFORM_ADMIN_PASSWORD（10 至 128 字符）"
            );
        }
        if (!username.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{2,31}")) {
            throw new IllegalStateException("初始管理员账号格式不正确");
        }
        mapper.insert(
            UUID.randomUUID(),
            normalize(username),
            "平台管理员",
            passwords.encode(password),
            Role.ADMIN.name()
        );
    }

    private String normalize(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
