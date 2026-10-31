public class OrderTest {
    static int failed = 0;
    static void check(boolean ok, String msg) {
        if (!ok) { failed++; System.out.println("FAIL: " + msg); }
    }
    static void done() {
        if (failed > 0) { System.out.println(failed + " check(s) failed"); System.exit(1); }
        System.out.println("OK");
    }

    static boolean throwsIllegalState(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalStateException e) {
            return true;
        }
    }

    public static void main(String[] args) {
        check("PAID".equals(Order.next("NEW")), "NEW -> PAID");
        check("SHIPPED".equals(Order.next("PAID")), "PAID -> SHIPPED");
        check(throwsIllegalState(() -> Order.next("SHIPPED")), "SHIPPED must not go back");
        check(throwsIllegalState(() -> Order.next("CANCELLED")), "unknown state throws");
        done();
    }
}
