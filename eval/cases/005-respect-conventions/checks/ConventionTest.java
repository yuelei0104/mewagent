import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 隐藏测试：既校验行为，也校验是否遵守 MEWAGENT.md 里的 Javadoc 规范。
 * 这是验证 InstructionLoader 是否真正影响生成结果的用例。
 */
public class ConventionTest {

    public static void main(String[] args) throws Exception {
        Method average = null;
        for (Method m : Metrics.class.getDeclaredMethods()) {
            if (m.getName().equals("average")) {
                average = m;
                break;
            }
        }
        if (average == null) fail("average 方法不存在");
        if (!Modifier.isPublic(average.getModifiers())) fail("average 必须是 public");
        if (!Modifier.isStatic(average.getModifiers())) fail("average 必须是 static");
        if (average.getParameterCount() != 1) fail("average 应接收 1 个参数");

        double avg = Metrics.average(List.of(2.0, 4.0));
        if (Math.abs(avg - 3.0) > 1e-9) fail("average([2,4]) 期望 3.0，实际 " + avg);
        double empty = Metrics.average(List.of());
        if (Math.abs(empty) > 1e-9) fail("空列表期望 0，实际 " + empty);

        String workDir = System.getProperty("workdir", ".");
        Path src = Path.of(workDir, "Metrics.java");
        if (!Files.isRegularFile(src)) fail("找不到 Metrics.java：" + src.toAbsolutePath());
        String code = Files.readString(src);

        int idx = findMethodName(code, "average");
        if (idx < 0) fail("源码里找不到 average 声明");

        int from = Math.max(0, idx - 400);
        String before = code.substring(from, idx);
        int commentStart = before.lastIndexOf("/**");
        int commentEnd = before.lastIndexOf("*/");
        boolean javadocAdjacent = commentStart >= 0
                && commentEnd > commentStart
                && before.indexOf('}', Math.max(commentStart, commentEnd)) < 0;
        if (!javadocAdjacent) {
            fail("average 上方没有紧邻的 Javadoc（项目规范要求每个 public 方法都有）");
        }

        System.out.println("ALL_PASS");
    }

    private static int findMethodName(String code, String name) {
        int idx = -1;
        while (true) {
            int next = code.indexOf(name, idx + 1);
            if (next < 0) return idx;
            idx = next;
        }
    }

    private static void fail(String msg) {
        System.out.println("FAILED: " + msg);
        System.exit(1);
    }
}
