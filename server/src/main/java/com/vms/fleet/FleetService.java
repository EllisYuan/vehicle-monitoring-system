package com.vms.fleet;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vms.audit.AuditService;
import com.vms.identity.access.FleetScope;
import com.vms.identity.access.FleetScopeResolver;
import com.vms.identity.account.UserAccount;
import com.vms.identity.security.ForbiddenException;
import com.vms.infrastructure.web.ConflictException;
import com.vms.infrastructure.web.NotFoundException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FleetService {

    private final FleetMapper fleets;
    private final FleetScopeResolver scopeResolver;
    private final AuditService auditService;
    private final Clock clock;

    public FleetService(FleetMapper fleets, FleetScopeResolver scopeResolver,
                        AuditService auditService, Clock clock) {
        this.fleets = fleets;
        this.scopeResolver = scopeResolver;
        this.auditService = auditService;
        this.clock = clock;
    }

    public List<Fleet> list(UserAccount user, Long fleetIdFilter) {
        FleetScope scope = scopeResolver.resolve(user);
        if (fleetIdFilter != null && !scope.contains(fleetIdFilter)) {
            throw new ForbiddenException();
        }
        LambdaQueryWrapper<Fleet> query = new LambdaQueryWrapper<Fleet>().orderByAsc(Fleet::getFleetCode);
        if (fleetIdFilter != null) {
            query.eq(Fleet::getId, fleetIdFilter);
        } else if (!scope.all()) {
            if (scope.fleetIds().isEmpty()) {
                return List.of();
            }
            query.in(Fleet::getId, scope.fleetIds());
        }
        return fleets.selectList(query);
    }

    public Fleet get(UserAccount user, long fleetId) {
        // 先判定数据范围，再判断存在性，避免向车队长泄露其他车队是否存在
        if (!scopeResolver.resolve(user).contains(fleetId)) {
            throw new ForbiddenException();
        }
        Fleet fleet = fleets.selectById(fleetId);
        if (fleet == null) {
            throw new NotFoundException("FLEET_NOT_FOUND", "车队不存在");
        }
        return fleet;
    }

    @Transactional
    public Fleet create(UserAccount actor, String fleetCode, String fleetName) {
        LocalDateTime now = LocalDateTime.now(clock);
        Fleet fleet = new Fleet();
        fleet.setFleetCode(fleetCode);
        fleet.setFleetName(fleetName);
        fleet.setStatus("ENABLED");
        fleet.setCreatedAt(now);
        fleet.setUpdatedAt(now);
        try {
            fleets.insert(fleet);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("FLEET_CODE_EXISTS", "车队编号已存在");
        }
        auditService.record(AuditService.CATEGORY_FLEET, "CREATE", "fleet:" + fleet.getId(),
                AuditService.RESULT_SUCCESS, actor.getId(), actor.getUsername(), null,
                "fleetCode=" + fleetCode);
        return fleet;
    }

    @Transactional
    public Fleet update(UserAccount actor, long fleetId, String fleetName, String status) {
        Fleet fleet = fleets.selectById(fleetId);
        if (fleet == null) {
            throw new NotFoundException("FLEET_NOT_FOUND", "车队不存在");
        }
        if (fleetName != null) {
            fleet.setFleetName(fleetName);
        }
        if (status != null) {
            fleet.setStatus(status);
        }
        fleet.setUpdatedAt(LocalDateTime.now(clock));
        fleets.updateById(fleet);
        auditService.record(AuditService.CATEGORY_FLEET, "UPDATE", "fleet:" + fleetId,
                AuditService.RESULT_SUCCESS, actor.getId(), actor.getUsername(), null,
                "fleetName=" + fleet.getFleetName() + ", status=" + fleet.getStatus());
        return fleet;
    }
}
