package com.vms.audit;

import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 审计只记录可追溯的操作事实；调用方保证 detail 不含密码、token 或鉴权码。
 */
@Service
public class AuditService {

    public static final String CATEGORY_BOOTSTRAP = "BOOTSTRAP";
    public static final String CATEGORY_LOGIN = "LOGIN";
    public static final String CATEGORY_LOGOUT = "LOGOUT";
    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAILURE = "FAILURE";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AuditService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void record(String category, String action, String target, String result,
                       Long actorId, String actorName, String sourceIp, String detail) {
        jdbc.update("""
                        INSERT INTO audit_log
                            (actor_id, actor_name, category, action, target, result, source_ip, detail, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                actorId, actorName, category, action, target, result, sourceIp, detail,
                LocalDateTime.now(clock));
    }
}
