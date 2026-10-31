import java.util.ArrayList;
import java.util.List;

public class Countdown {
    public static List<Integer> down(int n) {
        List<Integer> out = new ArrayList<>();
        for (int i = n; i > 0; i--) {
            out.add(i);
        }
        return out;
    }
}
