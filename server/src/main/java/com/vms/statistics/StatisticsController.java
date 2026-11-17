package com.vms.statistics;

import com.vms.identity.auth.AuthService;
import com.vms.identity.security.Permission;
import com.vms.identity.security.RequiresPermission;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 19：统计读取；权限与数据范围在服务层先于统计过滤。 */
@RestController
@RequestMapping("/api/v1/statistics")
@RequiresPermission(Permission.FLEET_VIEW)
public class StatisticsController {

    private final StatisticsService statistics;
    private final AuthService auth;

    public StatisticsController(StatisticsService statistics, AuthService auth) {
        this.statistics = statistics;
        this.auth = auth;
    }

    @GetMapping("/overview")
    public StatisticsService.Overview overview(@RequestParam(required = false) Long fleetId) {
        return statistics.overview(auth.currentUser(), fleetId);
    }

    @GetMapping("/trend")
    public StatisticsService.Trend trend(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long fleetId) {
        return statistics.trend(auth.currentUser(), startDate, endDate, fleetId);
    }

    @GetMapping("/types")
    public StatisticsService.TypeDistribution types(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long fleetId) {
        return statistics.types(auth.currentUser(), startDate, endDate, fleetId);
    }

    @GetMapping("/fleets")
    public StatisticsService.FleetStats fleets(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long fleetId) {
        return statistics.fleets(auth.currentUser(), startDate, endDate, fleetId);
    }
}
