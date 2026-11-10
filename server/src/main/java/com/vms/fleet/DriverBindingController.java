package com.vms.fleet;

import com.vms.identity.auth.AuthService;
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
 * 车辆司机关联归 driver 模块所有；路径保持在车辆资源下，便于页面按车辆操作。
 */
@RestController
@RequestMapping("/api/v1/vehicles/{vehicleId}")
public class DriverBindingController {

    private final DriverService drivers;
    private final AuthService auth;

    public DriverBindingController(DriverService drivers, AuthService auth) {
        this.drivers = drivers;
        this.auth = auth;
    }

    @GetMapping("/driver")
    @RequiresPermission(Permission.FLEET_VIEW)
    public DriverService.CurrentAssociation current(@PathVariable long vehicleId) {
        return drivers.current(auth.currentUser(), vehicleId);
    }

    @PutMapping("/driver")
    @RequiresPermission(Permission.DRIVER_MANAGE)
    public DriverService.CurrentAssociation bind(@PathVariable long vehicleId,
                                                 @Valid @RequestBody BindingRequest request) {
        return drivers.bind(auth.currentUser(), vehicleId, request.driverId());
    }

    @GetMapping("/driver-bindings")
    @RequiresPermission(Permission.FLEET_VIEW)
    public List<DriverService.AssociationView> bindings(@PathVariable long vehicleId) {
        return drivers.history(auth.currentUser(), vehicleId);
    }

    public record BindingRequest(@Positive Long driverId) { }
}
