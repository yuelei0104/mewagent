import java.util.Map;

public class Order {
    private static final Map<String, String> NEXT = Map.of(
            "NEW", "PAID",
            "PAID", "SHIPPED",
            "SHIPPED", "PAID"
    );

    public static String next(String current) {
        String next = NEXT.get(current);
        if (next == null) {
            throw new IllegalStateException("no transition from " + current);
        }
        return next;
    }
}
