package com.febridge.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class UserDtos {

    private UserDtos() {}

    public enum Role {
        ADMIN,
        USER,
    }

    public record UserView(
        UUID id,
        String username,
        String displayName,
        Role role,
        boolean enabled,
        String createdAt
    ) {}

    public record Login(
        @NotBlank @Size(max = 32) String username,
        @NotBlank @Size(max = 128) String password
    ) {}

    public record CreateUser(
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9_.-]{2,31}") String username,
        @NotBlank @Size(max = 80) String displayName,
        @NotBlank @Size(min = 10, max = 128) String password,
        @NotNull Role role
    ) {}

    public record UpdateUser(
        @NotBlank @Size(max = 80) String displayName,
        @NotNull Role role,
        boolean enabled
    ) {}

    public record PasswordChange(
        @NotBlank @Size(max = 128) String currentPassword,
        @NotBlank @Size(min = 10, max = 128) String newPassword
    ) {}

    public record PasswordReset(@NotBlank @Size(min = 10, max = 128) String password) {}

    public record UserRecord(
        UUID id,
        String username,
        String displayName,
        String passwordHash,
        Role role,
        boolean enabled,
        long sessionVersion,
        int failedAttempts,
        boolean locked,
        String createdAt
    ) {
        public UserView view() {
            return new UserView(id, username, displayName, role, enabled, createdAt);
        }
    }
}
