package com.vms.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TemporaryTrackingTest {

    @Test
    void acceptsActivityBoundariesAndStop() {
        assertThat(TemporaryTracking.parse(new byte[] { 0, 1, 0, 0, 0x0E, 0x10 }))
                .isEqualTo(new TemporaryTracking(1, 3600));
        assertThat(new TemporaryTracking(60, 0).encode())
                .containsExactly(0, 60, 0, 0, 0, 0);
        assertThat(TemporaryTracking.parse(new byte[] { 0, 0 }))
                .isEqualTo(new TemporaryTracking(0, 0));
    }

    @Test
    void rejectsActivityValuesOutsideConfiguredRange() {
        assertThatThrownBy(() -> TemporaryTracking.parse(new byte[] { 0, 61, 0, 0, 0x0E, 0x10 }))
                .isInstanceOf(MalformedFrameException.class);
        assertThatThrownBy(() -> TemporaryTracking.parse(new byte[] { 0, 1, 0, 0, 0x0E, 0x11 }))
                .isInstanceOf(MalformedFrameException.class);
        assertThatThrownBy(() -> new TemporaryTracking(61, 300))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TemporaryTracking(0, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsStopWithTrailingValidityAndTruncatedActivity() {
        assertThatThrownBy(() -> TemporaryTracking.parse(new byte[] { 0, 0, 0, 1 }))
                .isInstanceOf(MalformedFrameException.class);
        assertThatThrownBy(() -> TemporaryTracking.parse(new byte[] { 0, 30 }))
                .isInstanceOf(MalformedFrameException.class);
    }
}
