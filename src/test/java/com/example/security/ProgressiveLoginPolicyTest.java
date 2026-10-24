package com.example.security;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class ProgressiveLoginPolicyTest {
    final ProgressiveLoginPolicy policy=ProgressiveLoginPolicy.defaults();
    final LocalDateTime now=LocalDateTime.of(2026,10,15,9,0);
    @Test void fifthTenthAndFifteenthFailuresRetainStagesAcrossRestrictionExpiry(){
        var state=policy.reset(now);var time=now;
        for(int i=1;i<=15;i++){
            state=policy.failed(state,time);assertEquals(i,state.failures());
            if(i==5){assertEquals(900,policy.retryAfter(state,time));assertEquals(state,policy.failed(state,time.plusSeconds(1)));time=time.plusMinutes(15);}
            if(i==10){assertEquals(3600,policy.retryAfter(state,time));assertEquals(state,policy.failed(state,time.plusMinutes(1)));time=time.plusMinutes(60);}
        }
        assertTrue(state.protectedLogin());assertEquals(3,state.stage());assertEquals(state,policy.current(state,time.plusDays(2)));
        assertEquals(state,policy.failed(state,time.plusDays(3)));assertEquals(0,policy.retryAfter(state,time));
    }
    @Test void observationExpiryResetsOrdinaryFailuresButDoesNotShortenActiveRestriction(){
        var ordinary=new ProgressiveLoginPolicy.State(9,now,1,null,false);
        assertEquals(1,policy.failed(ordinary,now.plusHours(24)).failures());
        var active=new ProgressiveLoginPolicy.State(10,now,2,now.plusHours(25),false);
        assertEquals(active,policy.current(active,now.plusHours(24)));
        assertEquals(1,policy.failed(active,now.plusHours(25)).failures());
    }
    @Test void fullLoginResetHasFreshObservationAndNoStage(){var reset=policy.reset(now);assertEquals(0,reset.failures());assertEquals(0,reset.stage());assertFalse(reset.protectedLogin());assertEquals(now,reset.windowStart());}
    @Test void configuredThresholdsMustBeAscendingAndLimitsPositive(){
        assertThrows(IllegalArgumentException.class,()->new ProgressiveLoginPolicy(Duration.ofHours(24),5,Duration.ofMinutes(15),5,Duration.ofMinutes(60),15));
        assertThrows(IllegalArgumentException.class,()->new ProgressiveLoginPolicy(Duration.ZERO,5,Duration.ofMinutes(15),10,Duration.ofMinutes(60),15));
        assertThrows(IllegalArgumentException.class,()->new ProgressiveLoginPolicy(Duration.ofHours(24),5,Duration.ofMinutes(15),10,Duration.ofMinutes(1),15));
    }
    @Test
    void futureObservationStartKeepsFailuresAndDoesNotBypassNextRestriction() {
        ProgressiveLoginPolicy.State future = new ProgressiveLoginPolicy.State(4, now.plusHours(1), 0, null, false);
        ProgressiveLoginPolicy.State current = policy.current(future, now);
        assertEquals(4, current.failures());
        assertEquals(now, current.windowStart());
        ProgressiveLoginPolicy.State fifth = policy.failed(current, now);
        assertEquals(5, fifth.failures());
        assertEquals(900, policy.retryAfter(fifth, now));

        ProgressiveLoginPolicy.State ninth = new ProgressiveLoginPolicy.State(9, now.plusHours(1), 1, null, false);
        ProgressiveLoginPolicy.State tenth = policy.failed(ninth, now);
        assertEquals(10, tenth.failures());
        assertEquals(3600, policy.retryAfter(tenth, now));
    }

    @Test
    void futureObservationDoesNotReleaseProtectionOrShortenActiveRestriction() {
        ProgressiveLoginPolicy.State protectedState = new ProgressiveLoginPolicy.State(15, now.plusHours(1), 3, null, true);
        assertEquals(protectedState, policy.current(protectedState, now));
        ProgressiveLoginPolicy.State blocked = new ProgressiveLoginPolicy.State(10, now.plusHours(1), 2, now.plusHours(2), false);
        assertEquals(blocked, policy.current(blocked, now));
        assertEquals(7200, policy.retryAfter(blocked, now));
        assertThrows(IllegalStateException.class, () -> policy.current(
                new ProgressiveLoginPolicy.State(-1, now, 0, null, false), now));
    }
    @Test void legacyCounterKeepsFirstStageInsteadOfLockingOnEveryNextFailure(){var state=new ProgressiveLoginPolicy.State(5,null,0,null,false);var next=policy.failed(state,now);assertEquals(6,next.failures());assertEquals(1,next.stage());assertNull(next.blockedUntil());}
}
