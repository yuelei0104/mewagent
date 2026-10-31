public class FormatterTest {
    static int failed = 0;
    static void check(boolean ok, String msg) {
        if (!ok) { failed++; System.out.println("FAIL: " + msg); }
    }
    static void done() {
        if (failed > 0) { System.out.println(failed + " check(s) failed"); System.exit(1); }
        System.out.println("OK");
    }

    public static void main(String[] args) throws Exception {
        check("$12.34".equals(Formatter.format(1234)), "formatter output");
        check("Total: $12.34".equals(new Invoice().total(1234)), "invoice uses formatter");

        boolean moneyStillHasFormat = true;
        try {
            Money.class.getDeclaredMethod("format", long.class);
        } catch (NoSuchMethodException e) {
            moneyStillHasFormat = false;
        }
        check(!moneyStillHasFormat, "Money.format must be gone");
        done();
    }
}
