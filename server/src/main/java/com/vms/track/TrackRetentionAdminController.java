package com.vms.track;

import com.vms.identity.auth.AuthService;
import com.vms.identity.security.Permission;
import com.vms.identity.security.RequiresPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理端轨迹保留期与分区维护接口。 */
@RestController
@RequestMapping("/api/v1/admin/track-retention")
@RequiresPermission(Permission.TRACK_RETENTION_MANAGE)
public class TrackRetentionAdminController {

    private final TrackRetentionAdminService service;
    private final AuthService authService;

    public TrackRetentionAdminController(TrackRetentionAdminService service, AuthService authService) {
        this.service = service;
        this.authService = authService;
    }

    @GetMapping
    public TrackRetentionAdminService.OverviewView overview() {
        return service.overview();
    }

    @PostMapping("/preview")
    public TrackRetentionAdminService.PreviewView preview(@Valid @RequestBody PreviewRequest request) {
        return service.preview(authService.currentUser(), request.retentionDays());
    }

    @PostMapping("/confirm")
    public TrackRetentionAdminService.ConfirmView confirm(@Valid @RequestBody ConfirmRequest request) {
        return service.confirm(authService.currentUser(), request.token());
    }

    public record PreviewRequest(@Min(1) @Max(365) int retentionDays) {
    }

    public record ConfirmRequest(@NotBlank String token) {
    }
}
