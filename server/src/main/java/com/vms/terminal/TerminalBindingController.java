package com.vms.terminal;

import com.vms.identity.auth.AuthService;
import com.vms.fleet.VehicleService;
import com.vms.identity.security.Permission;
import com.vms.identity.security.RequiresPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 车辆与终端绑定归 terminal 模块所有；路径保持在车辆资源下，便于页面按车辆操作。
 */
@RestController
@RequestMapping("/api/v1/vehicles/{vehicleId}")
public class TerminalBindingController {

    private final TerminalService terminals;
    private final AuthService auth;

    public TerminalBindingController(TerminalService terminals, AuthService auth) {
        this.terminals = terminals;
        this.auth = auth;
    }

    @PutMapping("/terminal")
    @RequiresPermission(Permission.FLEET_MANAGE)
    public VehicleService.VehicleView bind(@PathVariable long vehicleId,
                                           @Valid @RequestBody BindingRequest request) {
        return terminals.bind(auth.currentUser(), vehicleId, request.terminalId());
    }

    @GetMapping("/terminal-bindings")
    @RequiresPermission(Permission.FLEET_VIEW)
    public List<TerminalService.BindingView> bindings(@PathVariable long vehicleId) {
        return terminals.history(auth.currentUser(), vehicleId);
    }

    public record BindingRequest(@Positive Long terminalId) { }
}
