import java.util.LinkedHashMap;
import java.util.Map;

public class Kv {
    public static Map<String, String> parse(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : text.split(";")) {
            String[] kv = part.split("=");
            out.put(kv[0], kv[1]);
        }
        return out;
    }
}
