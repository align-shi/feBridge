package com.febridge.user;

import static com.febridge.user.UserDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

class UserServiceTest {

    @Test
    void protectsLastEnabledAdministrator() {
        var mapper = mock(UserMapper.class);
        var passwords = mock(PasswordEncoder.class);
        var service = new UserService(mapper, passwords);
        UUID id = UUID.randomUUID();
        when(mapper.byId(id)).thenReturn(
            new UserRecord(
                id,
                "admin",
                "管理员",
                "hash",
                Role.ADMIN,
                true,
                0,
                0,
                false,
                "2026-10-13T00:00:00Z"
            )
        );
        when(mapper.enabledAdmins()).thenReturn(1);
        assertThrows(ResponseStatusException.class, () ->
            service.update(UUID.randomUUID(), id, new UpdateUser("管理员", Role.USER, true))
        );
        verify(mapper, never()).update(any(), any(), any(), anyBoolean());
    }

    @Test
    void bootstrapDoesNotResetExistingCredentials() {
        var mapper = mock(UserMapper.class);
        var service = new UserService(mapper, mock(PasswordEncoder.class));
        when(mapper.count()).thenReturn(1);
        service.bootstrap("admin", "Different-password");
        verify(mapper, never()).insert(any(), any(), any(), any(), any());
    }
}
