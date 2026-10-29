# MewAgent Eval

端到端评测。单元测试验证组件正确，这套东西回答另一个问题：**Agent 能不能真的把一个任务做完**。

每个用例是一个独立的小仓库，跑分器用 `-p` 模式驱动真实 Agent 去改，改完由**隐藏断言**判定。

## 用法

```bash
./eval/run.sh                    # 全部用例
./eval/run.sh --only=001,003     # 只跑 id 含 001 或 003 的
./eval/run.sh --limit=2          # 只跑前 2 个
./eval/run.sh --keep             # 保留工作区现场
./eval/run.sh --inherit-proxy    # 需要走代理访问 API 时
```

前提：`build/libs/mewagent.jar` 可用（脚本会自动重建），且 `.mewagent/config.yaml` 里的provider 能连通。

输出：

- 终端：逐用例结果 + 通过率 / 平均轮次 / 平均耗时 / token 合计 / 失败分类
- `build/eval/report.json`：完整数据，便于画趋势
- `build/eval/report.md`：可直接贴进 README 或周报

## 目录结构

```
eval/
├── EvalRunner.java          # 跑分器（单文件，用 jar 的 classpath 运行）
├── run.sh                   # 入口，自动按需构建 jar
└── cases/
    └── 001-fix-off-by-one/
        ├── case.json        # id / title / prompt / timeoutSec / maxTurns
        ├── workspace/       # 交给 Agent 的初始文件
        ├── verify.sh        # 断言脚本，退出码 0 = 通过
        └── checks/          # 隐藏测试，Agent 看不到
```

`checks/` 与 `workspace/` 分开是关键：断言用的测试不在 Agent 的工作区里，
它无法通过"改测试"让自己通过。

## 指标口径

全部来自 `--output-format stream-json` 的真实事件流，不做估算：

| 指标 | 来源 |
|---|---|
| 轮次 | `result.num_turns` |
| token | `usage` 事件（in/out 分别累计） |
| 耗时 | `result.duration_ms` |
| 工具调用 | `tool_use` 事件计数 |
| 改动文件数 | 运行前后工作区的 SHA-256 基线对比 |

失败会分类，避免只看到一个百分比：`no_action`（一个工具都没调）、
`check_failed`（改了但断言不过）、`agent_error`（Agent 报错）、`timeout`、
`harness_error`（跑分器自身问题）。

## 现有用例

| id | 考察点 |
|---|---|
| 001-fix-off-by-one | 定位并修复越界，含边界与空输入 |
| 002-add-validation | 把多种异常统一为 IllegalArgumentException |
| 003-rename-api | 跨三个文件重命名 API，不留旧名、不改行为 |
| 004-read-only-question | **反向用例**：纯问答不得改动任何文件 |
| 005-respect-conventions | 按 `MEWAGENT.md` 的项目规范新增方法（验证指令注入是否真的生效） |
| 006-minimal-edit | 只改一处拼写，整份文件必须逐字节等于期望 |

004 和 006 是最容易被忽略但最能体现质量的两条：多数 Agent 的问题不是"做不对"，
而是"做多了"。

## 加用例

1. 建 `cases/007-xxx/`，写 `case.json`，把初始文件放进 `workspace/`
2. 把断言测试放进 `checks/`，写 `verify.sh`（可用 `CASE_DIR`、`WORK_DIR`、`BASELINE`）
3. `./eval/run.sh --only=007`

写完先自检双向：原始 `workspace` 必须**失败**，手工改对后必须**通过**。
否则这个用例没有区分度。

## 设计取舍

- **禁用 MCP**：MCP 是外部进程，`npx` 拉包会让单个用例卡上数分钟，把测量结果
  污染成"启动耗时"。需要时加 `--keep-mcp`。
- **不 git init 工作区**：仓库探测在 Windows + 嵌套仓库下实测会多耗数十秒，
  正好撞上 LLM 流的 30 秒超时；而且会让被评测的 Agent 用 git 操作结果。
  改用文件哈希基线，同时让"不该改动"这类断言更直接。
- **清空代理变量**：宿主 shell 的 `http_proxy` 常指向本地代理，被 SDK 继承后
  所有请求都会卡死。需要代理时用 `--inherit-proxy` 显式开启。
- **单次采样**：LLM 有随机性，单跑一次只能看趋势。做严格对比时建议多跑几轮
  看方差，而不是只看一次通过率。
