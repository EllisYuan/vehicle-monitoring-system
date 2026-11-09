package com.vms.identity.account;

import com.vms.identity.auth.AuthService;
import com.vms.identity.security.Permission;
import com.vms.identity.security.RequiresPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiresPermission(Permission.ACCOUNT_MANAGE)
public class AccountAdminController {

    private final AccountAdminService accountAdminService;
    private final AuthService authService;

    public AccountAdminController(AccountAdminService accountAdminService, AuthService authService) {
        this.accountAdminService = accountAdminService;
        this.authService = authService;
    }

    @GetMapping
    public List<AccountAdminService.AccountView> list() {
        return accountAdminService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountAdminService.AccountView create(@Valid @RequestBody CreateAccountRequest request) {
        return accountAdminService.create(authService.currentUser(), request.username(),
                request.password(), request.roleCode(), request.fleetIds());
    }

    @PutMapping("/{id}/status")
    public AccountAdminService.AccountView updateStatus(@PathVariable long id,
                                                        @Valid @RequestBody UpdateStatusRequest request) {
        return accountAdminService.updateStatus(authService.currentUser(), id, request.status());
    }

    @PostMapping("/{id}/password-reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable long id, @Valid @RequestBody ResetPasswordRequest request) {
        accountAdminService.resetPassword(authService.currentUser(), id, request.newPassword());
    }

    @PutMapping("/{id}/fleets")
    public AccountAdminService.AccountView assignFleets(@PathVariable long id,
                                                        @Valid @RequestBody AssignFleetsRequest request) {
        return accountAdminService.assignFleets(authService.currentUser(), id, request.fleetIds());
    }

    public record CreateAccountRequest(
            @NotBlank @Size(min = 4, max = 64) @Pattern(regexp = "^[A-Za-z0-9_.-]+$") String username,
            @NotBlank @Size(min = 8, max = 200) String password,
            @NotBlank String roleCode,
            List<Long> fleetIds) {
    }

    public record UpdateStatusRequest(@NotBlank String status) {
    }

    public record ResetPasswordRequest(@NotBlank @Size(min = 8, max = 200) String newPassword) {
    }

    public record AssignFleetsRequest(List<Long> fleetIds) {
    }
}
