import java.util.List;

public class Stats {
    public static int average(List<Integer> items) {
        int sum = 0;
        for (int v : items) {
            sum += v;
        }
        return sum / items.size();
    }
}
