import java.util.List;

/**
 * 隐藏测试：不在 Agent 的工作区内，避免被改写。
 * 覆盖首页、中间页、最后一页不整除、页码超范围、空列表五种情况。
 */
public class PaginationTest {

    public static void main(String[] args) {
        List<Integer> items = List.of(1, 2, 3, 4, 5);

        expect(Pagination.page(items, 0, 2), List.of(1, 2), "first page");
        expect(Pagination.page(items, 1, 2), List.of(3, 4), "middle page");
        expect(Pagination.page(items, 2, 2), List.of(5), "last partial page");
        expect(Pagination.page(items, 5, 2), List.of(), "page beyond range");
        expect(Pagination.page(List.of(), 0, 3), List.of(), "empty input");

        System.out.println("ALL_PASS");
    }

    private static <T> void expect(List<T> actual, List<T> expected, String name) {
        if (!actual.equals(expected)) {
            System.out.println("FAILED: " + name + " expected=" + expected + " actual=" + actual);
            System.exit(1);
        }
    }
}
