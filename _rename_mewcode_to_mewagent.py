#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MewCode → MewAgent 全量改名（"对外发布的新版本" 范围）

执行顺序：
  1. 物理移动 com/mewcode/ → com/mewagent/ (main/test/resources)
  2. 重命名 MewCode.java / MewCodeModel.java → MewAgent*
  3. 文本替换（先长后短避免误伤）
  4. 改 Gradle 配置
  5. 改根目录启动脚本（重命名 + 内容）
  6. 改 README
  7. 改 ConfigLoader（加迁移逻辑）
  8. 改 MewAgent.java（MEWCODE_CONFIG → MEWAGENT_CONFIG + 兼容回退）

注意：
  - 全程 UTF-8
  - 不动 shengyuban/、声语伴技术方案.md（声语伴是产品代号，独立）
  - 不动 src.bak/（备份）
  - 不动 bin/、build/（编译产物，下次 build 自动重生成）
  - 不动 .mewcode/（用户数据，迁移逻辑在 ConfigLoader 加，不在这里）
"""
import re
import shutil
from pathlib import Path

ROOT = Path(r'D:\mewcode-java')


# ============= 1. 物理移动 com/mewcode/ 目录 =============
def step1_move_dirs():
    print("\n[1] 物理移动 com/mewcode/ 目录")
    sources = [
        ROOT / 'src' / 'main' / 'java' / 'com' / 'mewcode',
        ROOT / 'src' / 'test' / 'java' / 'com' / 'mewcode',
        ROOT / 'src' / 'main' / 'resources' / 'com' / 'mewcode',
    ]
    for src in sources:
        if not src.exists():
            print(f"  [SKIP] 不存在: {src.relative_to(ROOT)}")
            continue
        dst = src.parent / 'mewagent'
        if dst.exists():
            print(f"  [SKIP] 目标已存在: {dst.relative_to(ROOT)}")
            continue
        shutil.move(str(src), str(dst))
        print(f"  [OK] {src.relative_to(ROOT)} → {dst.relative_to(ROOT)}")
    # 清掉空的 com/mewcode/ 残留
    for parent in [
        ROOT / 'src' / 'main' / 'java' / 'com',
        ROOT / 'src' / 'test' / 'java' / 'com',
        ROOT / 'src' / 'main' / 'resources' / 'com',
    ]:
        stale = parent / 'mewcode'
        if stale.exists() and not any(stale.iterdir()):
            stale.rmdir()
            print(f"  [CLEAN] 删除空目录: {stale.relative_to(ROOT)}")


# ============= 2. 重命名顶层 java 文件 =============
def step2_rename_java_files():
    print("\n[2] 重命名顶层 java 文件")
    pairs = [
        (ROOT / 'src' / 'main' / 'java' / 'com' / 'mewagent' / 'MewCode.java',
         ROOT / 'src' / 'main' / 'java' / 'com' / 'mewagent' / 'MewAgent.java'),
        (ROOT / 'src' / 'main' / 'java' / 'com' / 'mewagent' / 'tui' / 'MewCodeModel.java',
         ROOT / 'src' / 'main' / 'java' / 'com' / 'mewagent' / 'tui' / 'MewAgentModel.java'),
    ]
    for old, new in pairs:
        if not old.exists():
            print(f"  [SKIP] 不存在: {old.relative_to(ROOT)}")
            continue
        if new.exists():
            print(f"  [SKIP] 目标已存在: {new.relative_to(ROOT)}")
            continue
        old.rename(new)
        print(f"  [OK] {old.relative_to(ROOT)} → {new.relative_to(ROOT)}")


# ============= 3. 文本替换 =============
# 顺序：先长后短，避免误伤
REPLACEMENTS = [
    # 3.1 类名（先替换长的 MewCodeModel）
    ('MewCodeModel', 'MewAgentModel'),
    # 3.2 类名 MewCode
    ('MewCode', 'MewAgent'),
    # 3.3 包名 / import / FQN
    ('com.mewcode', 'com.mewagent'),
    # 3.4 文件名：MEWCODE.md → MEWAGENT.md
    ('MEWCODE.md', 'MEWAGENT.md'),
    # 3.5 prompt 段标题
    ('# mewcodeMd', '# mewagentMd'),
    # 3.6 带斜杠的配置路径
    ('.mewcode/', '.mewagent/'),
    # 3.7 链式 Path.of 里的 ".mewcode"（带引号，无斜杠）
    ('".mewcode"', '".mewagent"'),
    # 3.8 环境变量字符串（后面会在 MewAgent.java 加 fallback 逻辑）
    ('"MEWCODE_CONFIG"', '"MEWAGENT_CONFIG"'),
]


def step3_patch_text():
    print("\n[3] 文本批量替换")
    text_exts = {'.java', '.html', '.md', '.yaml', '.yml', '.json', '.properties', '.xml', '.txt', '.kts'}
    roots = [
        ROOT / 'src' / 'main' / 'java',
        ROOT / 'src' / 'test' / 'java',
        ROOT / 'src' / 'main' / 'resources',
    ]
    total_files = 0
    total_changes = 0
    for root in roots:
        if not root.exists():
            continue
        for p in root.rglob('*'):
            if not p.is_file() or p.suffix not in text_exts:
                continue
            try:
                text = p.read_text(encoding='utf-8')
            except UnicodeDecodeError:
                continue
            orig = text
            file_changes = 0
            for old, new in REPLACEMENTS:
                if old in text:
                    cnt = text.count(old)
                    text = text.replace(old, new)
                    file_changes += cnt
            if text != orig:
                p.write_text(text, encoding='utf-8')
                rel = p.relative_to(ROOT)
                print(f"  [{file_changes:>3} changes] {rel}")
                total_files += 1
                total_changes += file_changes
    print(f"  总计: {total_files} 个文件，{total_changes} 处替换")


# ============= 4. Gradle 配置 =============
def step4_gradle():
    print("\n[4] 改 Gradle 配置")
    # build.gradle.kts 里的 mainClass 和 archiveBaseName 在第 3 步已经被 .kts 扩展名替换掉一部分
    # 但 mainClass 的全限定名是 com.mewcode.MewCode → com.mewagent.MewAgent（被 3 步处理了）
    # archiveBaseName = "mewcode" → "mewagent"（不在 .kts 替换列表里，单独处理）
    build_kts = ROOT / 'build.gradle.kts'
    text = build_kts.read_text(encoding='utf-8')
    repls = [
        ('archiveBaseName = "mewcode"', 'archiveBaseName = "mewagent"'),
    ]
    for old, new in repls:
        if old in text:
            text = text.replace(old, new)
            print(f"  [OK] build.gradle.kts: {old!r} → {new!r}")
    build_kts.write_text(text, encoding='utf-8')

    settings_kts = ROOT / 'settings.gradle.kts'
    text = settings_kts.read_text(encoding='utf-8')
    if 'rootProject.name = "mewcode"' in text:
        text = text.replace('rootProject.name = "mewcode"', 'rootProject.name = "mewagent"')
        print(f"  [OK] settings.gradle.kts: rootProject.name")
        settings_kts.write_text(text, encoding='utf-8')


# ============= 5. 启动脚本 =============
def step5_scripts():
    print("\n[5] 重命名根目录启动脚本")
    # mewcode (bash) → mewagent
    old_sh = ROOT / 'mewcode'
    new_sh = ROOT / 'mewagent'
    if old_sh.exists() and not new_sh.exists():
        text = old_sh.read_text(encoding='utf-8')
        # 3 步没处理 .sh 文件（不在 text_exts 里），单独处理内容
        text = text.replace('mewcode.jar', 'mewagent.jar')
        text = text.replace('[mewcode]', '[mewagent]')
        new_sh.write_text(text, encoding='utf-8')
        old_sh.unlink()
        print(f"  [OK] mewcode → mewagent (sh)")
    elif new_sh.exists():
        print(f"  [SKIP] mewagent 已存在")
    else:
        print(f"  [SKIP] mewcode 不存在")

    # mewcode.bat → mewagent.bat
    old_bat = ROOT / 'mewcode.bat'
    new_bat = ROOT / 'mewagent.bat'
    if old_bat.exists() and not new_bat.exists():
        text = old_bat.read_text(encoding='utf-8')
        text = text.replace('mewcode.jar', 'mewagent.jar')
        text = text.replace('[mewcode]', '[mewagent]')
        new_bat.write_text(text, encoding='utf-8')
        old_bat.unlink()
        print(f"  [OK] mewcode.bat → mewagent.bat")
    elif new_bat.exists():
        print(f"  [SKIP] mewagent.bat 已存在")
    else:
        print(f"  [SKIP] mewcode.bat 不存在")


# ============= 6. README =============
def step6_readme():
    print("\n[6] 改 README.md")
    p = ROOT / 'README.md'
    text = p.read_text(encoding='utf-8')
    # 3 步已经处理 .md 文件，但 README 里的 .mewcode/ 配置目录说明要保留
    # 我们手动加一段"兼容迁移说明"
    repls = [
        # jar 名
        ('mewcode.jar', 'mewagent.jar'),
        # 启动脚本引用
        ('./mewcode', './mewagent'),
        # 类名（在 README 文本里）
        # （MewCodeModel 已经被 3 步处理）
        # 配置目录 .mewcode 保留（用户数据），但需要说明
    ]
    for old, new in repls:
        if old in text:
            text = text.replace(old, new)
            print(f"  [OK] README: {old!r} → {new!r}")

    # 加一段"升级迁移"说明（在"快速开始"前面或后面）
    migration_note = """

