package com.vms.command;

import com.vms.identity.account.UserAccount;
import com.vms.identity.auth.AuthService;
import com.vms.identity.security.ForbiddenException;
import com.vms.identity.security.Permission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/commands")
public class CommandController {

    private final CommandService commands;
    private final AuthService auth;

    public CommandController(CommandService commands, AuthService auth) {
        this.commands = commands;
        this.auth = auth;
    }

    @PostMapping("/text")
    @ResponseStatus(HttpStatus.CREATED)
    public CommandService.CommandView submitText(@Valid @RequestBody TextRequest request) {
        UserAccount actor = auth.currentUser();
        requireDispatch(actor, request.terminalId());
        return commands.submitText(actor, request.terminalId(), request.text(), request.textType());
    }

    @PostMapping("/tracking")
    @ResponseStatus(HttpStatus.CREATED)
    public CommandService.CommandView submitTracking(@Valid @RequestBody TrackingRequest request) {
        UserAccount actor = auth.currentUser();
        requireDispatch(actor, request.terminalId());
        return commands.submitTracking(actor, request.terminalId(), request.intervalSeconds(),
                request.validSeconds());
    }

    @PostMapping("/{id}/retry")
    @ResponseStatus(HttpStatus.CREATED)
    public CommandService.CommandView retry(@PathVariable long id) {
        UserAccount actor = auth.currentUser();
        requireDispatch(actor, null);
        return commands.retry(actor, id);
    }

    /** 指令记录读取：具备下发权限或车队查看权限（车队长只读）即可，数据范围由 FleetScope 限定。 */
    @GetMapping
    public List<CommandService.CommandView> list(@RequestParam(required = false) Long terminalId) {
        UserAccount actor = auth.currentUser();
        requireRead(actor);
        return commands.list(actor, terminalId);
    }

    @GetMapping("/{id}")
    public CommandService.CommandView get(@PathVariable long id) {
        UserAccount actor = auth.currentUser();
        requireRead(actor);
        return commands.get(actor, id);
    }

    /**
     * 下发类接口由控制器显式检查权限：需要在拒绝时记录被指向的终端，形成可追溯的 DENIED 审计。
     * 因此不使用只能表达单一权限的 {@link com.vms.identity.security.RequiresPermission} 注解。
     */
    private void requireDispatch(UserAccount actor, Long terminalId) {
        try {
            auth.requirePermission(actor, Permission.COMMAND_DISPATCH);
        } catch (ForbiddenException e) {
            if (terminalId != null) {
                commands.auditDenied(actor, terminalId);
            }
            throw e;
        }
    }

    /** 读取指令记录不要求下发权限：COMMAND_DISPATCH 或 FLEET_VIEW 任一即可。 */
    private void requireRead(UserAccount actor) {
        if (!auth.hasPermission(actor, Permission.COMMAND_DISPATCH)
                && !auth.hasPermission(actor, Permission.FLEET_VIEW)) {
            throw new ForbiddenException();
        }
    }

    public record TextRequest(@NotNull @Positive Long terminalId, String text, Integer textType) { }

    public record TrackingRequest(@NotNull @Positive Long terminalId, Integer intervalSeconds,
                                  Long validSeconds) { }
}
