package com.febridge.security;

import com.febridge.user.UserDtos.Role;
import com.febridge.user.UserDtos.UserRecord;
import java.io.Serializable;
import java.util.UUID;

public record SessionUser(
    UUID id,
    String username,
    Role role,
    long version
) implements Serializable {
    public static SessionUser from(UserRecord user) {
        return new SessionUser(user.id(), user.username(), user.role(), user.sessionVersion());
    }
}
