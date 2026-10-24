package com.example.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionRegistryDateTimeTest {

    @Test
    void sessionDateParametersDoNotDependOnJvmDefaultTimezone() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(2);
        when(jdbc.queryForObject(contains("SELECT sequence_no"), eq(Long.class),
                anyString(), anyString(), anyString())).thenReturn(0L);

        Map<String, Object> user = new LinkedHashMap<String, Object>();
        user.put("role", "ADMIN");
        user.put("status", 1);
        user.put("delete_flag", 0);
        user.put("authentication_version", 0L);
        List<Map<String, Object>> users = new ArrayList<Map<String, Object>>();
        users.add(user);
        when(jdbc.queryForList(contains("SELECT role,status"), anyString())).thenReturn(users);

        List<String> statements = new ArrayList<String>();
        List<Object[]> parameters = new ArrayList<Object[]>();
        doAnswer(new Answer<Integer>() {
            @Override
            public Integer answer(InvocationOnMock invocation) {
                statements.add((String) invocation.getRawArguments()[0]);
                parameters.add((Object[]) invocation.getRawArguments()[1]);
                return 1;
            }
        }).when(jdbc).update(anyString(), any(Object[].class));

        Clock clock = Clock.fixed(Instant.parse("2026-10-24T08:44:49Z"), ZoneOffset.UTC);
        SessionRegistry registry = new SessionRegistry(jdbc, manager, clock, "OA",
                SessionRegistry.OA_OWNER, "date-binding-test", Duration.ofMinutes(30), Duration.ofHours(8));
        registry.initialize();
        String reference = registry.open("1", "QA_EMP_1", "ADMIN", 0L, 0L, null, "192.0.2.1");
        registry.touch(reference);
        registry.close(reference, "LOGOUT");

        LocalDateTime expected = LocalDateTime.of(2026, 10, 24, 17, 44, 49);
        int checkedStatements = 0;
        for (int index = 0; index < statements.size(); index++) {
            String sql = statements.get(index);
            Object[] values = parameters.get(index);
            if (sql.startsWith("INSERT INTO security_session_registry")) {
                assertEquals(expected, values[13]);
                assertEquals(expected, values[14]);
                assertEquals(expected.plusMinutes(30), values[15]);
                assertEquals(expected.plusHours(8), values[16]);
                checkedStatements++;
            } else if (sql.startsWith("UPDATE security_session_registry")) {
                assertInstanceOf(LocalDateTime.class, values[0]);
                assertEquals(expected, values[0]);
                if (sql.contains("last_active_at=")) {
                    assertEquals(expected.plusMinutes(30), values[1]);
                    assertEquals(expected, values[6]);
                    assertEquals(expected, values[7]);
                }
                checkedStatements++;
            }
        }
        assertEquals(4, checkedStatements);
    }
}
