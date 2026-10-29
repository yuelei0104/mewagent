import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 隐藏测试：旧方法名必须消失、新方法名必须存在，且两个调用点的行为不变。
 */
public class RenameTest {

    public static void main(String[] args) {
        Method[] methods = UserRepository.class.getDeclaredMethods();

        boolean hasNew = Arrays.stream(methods).anyMatch(m -> m.getName().equals("findUserById"));
        boolean hasOld = Arrays.stream(methods).anyMatch(m -> m.getName().equals("getUserById"));

        if (!hasNew) fail("UserRepository 上没有 findUserById");
        if (hasOld) fail("旧方法 getUserById 仍然存在，应删除或改名");

        UserRepository repo = new UserRepository();
        if (!"alice".equals(repo.findUserById(1))) fail("findUserById 行为改变");
        if (repo.findUserById(99) != null) fail("不存在的 id 应返回 null");

        if (!"user:alice".equals(new UserService(repo).describe(1))) fail("UserService 调用点未更新");
        if (!"200 alice".equals(new UserController().handle(1))) fail("UserController 调用点未更新");
        if (!"404".equals(new UserController().handle(99))) fail("UserController 的 404 分支被破坏");

        System.out.println("ALL_PASS");
    }

    private static void fail(String msg) {
        System.out.println("FAILED: " + msg);
        System.exit(1);
    }
}
