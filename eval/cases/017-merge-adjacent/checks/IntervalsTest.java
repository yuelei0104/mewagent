import java.util.List;

public class IntervalsTest {
    static int failed = 0;
    static void check(boolean ok, String msg) {
        if (!ok) { failed++; System.out.println("FAIL: " + msg); }
    }
    static void done() {
        if (failed > 0) { System.out.println(failed + " check(s) failed"); System.exit(1); }
        System.out.println("OK");
    }

    static String render(List<int[]> list) {
        StringBuilder sb = new StringBuilder();
        for (int[] r : list) sb.append("[").append(r[0]).append(",").append(r[1]).append("]");
        return sb.toString();
    }

    public static void main(String[] args) {
        check("[1,3]".equals(render(Intervals.merge(List.of(new int[]{1, 2}, new int[]{2, 3})))), "touching merged");
        check("[1,4]".equals(render(Intervals.merge(List.of(new int[]{1, 3}, new int[]{2, 4})))), "overlap merged");
        check("[1,2][5,6]".equals(render(Intervals.merge(List.of(new int[]{5, 6}, new int[]{1, 2})))), "sorted, disjoint");
        done();
    }
}
