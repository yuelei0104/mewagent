import java.util.Map;

public class ConfigTest {
    static int failed = 0;
    static void check(boolean ok, String msg) {
        if (!ok) { failed++; System.out.println("FAIL: " + msg); }
    }
    static void done() {
        if (failed > 0) { System.out.println(failed + " check(s) failed"); System.exit(1); }
        System.out.println("OK");
    }

    public static void main(String[] args) {
        Config cfg = new Config(Map.of("set", " value ", "blank", "   "));
        check("value".equals(cfg.get("set", "d")), "trimmed value");
        check("d".equals(cfg.get("missing", "d")), "missing -> fallback");
        check("d".equals(cfg.get("blank", "d")), "blank -> fallback");
        done();
    }
}
