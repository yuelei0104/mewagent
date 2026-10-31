import java.util.List;

public class DedupTest {
    static int failed = 0;
    static void check(boolean ok, String msg) {
        if (!ok) { failed++; System.out.println("FAIL: " + msg); }
    }
    static void done() {
        if (failed > 0) { System.out.println(failed + " check(s) failed"); System.exit(1); }
        System.out.println("OK");
    }

    public static void main(String[] args) {
        check(Dedup.unique(List.of(3, 1, 3, 2)).equals(List.of(3, 1, 2)), "order preserved");
        check(Dedup.unique(List.of()).isEmpty(), "empty");
        check(Dedup.unique(List.of(7, 7, 7)).equals(List.of(7)), "all same");
        done();
    }
}
