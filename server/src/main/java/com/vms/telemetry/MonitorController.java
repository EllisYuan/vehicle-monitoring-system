package com.vms.telemetry;

import com.vms.identity.auth.AuthService;
import com.vms.identity.security.Permission;
import com.vms.identity.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/monitor")
public class MonitorController {
    private final AuthService auth;
    private final MonitorSnapshotService snapshots;
    public MonitorController(AuthService auth, MonitorSnapshotService snapshots) {
        this.auth = auth;
        this.snapshots = snapshots;
    }

    @GetMapping("/snapshot")
    @RequiresPermission(Permission.FLEET_VIEW)
    public MonitorSnapshotService.Snapshot snapshot(@RequestParam(required = false) Long fleetId) {
        return snapshots.snapshot(auth.currentUser(), fleetId);
    }
}
