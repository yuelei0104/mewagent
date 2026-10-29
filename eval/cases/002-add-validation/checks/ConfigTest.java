/**
 * 隐藏测试：校验异常类型统一为 IllegalArgumentException，且消息含 "port"。
 */
public class ConfigTest {

    public static void main(String[] args) {
        expectIllegalArgument(() -> Config.port(null), "null");
        expectIllegalArgument(() -> Config.port("   "), "blank");
        expectIllegalArgument(() -> Config.port("abc"), "non-numeric");

        if (Config.port("8080") != 8080) fail("有效输入的行为被改变了");

        System.out.println("ALL_PASS");
    }

    private static void expectIllegalArgument(Runnable action, String name) {
        try {
            action.run();
            fail(name + ": 没有抛异常");
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if (msg == null || !msg.toLowerCase().contains("port")) {
                fail(name + ": 异常消息不含 port，实际=" + msg);
            }
        } catch (RuntimeException e) {
            fail(name + ": 抛了 " + e.getClass().getSimpleName() + "，期望 IllegalArgumentException");
        }
    }

    private static void fail(String msg) {
        System.out.println("FAILED: " + msg);
        System.exit(1);
    }
}
