package com.vms.identity.account;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {

    // MySQL 按 SET 顺序求值：计数先使用旧 locked_until，锁定判断再使用新计数。
    @Update("""
            UPDATE sys_user
               SET failed_attempts = CASE
                       WHEN locked_until IS NOT NULL AND locked_until <= #{now} THEN 1
                       ELSE failed_attempts + 1 END,
                   locked_until = CASE
                       WHEN failed_attempts >= #{maxFailures} THEN #{lockUntil}
                       ELSE NULL END,
                   updated_at = #{now}
             WHERE id = #{id}
               AND (locked_until IS NULL OR locked_until <= #{now})
            """)
    int incrementFailureAndMaybeLock(@Param("id") Long id,
                                     @Param("now") LocalDateTime now,
                                     @Param("maxFailures") int maxFailures,
                                     @Param("lockUntil") LocalDateTime lockUntil);

    /**
     * 成功登录或锁定到期后清零失败计数，只触碰登录状态字段。
     */
    @Update("""
            UPDATE sys_user
               SET failed_attempts = 0,
                   locked_until = NULL,
                   updated_at = #{now}
             WHERE id = #{id}
            """)
    int resetLoginState(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 只更新密码并提升授权版本，使该账号已有会话立即失效。
     */
    @Update("""
            UPDATE sys_user
               SET password_hash = #{passwordHash},
                   auth_version = auth_version + 1,
                   failed_attempts = 0,
                   locked_until = NULL,
                   updated_at = #{updatedAt}
             WHERE id = #{id}
            """)
    int updatePasswordAndRevokeSessions(@Param("id") Long id,
                                       @Param("passwordHash") String passwordHash,
                                       @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE sys_user
               SET password_hash = #{passwordHash},
                   auth_version = auth_version + 1,
                   failed_attempts = 0,
                   locked_until = NULL,
                   updated_at = #{updatedAt}
             WHERE id = #{id} AND auth_version = #{authVersion} AND status = 'ENABLED'
            """)
    int changePasswordIfCurrent(@Param("id") Long id,
                                @Param("authVersion") int authVersion,
                                @Param("passwordHash") String passwordHash,
                                @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 只更新状态并提升授权版本。
     */
    @Update("""
            UPDATE sys_user
               SET status = #{status},
                   auth_version = auth_version + 1,
                   updated_at = #{updatedAt}
             WHERE id = #{id}
            """)
    int updateStatusAndRevokeSessions(@Param("id") Long id,
                                      @Param("status") String status,
                                      @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 授权变化只提升版本，不动其他字段。
     */
    @Update("""
            UPDATE sys_user
               SET auth_version = auth_version + 1,
                   updated_at = #{updatedAt}
             WHERE id = #{id}
            """)
    int revokeSessions(@Param("id") Long id, @Param("updatedAt") LocalDateTime updatedAt);
}
