public class UserService {

    private final UserRepository repo;

    public UserService(UserRepository repo) {
        this.repo = repo;
    }

    /** 返回用户描述。 */
    public String describe(int id) {
        String name = repo.getUserById(id);
        return name == null ? "unknown" : "user:" + name;
    }
}
