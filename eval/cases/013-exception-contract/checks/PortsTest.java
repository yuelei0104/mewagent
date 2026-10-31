public class PortsTest {
    static int failed = 0;
    static void check(boolean ok, String msg) {
        if (!ok) { failed++; System.out.println("FAIL: " + msg); }
    }
    static void done() {
        if (failed > 0) { System.out.println(failed + " check(s) failed"); System.exit(1); }
        System.out.println("OK");
    }

    public static void main(String[] args) {
        check(Ports.parse("8080") == 8080, "valid port");

        try {
            Ports.parse("abc");
            check(false, "should throw");
        } catch (IllegalArgumentException e) {
            check(e.getClass() == IllegalArgumentException.class, "exact type, got " + e.getClass());
            check(e.getMessage() != null && e.getMessage().contains("invalid port"), "message: " + e.getMessage());
        }

        try {
            Ports.parse("70000");
            check(false, "should throw");
        } catch (IllegalArgumentException e) {
            check(e.getMessage() != null && e.getMessage().contains("out of range"), "range message");
        }
        done();
    }
}
