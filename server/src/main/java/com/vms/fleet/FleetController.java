package com.vms.fleet;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fleets")
public class FleetController {

    private final FleetService fleetService;
    private final AuthService authService;

    public FleetController(FleetService fleetService, AuthService authService) {
        this.fleetService = fleetService;
        this.authService = authService;
    }

    @GetMapping
    @RequiresPermission(Permission.FLEET_VIEW)
    public List<FleetView> list(@RequestParam(required = false) Long fleetId) {
        return fleetService.list(authService.currentUser(), fleetId).stream()
                .map(FleetView::of).toList();
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permission.FLEET_VIEW)
    public FleetView get(@PathVariable long id) {
        return FleetView.of(fleetService.get(authService.currentUser(), id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.FLEET_MANAGE)
    public FleetView create(@Valid @RequestBody CreateFleetRequest request) {
        return FleetView.of(fleetService.create(authService.currentUser(),
                request.fleetCode(), request.fleetName()));
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.FLEET_MANAGE)
    public FleetView update(@PathVariable long id, @Valid @RequestBody UpdateFleetRequest request) {
        return FleetView.of(fleetService.update(authService.currentUser(), id,
                request.fleetName(), request.status()));
    }

    public record CreateFleetRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_.-]+$") String fleetCode,
            @NotBlank @Size(max = 128) String fleetName) {
    }

    public record UpdateFleetRequest(
            @Size(max = 128) String fleetName,
            @Pattern(regexp = "ENABLED|DISABLED") String status) {
    }

    public record FleetView(Long id, String fleetCode, String fleetName, String status) {
        public static FleetView of(Fleet fleet) {
            return new FleetView(fleet.getId(), fleet.getFleetCode(), fleet.getFleetName(), fleet.getStatus());
        }
    }
}
