import java.util.List;

public class StatsTest {
    static int failed = 0;
    static void check(boolean ok, String msg) {
        if (!ok) { failed++; System.out.println("FAIL: " + msg); }
    }
    static void done() {
        if (failed > 0) { System.out.println(failed + " check(s) failed"); System.exit(1); }
        System.out.println("OK");
    }

    public static void main(String[] args) {
        check(Stats.average(List.of()) == 0, "empty -> 0");
        check(Stats.average(null) == 0, "null -> 0");
        check(Stats.average(List.of(1, 2, 3)) == 2, "1,2,3 -> 2");
        check(Stats.average(List.of(5)) == 5, "single -> 5");
        done();
    }
}
