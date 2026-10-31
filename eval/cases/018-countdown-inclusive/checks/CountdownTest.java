import java.util.List;

public class CountdownTest {
    static int failed = 0;
    static void check(boolean ok, String msg) {
        if (!ok) { failed++; System.out.println("FAIL: " + msg); }
    }
    static void done() {
        if (failed > 0) { System.out.println(failed + " check(s) failed"); System.exit(1); }
        System.out.println("OK");
    }

    public static void main(String[] args) {
        check(Countdown.down(3).equals(List.of(3, 2, 1, 0)), "3..0");
        check(Countdown.down(0).equals(List.of(0)), "0");
        check(Countdown.down(1).equals(List.of(1, 0)), "1..0");
        done();
    }
}
