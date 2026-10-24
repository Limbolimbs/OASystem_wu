package com.example.security;

import java.time.Duration;
import java.time.LocalDateTime;

/** 時間窓と段階制限を扱う共通規則。保護解除は別の認可済み操作で行う。 */
public record ProgressiveLoginPolicy(Duration observationWindow, int firstThreshold, Duration firstLock,
        int secondThreshold, Duration secondLock, int protectionThreshold) {
    public record State(int failures, LocalDateTime windowStart, int stage, LocalDateTime blockedUntil, boolean protectedLogin) {}
    public ProgressiveLoginPolicy {
        if (observationWindow == null || observationWindow.isNegative() || observationWindow.isZero()
                || observationWindow.compareTo(Duration.ofDays(30)) > 0
                || firstLock == null || secondLock == null || firstLock.isNegative() || firstLock.isZero()
                || secondLock.compareTo(firstLock) < 0 || secondLock.compareTo(Duration.ofDays(1)) > 0
                || firstThreshold < 1 || secondThreshold <= firstThreshold
                || protectionThreshold <= secondThreshold || protectionThreshold > 10000)
            throw new IllegalArgumentException("認証制限の期間・段階順を確認してください。");
    }
    public static ProgressiveLoginPolicy defaults() {
        return new ProgressiveLoginPolicy(Duration.ofHours(24), 5, Duration.ofMinutes(15), 10, Duration.ofMinutes(60), 15);
    }
    public long retryAfter(State state, LocalDateTime now) {
        if (state.blockedUntil() == null || !state.blockedUntil().isAfter(now)) return 0;
        return Math.max(1, (Duration.between(now, state.blockedUntil()).toMillis() + 999) / 1000);
    }
    public State current(State state, LocalDateTime now) {
        if (state.failures() < 0 || state.stage() < 0 || state.stage() > 3)
            throw new IllegalStateException("認証制限の保存状態が不正です。");
        if (state.protectedLogin() || retryAfter(state, now) > 0) return state;
        if (state.windowStart() == null) {
            int stage=Math.max(state.stage(),state.failures()>=secondThreshold?2:state.failures()>=firstThreshold?1:0);
            return new State(state.failures(),now,stage,null,false);
        }
        LocalDateTime observationStart = state.windowStart();
        // 旧時区や時計補正で開始日だけが未来になっても、失敗回数・段階は消さない。
        // 有効な制限と手動解除が必要な保護は、上でそのまま返している。
        if (observationStart.isAfter(now)) {
            observationStart = now;
        }
        if (!observationStart.plus(observationWindow).isAfter(now)) return reset(now);
        int stage = Math.max(state.stage(), state.failures() >= secondThreshold ? 2 : state.failures() >= firstThreshold ? 1 : 0);
        return new State(state.failures(), observationStart, stage, null, false);
    }
    public State failed(State before, LocalDateTime now) {
        State state = current(before, now);
        if (state.protectedLogin() || retryAfter(state, now) > 0) return state;
        int failures = Math.addExact(state.failures(), 1);
        if (failures >= protectionThreshold) return new State(failures, state.windowStart(), 3, null, true);
        if (failures >= secondThreshold && state.stage() < 2)
            return new State(failures, state.windowStart(), 2, now.plus(secondLock), false);
        if (failures >= firstThreshold && state.stage() < 1)
            return new State(failures, state.windowStart(), 1, now.plus(firstLock), false);
        return new State(failures, state.windowStart(), state.stage(), null, false);
    }
    public State reset(LocalDateTime now) { return new State(0, now, 0, null, false); }
}
