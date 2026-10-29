public class UserController {

    private final UserRepository repo = new UserRepository();

    /** 模拟 HTTP 处理，返回简易状态串。 */
    public String handle(int id) {
        String name = repo.getUserById(id);
        return name == null ? "404" : "200 " + name;
    }
}
