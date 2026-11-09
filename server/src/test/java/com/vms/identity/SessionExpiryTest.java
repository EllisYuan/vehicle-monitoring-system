package com.vms.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vms.support.IntegrationContainers;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * AC03：8 小时绝对到期、不随活跃续期。
 * 为在合理时间内执行，本测试把 sa-token timeout 缩短为 4 秒；
 * 生产配置为 28800 秒（见 FleetAccessTest 的配置断言），语义同为绝对到期。
 */
@SpringBootTest(properties = {
        "vms.bootstrap-admin.username=bootstrap-admin",
        "vms.bootstrap-admin.password=Bootstrap-Pass-2026",
        "vms.login.rate-limit-max-attempts=1000",
        "sa-token.timeout=4",
})
@Import(IntegrationContainers.class)
@AutoConfigureMockMvc
class SessionExpiryTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void sessionsExpireAbsolutelyAndActivityDoesNotExtendThem() throws Exception {
        String token = login();

        mvc.perform(get("/api/v1/auth/me").header("satoken", token)).andExpect(status().isOk());

        // 在到期前持续活跃：绝对到期语义下不延长
        Thread.sleep(2500);
        mvc.perform(get("/api/v1/auth/me").header("satoken", token)).andExpect(status().isOk());

        // 登录时刻 + 5.5s 已超过 4s 绝对期限；若活跃续期则会推迟到约 6.5s，届时应仍有效
        Thread.sleep(3000);
        mvc.perform(get("/api/v1/auth/me").header("satoken", token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    private String login() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "bootstrap-admin", "password", "Bootstrap-Pass-2026"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("token").asText();
    }
}
