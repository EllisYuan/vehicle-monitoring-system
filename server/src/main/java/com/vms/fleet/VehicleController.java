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
@RequestMapping("/api/v1/vehicles")
public class VehicleController {

    private final VehicleService vehicles;
    private final AuthService auth;

    public VehicleController(VehicleService vehicles, AuthService auth) {
        this.vehicles = vehicles;
        this.auth = auth;
    }

    @GetMapping
    @RequiresPermission(Permission.FLEET_VIEW)
    public List<VehicleService.VehicleView> list(@RequestParam(required = false) Long fleetId) {
        return vehicles.list(auth.currentUser(), fleetId);
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permission.FLEET_VIEW)
    public VehicleService.VehicleView get(@PathVariable long id) {
        return vehicles.get(auth.currentUser(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.FLEET_MANAGE)
    public VehicleService.VehicleView create(@Valid @RequestBody CreateRequest request) {
        return vehicles.create(auth.currentUser(), request.plateNo(), request.fleetId());
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.FLEET_MANAGE)
    public VehicleService.VehicleView update(@PathVariable long id, @Valid @RequestBody UpdateRequest request) {
        return vehicles.update(auth.currentUser(), id, request.plateNo(), request.status());
    }

    @PutMapping("/{id}/fleet")
    @RequiresPermission(Permission.FLEET_MANAGE)
    public VehicleService.VehicleView transfer(@PathVariable long id, @Valid @RequestBody TransferRequest request) {
        return vehicles.transfer(auth.currentUser(), id, request.fleetId());
    }

    @GetMapping("/{id}/fleet-history")
    @RequiresPermission(Permission.FLEET_VIEW)
    public List<VehicleService.FleetHistory> fleetHistory(@PathVariable long id) {
        return vehicles.history(auth.currentUser(), id);
    }

    public record CreateRequest(@NotBlank @Size(max = 20) String plateNo, @NotNull @Positive Long fleetId) { }
    public record UpdateRequest(@Size(max = 20) @Pattern(regexp = "(?s).*\\S.*") String plateNo,
                                @Pattern(regexp = "ENABLED|DISABLED") String status) { }
    public record TransferRequest(@NotNull @Positive Long fleetId) { }
}