## 升级迁移（MewCode → MewAgent）

如果你从 MewCode 升级，首次启动时会自动把 `~/.mewcode/` 下的历史数据
（sessions、memory、file-history、skills、config.yaml）迁移到 `~/.mewagent/`。
原目录会保留为 `.mewcode.bak.<timestamp>` 直到下次启动后清理。

环境变量从 `MEWCODE_CONFIG` 改为 `MEWAGENT_CONFIG`（旧值仍可读取，带弃用警告）。
"""
    if '升级迁移' not in text and '升级迁移（MewCode' not in text:
        # 插到"快速开始"之前
        if '## 快速开始' in text:
            text = text.replace('## 快速开始', migration_note + '\n## 快速开始', 1)
        else:
            text += migration_note
        print(f"  [OK] README: 加了升级迁移说明")
    p.write_text(text, encoding='utf-8')


def main():
    print("=" * 60)
    print("MewCode → MewAgent 全量改名（对外发布新版本）")
    print("=" * 60)
    step1_move_dirs()
    step2_rename_java_files()
    step3_patch_text()
    step4_gradle()
    step5_scripts()
    step6_readme()
    print("\n" + "=" * 60)
    print("[核心步骤完成] 下一步：")
    print("  7. ConfigLoader 加 .mewcode → .mewagent 迁移逻辑")
    print("  8. MewAgent.java 加 MEWAGENT_CONFIG + MEWCODE_CONFIG fallback")
    print("  9. ./gradlew build 验证")
    print(" 10. ./gradlew test 验证")
    print("=" * 60)


if __name__ == '__main__':
    main()
