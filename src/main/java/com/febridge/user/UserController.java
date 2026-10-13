package com.febridge.user;

import static com.febridge.user.UserDtos.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public List<UserView> list() {
        return users.list();
    }

    @PostMapping
    public UserView create(@Valid @RequestBody CreateUser input) {
        return users.create(input);
    }

    @PutMapping("/{id}")
    public UserView update(@PathVariable UUID id, @Valid @RequestBody UpdateUser input) {
        return users.update(AuthController.current().id(), id, input);
    }

    @PostMapping("/{id}/password")
    public Map<String, Object> reset(
        @PathVariable UUID id,
        @Valid @RequestBody PasswordReset input
    ) {
        users.resetPassword(AuthController.current().id(), id, input);
        return Map.of("changed", true);
    }
}
