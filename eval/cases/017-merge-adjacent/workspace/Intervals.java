import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Intervals {
    public static List<int[]> merge(List<int[]> input) {
        List<int[]> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparingInt(a -> a[0]));

        List<int[]> out = new ArrayList<>();
        for (int[] cur : sorted) {
            if (out.isEmpty() || out.get(out.size() - 1)[1] <= cur[0]) {
                out.add(cur);
            } else {
                int[] last = out.get(out.size() - 1);
                last[1] = Math.max(last[1], cur[1]);
            }
        }
        return out;
    }
}
