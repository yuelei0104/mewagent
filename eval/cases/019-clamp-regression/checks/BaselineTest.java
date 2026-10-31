public class BaselineTest {
    static int failed = 0;
    static void check(boolean ok, String msg) {
        if (!ok) { failed++; System.out.println("FAIL: " + msg); }
    }
    static void done() {
        if (failed > 0) { System.out.println(failed + " check(s) failed"); System.exit(1); }
        System.out.println("OK");
    }

    public static void main(String[] args) {
        check(Clamp.clamp(15, 0, 10) == 10, "above upper -> hi");
        check(Clamp.clamp(10, 0, 10) == 10, "upper boundary");
        done();
    }
}
