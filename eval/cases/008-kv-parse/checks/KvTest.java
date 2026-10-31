import java.util.Map;

public class KvTest {
    static int failed = 0;
    static void check(boolean ok, String msg) {
        if (!ok) { failed++; System.out.println("FAIL: " + msg); }
    }
    static void done() {
        if (failed > 0) { System.out.println(failed + " check(s) failed"); System.exit(1); }
        System.out.println("OK");
    }

    public static void main(String[] args) {
        Map<String, String> a = Kv.parse("a=1;b=2;");
        check(a.size() == 2, "trailing ; ignored, got " + a);
        check("2".equals(a.get("b")), "b=2");

        Map<String, String> b = Kv.parse("a=");
        check("".equals(b.get("a")), "empty value allowed, got " + b);

        Map<String, String> c = Kv.parse(" a = 1 ; b = 2 ");
        check("1".equals(c.get("a")) && "2".equals(c.get("b")), "trim key/value, got " + c);

        check(Kv.parse("").isEmpty(), "empty input -> empty map");
        done();
    }
}
