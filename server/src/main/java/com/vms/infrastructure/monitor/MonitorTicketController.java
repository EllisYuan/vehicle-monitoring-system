package com.vms.infrastructure.monitor;

import cn.dev33.satoken.stp.StpUtil;
import com.vms.identity.security.Permission;
import com.vms.identity.security.RequiresPermission;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MonitorTicketController {
    private final MonitorTicketService tickets;
    public MonitorTicketController(MonitorTicketService tickets) { this.tickets = tickets; }

    @PostMapping("/api/v1/auth/ws-ticket")
    @RequiresPermission(Permission.FLEET_VIEW)
    public ResponseEntity<MonitorTicketService.Issued> issue() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(tickets.issue(StpUtil.getTokenValue()));
    }
}
