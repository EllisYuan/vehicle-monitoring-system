package com.vms.infrastructure;

import com.vms.infrastructure.web.ConflictException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 生效区间的时间口径：业务时钟、毫秒截断与“变更必须推进”校验集中在此，
 * 避免各关联服务各自实现后出现不一致的拒绝条件。
 */
public final class EffectiveTime {

    public static final String NOT_ADVANCED = "EFFECTIVE_TIME_NOT_ADVANCED";

    private EffectiveTime() {
    }

    public static LocalDateTime now(Clock clock) {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * 新区间的起点必须严格晚于上一区间终点（左闭右开），否则会形成空区间或倒填。
     */
    public static void requireAfter(LocalDateTime now, LocalDateTime boundary) {
        if (boundary == null || !now.isAfter(boundary)) {
            throw new ConflictException(NOT_ADVANCED, "生效时间尚未推进，请稍后重试");
        }
    }

    /**
     * 生效时间早于档案创建时间通常意味着系统时钟回拨，必须拒绝而不是写入倒填区间。
     */
    public static void requireNotBeforeArchive(LocalDateTime now, LocalDateTime createdAt) {
        if (createdAt != null && now.isBefore(createdAt)) {
            throw new ConflictException(NOT_ADVANCED, "生效时间早于档案创建时间，请检查系统时间");
        }
    }
}
