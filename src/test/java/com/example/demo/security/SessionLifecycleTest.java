package com.example.demo.security;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import com.example.demo.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class SessionLifecycleTest {
    static class MutableClock extends Clock {
        Instant time = Instant.parse("2026-10-14T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return time; }
        void advance(Duration elapsed) { time = time.plus(elapsed); }
    }
    User user() { User user = new User(); user.setId(1L); user.setRole("EMPLOYEE"); return user; }
    @Test void idleAndAbsoluteBoundariesAreServerControlled() {
        var clock = new MutableClock(); var life = new SessionLifecycle(clock, new SecuritySettings());
        var user = user(); var session = new MockHttpSession(); life.start(session, user);
        clock.advance(Duration.ofMinutes(29)); assertNull(life.invalidReason(session, user));
        clock.advance(Duration.ofMinutes(1)); assertEquals("SESSION_IDLE_EXPIRED", life.invalidReason(session, user));
        life.start(session, user);
        for (int index = 0; index < 15; index++) { clock.advance(Duration.ofMinutes(30).minusSeconds(1)); life.touch(session); }
        clock.advance(Duration.ofMinutes(31)); life.touch(session);
        assertEquals("SESSION_ABSOLUTE_EXPIRED", life.invalidReason(session, user));
    }
    @Test void authorityChangeInvalidatesEveryOldSessionAndReactivationCannotRestoreThem() {
        var life = new SessionLifecycle(new MutableClock(), new SecuritySettings()); var user = user();
        var first = new MockHttpSession(); var second = new MockHttpSession(); life.start(first, user); life.start(second, user);
        user.setAuthenticationVersion(1);
        assertEquals("SESSION_REVOKED", life.invalidReason(first, user));
        user.setStatus(0); user.setStatus(1);
        assertEquals("SESSION_REVOKED", life.invalidReason(second, user));
        life.invalidateAll(1L); assertTrue(first.isInvalid()); assertTrue(second.isInvalid());
    }
    @Test void missingAuthenticationTimeNeverGetsAFreeLifetime() {
        var life = new SessionLifecycle(new MutableClock(), new SecuritySettings()); var session = new MockHttpSession();
        session.setAttribute("loginUserId", 1L); session.setAttribute("loginRole", "ADMIN");
        assertEquals("SESSION_REVOKED", life.invalidReason(session, user()));
        assertThrows(IllegalArgumentException.class, () -> new SecuritySettings().setIdleTimeout(Duration.ZERO));
    }
}
