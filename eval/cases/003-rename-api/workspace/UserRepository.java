import java.util.HashMap;
import java.util.Map;

public class UserRepository {

    private final Map<Integer, String> store = new HashMap<>(Map.of(1, "alice", 2, "bob"));

    /** 按 id 查询用户名，不存在返回 null。 */
    public String getUserById(int id) {
        return store.get(id);
    }
}
