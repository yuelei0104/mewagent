import java.util.List;

public class PaginatorTest {
    static int failed = 0;
    static void check(boolean ok, String msg) {
        if (!ok) { failed++; System.out.println("FAIL: " + msg); }
    }
    static void done() {
        if (failed > 0) { System.out.println(failed + " check(s) failed"); System.exit(1); }
        System.out.println("OK");
    }

    public static void main(String[] args) {
        List<Integer> items = List.of(0, 1, 2, 3, 4, 5, 6);
        check(Paginator.page(items, 1, 3).equals(List.of(0, 1, 2)), "first page");
        check(Paginator.page(items, 3, 3).equals(List.of(6)), "last partial page");
        check(Paginator.page(items, 99, 3).isEmpty(), "beyond end -> empty");
        check(Paginator.page(items, 0, 3).isEmpty(), "page 0 -> empty");
        done();
    }
}
