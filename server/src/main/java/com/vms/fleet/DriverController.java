package com.vms.fleet;

import com.vms.identity.auth.AuthService;
import com.vms.identity.security.Permission;
import com.vms.identity.security.RequiresPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/drivers")
public class DriverController {

    private final DriverService drivers;
    private final AuthService auth;

    public DriverController(DriverService drivers, AuthService auth) {
        this.drivers = drivers;
        this.auth = auth;
    }

    @GetMapping
    @RequiresPermission(Permission.FLEET_VIEW)
    public List<DriverService.DriverView> list(@RequestParam(required = false) Long fleetId) {
        return drivers.list(auth.currentUser(), fleetId);
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permission.FLEET_VIEW)
    public DriverService.DriverView get(@PathVariable long id) {
        return drivers.get(auth.currentUser(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.DRIVER_MANAGE)
    public DriverService.DriverView create(@Valid @RequestBody CreateRequest request) {
        return drivers.create(auth.currentUser(), request.driverNo(), request.name(), request.phone(),
                request.fleetId());
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.DRIVER_MANAGE)
    public DriverService.DriverView update(@PathVariable long id, @Valid @RequestBody UpdateRequest request) {
        return drivers.update(auth.currentUser(), id, request.driverNo(), request.name(), request.phone(),
                request.status());
    }

    @GetMapping("/{id}/vehicle-bindings")
    @RequiresPermission(Permission.FLEET_VIEW)
    public List<DriverService.AssociationView> vehicleBindings(@PathVariable long id) {
        return drivers.driverHistory(auth.currentUser(), id);
    }

    public record CreateRequest(@NotBlank @Size(max = 32) String driverNo,
                                @NotBlank @Size(max = 50) String name,
                                @NotBlank @Pattern(regexp = "[0-9]{11}") String phone,
                                @NotNull @Positive Long fleetId) { }

    public record UpdateRequest(@Size(max = 32) @Pattern(regexp = "(?s).*\\S.*") String driverNo,
                                @Size(max = 50) @Pattern(regexp = "(?s).*\\S.*") String name,
                                @Pattern(regexp = "[0-9]{11}") String phone,
                                @Pattern(regexp = "ENABLED|DISABLED") String status) { }
}
