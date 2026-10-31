public class SearchTest {
    static int failed = 0;
    static void check(boolean ok, String msg) {
        if (!ok) { failed++; System.out.println("FAIL: " + msg); }
    }
    static void done() {
        if (failed > 0) { System.out.println(failed + " check(s) failed"); System.exit(1); }
        System.out.println("OK");
    }

    public static void main(String[] args) {
        check(Search.indexOf(new int[]{}, 1) == -1, "empty -> -1");
        check(Search.indexOf(new int[]{1, 2, 3}, 2) == 1, "found middle");
        check(Search.indexOf(new int[]{1, 2, 3}, 9) == -1, "missing -> -1");
        check(Search.indexOf(new int[]{1, 2, 3}, 1) == 0, "first");
        check(Search.indexOf(new int[]{1, 2, 3}, 3) == 2, "last");
        done();
    }
}
