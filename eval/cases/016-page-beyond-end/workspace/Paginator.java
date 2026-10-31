import java.util.List;

public class Paginator {
    public static <T> List<T> page(List<T> items, int page, int size) {
        int from = (page - 1) * size;
        if (from >= items.size()) {
            from = Math.max(0, items.size() - size);
        }
        int to = Math.min(from + size, items.size());
        return items.subList(from, to);
    }
}
