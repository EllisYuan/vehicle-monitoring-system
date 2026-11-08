package com.vms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.ObjectMapper;
import com.vms.support.IntegrationContainers;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "vms.bootstrap-admin.username=bootstrap-admin",
        "vms.bootstrap-admin.password=Bootstrap-Pass-2026",
})
@Import(IntegrationContainers.class)
@AutoConfigureMockMvc
class CredentialLogHygieneTest {

    static final String PASSWORD = "Bootstrap-Pass-2026";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void credentialsAndTokensNeverReachApplicationLogs() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);

        String token;
        try {
            MvcResult failed = mvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("bootstrap-admin", "wrong-password")))
                    .andExpect(status().isUnauthorized())
                    .andReturn();
            assertThat(failed.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .doesNotContain("Bootstrap-Pass-2026", "wrong-password");

            MvcResult ok = mvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("bootstrap-admin", PASSWORD)))
                    .andExpect(status().isOk())
                    .andReturn();
            token = objectMapper.readTree(ok.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .path("token").asText();
            mvc.perform(post("/api/v1/auth/logout").header("satoken", token))
                    .andExpect(status().isNoContent());
        } finally {
            root.detachAppender(appender);
        }

        String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertThat(logs).doesNotContain(PASSWORD, "wrong-password");
        assertThat(logs).doesNotContain(token);
    }

    private String body(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("username", username, "password", password));
    }
}
