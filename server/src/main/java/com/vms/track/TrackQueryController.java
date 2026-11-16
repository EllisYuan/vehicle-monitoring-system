package com.vms.track;

import com.vms.identity.account.UserAccount;
import com.vms.identity.auth.AuthService;
import com.vms.identity.security.Permission;
import com.vms.identity.security.RequiresPermission;
import com.vms.infrastructure.ClockConfig;
import com.vms.infrastructure.web.BadRequestException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 单车轨迹查询；参数缺失或格式错误统一返回 400，时间按业务时区解释。 */
@RestController
@RequestMapping("/api/v1/tracks")
public class TrackQueryController {

    private final AuthService auth;
    private final TrackQueryService tracks;

    public TrackQueryController(AuthService auth, TrackQueryService tracks) {
        this.auth = auth;
        this.tracks = tracks;
    }

    @GetMapping
    @RequiresPermission(Permission.FLEET_VIEW)
    public TrackQueryService.TrackView query(@RequestParam(required = false) Long vehicleId,
                                             @RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to) {
        UserAccount user = auth.currentUser();
        if (vehicleId == null) {
            throw new BadRequestException("TRACK_VEHICLE_REQUIRED", "必须指定要查询的车辆");
        }
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw new BadRequestException("TRACK_TIME_INVALID", "开始与结束时间必须为有效时间");
        }
        return tracks.query(user, vehicleId, parse("开始时间", from), parse("结束时间", to));
    }

    private static OffsetDateTime parse(String label, String value) {
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value).atZone(ClockConfig.BUSINESS_ZONE).toOffsetDateTime();
            } catch (DateTimeParseException e) {
                throw new BadRequestException("TRACK_TIME_INVALID", label + "格式无效：" + value);
            }
        }
    }
}
