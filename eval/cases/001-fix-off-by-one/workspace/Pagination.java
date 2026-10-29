import java.util.List;

public class Pagination {

    /** 返回列表的第 page 页（从 0 开始），每页 size 条。 */
    public static <T> List<T> page(List<T> items, int page, int size) {
        int start = page * size;
        int end = start + size;
        return items.subList(start, end);
    }
}
