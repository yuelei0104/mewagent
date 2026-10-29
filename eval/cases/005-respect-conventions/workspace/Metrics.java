import java.util.List;

public class Metrics {

    /** 求和。 */
    public static double sum(List<Double> values) {
        double total = 0;
        for (double v : values) {
            total += v;
        }
        return total;
    }
}
