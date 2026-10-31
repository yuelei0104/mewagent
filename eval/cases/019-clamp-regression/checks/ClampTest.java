public class ClampTest {
    static int failed = 0;
    static void check(boolean ok, String msg) {
        if (!ok) { failed++; System.out.println("FAIL: " + msg); }
    }
    static void done() {
        if (failed > 0) { System.out.println(failed + " check(s) failed"); System.exit(1); }
        System.out.println("OK");
    }

    public static void main(String[] args) {
        check(Clamp.clamp(-5, 0, 10) == 0, "below lower -> lo");
        check(Clamp.clamp(5, 0, 10) == 5, "inside");
        check(Clamp.clamp(-1, -3, 3) == -1, "inside negative");
        done();
    }
}
