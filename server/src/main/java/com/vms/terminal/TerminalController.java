package com.vms.terminal;

import com.vms.identity.auth.AuthService;
import com.vms.identity.security.Permission;
import com.vms.identity.security.RequiresPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
@RequestMapping("/api/v1/terminals")
public class TerminalController {

    private final TerminalService terminals;
    private final AuthService auth;

    public TerminalController(TerminalService terminals, AuthService auth) {
        this.terminals = terminals;
        this.auth = auth;
    }

    @GetMapping
    @RequiresPermission(Permission.FLEET_VIEW)
    public List<TerminalService.TerminalView> list() {
        return terminals.list(auth.currentUser());
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permission.FLEET_VIEW)
    public TerminalService.TerminalView get(@PathVariable long id) {
        return terminals.get(auth.currentUser(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.FLEET_MANAGE)
    public TerminalService.TerminalView create(@Valid @RequestBody RegisterRequest request) {
        return terminals.create(auth.currentUser(), request.terminalNo());
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.FLEET_MANAGE)
    public TerminalService.TerminalView update(@PathVariable long id, @Valid @RequestBody StatusRequest request) {
        return terminals.update(auth.currentUser(), id, request.status());
    }

    public record RegisterRequest(@NotBlank @Pattern(regexp = "[0-9]{20}") String terminalNo) { }
    public record StatusRequest(@NotBlank @Pattern(regexp = "ENABLED|DISABLED") String status) { }
}
