import java.util.Map;

public class Config {
    private final Map<String, String> values;

    public Config(Map<String, String> values) {
        this.values = values;
    }

    public String get(String key, String fallback) {
        return values.get(key);
    }
}
