package com.vms.simulator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 22：规范化计划。normalizedText 只包含终端、场景、报文类型、相对时间与输入字段，
 * 不含墙钟时间与连接先后，因此相同 seed/路线/车数可逐条比对。
 */
public record ScenarioPlan(List<String> terminals, List<PlanEntry> entries) {

    public ScenarioPlan {
        terminals = List.copyOf(terminals);
        entries = List.copyOf(entries);
    }

    public String normalizedText() {
        List<PlanEntry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparing(PlanEntry::terminalNo)
                .thenComparingLong(PlanEntry::offsetMs)
                .thenComparing(entry -> entry.scene().ordinal())
                .thenComparing(PlanEntry::kind)
                .thenComparingInt(PlanEntry::messageId));
        StringBuilder builder = new StringBuilder();
        for (PlanEntry entry : ordered) {
            builder.append(entry.terminalNo()).append('|')
                    .append(entry.scene().key()).append('|')
                    .append(entry.kind()).append('|')
                    .append(entry.offsetMs()).append('|')
                    .append(String.format(Locale.ROOT, "%04X", entry.messageId())).append('|')
                    .append(format(entry.lng())).append('|')
                    .append(format(entry.lat())).append('|')
                    .append(entry.speed()).append('|')
                    .append(entry.direction()).append('|')
                    .append(entry.fixValid() ? '1' : '0').append('|')
                    .append(entry.dropAcks());
            if (!entry.items().isEmpty()) {
                builder.append('|');
                for (PlanItem item : entry.items()) {
                    builder.append(item.gpsOffsetSeconds()).append(',')
                            .append(format(item.lng())).append(',')
                            .append(format(item.lat())).append(',')
                            .append(item.speed()).append(',')
                            .append(item.direction()).append(',')
                            .append(item.fixValid() ? '1' : '0').append(';');
                }
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalizedText().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
