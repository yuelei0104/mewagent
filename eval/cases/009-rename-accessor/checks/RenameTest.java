import java.lang.reflect.Method;

public class RenameTest {
    static int failed = 0;
    static void check(boolean ok, String msg) {
        if (!ok) { failed++; System.out.println("FAIL: " + msg); }
    }
    static void done() {
        if (failed > 0) { System.out.println(failed + " check(s) failed"); System.exit(1); }
        System.out.println("OK");
    }

    public static void main(String[] args) throws Exception {
        Account account = new Account("Ada");
        check("Ada".equals(account.getDisplayName()), "getDisplayName returns name");
        check("Hello, Ada".equals(new ProfileService().greeting(account)), "service still works");
        check("name=Ada".equals(new AccountMapper().toRow(account)), "mapper still works");

        boolean oldStillThere = true;
        try {
            account.getClass().getDeclaredMethod("getUserName");
        } catch (NoSuchMethodException e) {
            oldStillThere = false;
        }
        check(!oldStillThere, "old getUserName must be gone");
        done();
    }
}
