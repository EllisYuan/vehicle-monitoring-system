package com.vms.geofence;

import com.vms.identity.auth.AuthService;
import com.vms.identity.security.Permission;
import com.vms.identity.security.RequiresPermission;
import jakarta.validation.Valid;
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

/** 15：围栏创建、编辑、启停与车辆绑定；读取按 FleetScope 过滤。 */
@RestController
@RequestMapping("/api/v1/geofences")
public class GeofenceController {

    private final GeofenceAdminService service;
    private final AuthService auth;

    public GeofenceController(GeofenceAdminService service, AuthService auth) {
        this.service = service;
        this.auth = auth;
    }

    @GetMapping
    @RequiresPermission(Permission.FLEET_VIEW)
    public List<GeofenceAdminService.GeofenceView> list(@RequestParam(required = false) Long fleetId) {
        return service.list(auth.currentUser(), fleetId);
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permission.FLEET_VIEW)
    public GeofenceAdminService.GeofenceView get(@PathVariable long id) {
        return service.get(auth.currentUser(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.GEOFENCE_MANAGE)
    public GeofenceAdminService.GeofenceView create(
            @Valid @RequestBody GeofenceAdminService.GeofenceRequest request) {
        return service.create(auth.currentUser(), request);
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.GEOFENCE_MANAGE)
    public GeofenceAdminService.GeofenceView update(
            @PathVariable long id, @Valid @RequestBody GeofenceAdminService.GeofenceRequest request) {
        return service.update(auth.currentUser(), id, request);
    }

    @PutMapping("/{id}/status")
    @RequiresPermission(Permission.GEOFENCE_MANAGE)
    public GeofenceAdminService.GeofenceView updateStatus(
            @PathVariable long id, @Valid @RequestBody GeofenceAdminService.StatusRequest request) {
        return service.updateStatus(auth.currentUser(), id, request.status());
    }

    @PutMapping("/{id}/vehicles")
    @RequiresPermission(Permission.GEOFENCE_MANAGE)
    public GeofenceAdminService.GeofenceView updateVehicles(
            @PathVariable long id, @Valid @RequestBody GeofenceAdminService.VehiclesRequest request) {
        return service.updateVehicles(auth.currentUser(), id, request.vehicleIds());
    }
}