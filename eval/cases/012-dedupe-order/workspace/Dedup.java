import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class Dedup {
    public static List<Integer> unique(List<Integer> input) {
        return new ArrayList<>(new HashSet<>(input));
    }
}
