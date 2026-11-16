package com.vms.alarm;

import com.vms.identity.auth.AuthService;
import com.vms.identity.security.Permission;
import com.vms.identity.security.RequiresPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 13：报警列表与详情；处置端点按 ALARM_HANDLE 权限单独校验。 */
@RestController
@RequestMapping("/api/v1/alarms")
@RequiresPermission(Permission.FLEET_VIEW)
public class AlarmController {

    private final AlarmQueryService service;
    private final AlarmHandleService handleService;
    private final AuthService authService;

    public AlarmController(AlarmQueryService service, AlarmHandleService handleService, AuthService authService) {
        this.service = service;
        this.handleService = handleService;
        this.authService = authService;
    }

    @GetMapping
    public List<AlarmQueryService.AlarmRow> list(@RequestParam(required = false) Long fleetId) {
        return service.list(authService.currentUser(), fleetId);
    }

    @GetMapping("/{alarmId}")
    public AlarmQueryService.AlarmDetail detail(@PathVariable long alarmId) {
        return service.detail(authService.currentUser(), alarmId);
    }

    @PostMapping("/{alarmId}/handle")
    @RequiresPermission(Permission.ALARM_HANDLE)
    public AlarmHandleService.HandleView handle(@PathVariable long alarmId,
                                                @Valid @RequestBody HandleRequest request) {
        return handleService.handle(authService.currentUser(), alarmId, request.targetStatus(), request.remark());
    }

    public record HandleRequest(@NotBlank String targetStatus, @Size(max = 500) String remark) {
    }
}

