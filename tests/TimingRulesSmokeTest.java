import com.example.eyebreak20.TimingRules;

public final class TimingRulesSmokeTest {
    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }

    public static void main(String[] args) {
        long off = 1_000_000L;
        check(TimingRules.isShortScreenOff(off, off + 19_999, 20_000), "19.999s preserves usage");
        check(TimingRules.isShortScreenOff(off, off + 20_000, 20_000), "exactly 20s preserves usage");
        check(!TimingRules.isShortScreenOff(off, off + 20_001, 20_000), "longer than 20s resets");
        check(!TimingRules.isShortScreenOff(off, off - 1, 20_000), "backward clock is invalid");
        check(TimingRules.moveTimestampAfterPause(900_000, off, off + 15_000) == 915_000,
                "usage start moves forward for paused time");
        check(TimingRules.moveTimestampAfterPause(1_100_000, off, off + 15_000) == 1_115_000,
                "reminder due moves forward for paused time");
        check(TimingRules.isWithinStrictBreak(5_020_000, 5_019_999), "re-lock before deadline");
        check(!TimingRules.isWithinStrictBreak(5_020_000, 5_020_000), "stop re-lock at deadline");
        System.out.println("PASS: 8 timing assertions");
    }
}
