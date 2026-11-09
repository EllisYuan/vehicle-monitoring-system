package com.vms.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 可控业务时钟：用于推进锁定窗口等时间相关验收，不改变生产语义。
 */
public class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public synchronized void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    @Override
    public synchronized Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant(), zone);
    }
}
