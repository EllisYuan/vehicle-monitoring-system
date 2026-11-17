package com.vms.alarm;

import com.vms.identity.auth.AuthService;
import com.vms.identity.security.Permission;
import com.vms.identity.security.RequiresPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 12：管理端维护全局默认与车队覆盖两级超速规则。 */
@RestController
@RequestMapping("/api/v1/admin/alarm-rules")
@RequiresPermission(Permission.ALARM_RULE_MANAGE)
public class AlarmRuleAdminController {

    private final AlarmRuleAdminService service;
    private final AuthService authService;

    public AlarmRuleAdminController(AlarmRuleAdminService service, AuthService authService) {
        this.service = service;
        this.authService = authService;
    }

    @GetMapping
    public AlarmRuleAdminService.OverviewView overview() {
        return service.overview(authService.currentUser());
    }

    @PutMapping("/global")
    public AlarmRuleAdminService.RuleView updateGlobal(@Valid @RequestBody ThresholdRequest request) {
        return service.updateGlobal(authService.currentUser(), request.thresholdKph());
    }

    @PutMapping("/fleets/{fleetId}")
    public AlarmRuleAdminService.RuleView upsertFleetOverride(@PathVariable long fleetId,
                                                              @Valid @RequestBody ThresholdRequest request) {
        return service.upsertFleetOverride(authService.currentUser(), fleetId, request.thresholdKph());
    }

    @DeleteMapping("/fleets/{fleetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFleetOverride(@PathVariable long fleetId) {
        service.deleteFleetOverride(authService.currentUser(), fleetId);
    }

    public record ThresholdRequest(@Min(1) @Max(300) int thresholdKph) {
    }
}
