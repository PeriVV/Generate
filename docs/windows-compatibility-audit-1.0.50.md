# Windows 专项兼容性排查（1.0.50）

日期：2026-10-23。Windows 是本轮部署目标。当前执行主机是 macOS；Windows 原生验收与本机可执行回归分别记录。
本轮保留之前工作区的修改，对插件运行链路补充适配和回归，不安装插件、不改业务项目、不调用付费模型。

## 问题与修复

| 环节 | 原问题 | 修复与检查 |
| --- | --- | --- |
| 长 javac 命令 | 把所有反斜杠改为 `/`，会改坏处理器参数；引号、单引号、`#`、换行等值也可能被参数文件重新解释 | 参数文件统一带引号并按 javac 语法转义，保留参数原值；通过真实编译器和注解处理器回读完整值 |
| 参数文件编码与 JVM 选项 | 参数文件固定 UTF-8，但旧 JDK 按默认编码读取；原逻辑把不支持放进参数文件的 `-J` 也写进去 | 保留原 `-J` 在启动命令上，参数文件读取端指定 UTF-8；提供 JDK 17 与可选 JDK 8 用例 |
| Java classpath 缩短 | 相对路径按 IDE 工作目录解析；丢弃空条目；Manifest 不展开 `lib/*` | 使用实际测试工作目录，保留空条目的当前目录含义，展开通配 JAR，URL 使用 ASCII 编码；真实 Java 执行与 40 KB classpath 回归 |
| Windows 批处理启动 | 所有入口统一交给 cmd，包括 exe；手工拼接缺少批处理参数转义 | `.bat/.cmd` 使用 IDEA `GeneralCommandLine` 的 Windows 参数处理，关闭 AutoRun 与延迟扩展；exe 直接执行；原生 cmd 回归覆盖中文/空格路径及特殊字符 |
| 环境变量 | Windows 不区分变量名大小写，但 ProcessBuilder 的 Map 区分，`PATH` 排除可能遗漏 `Path`，覆盖时可能留下两个同名变量 | 排除和覆盖按 Windows 大小写规则处理，其他平台保留原规则；两种规则均有回归 |
| CRLF 与 BOM | 文档使用 LF，原磁盘读取使用 CRLF/BOM，运行证明和写入快照的源码指纹可能不同 | 源码/已验证文本使用与文档一致的 LF 和去 BOM 表示；磁盘并发锁另用原始字节 SHA-256，保留外部修改检测 |
| GBK 源码与间接依赖 | 源码和间接依赖固定按 UTF-8 读取，联合编译也可能把 GBK 原文件交给 UTF-8 javac | 使用 IDE 对应路径的文件编码；依赖图中的项目文件复制到隔离目录并转为 UTF-8；原项目文件不转码。真实 GBK 两层依赖编译及 JVM 执行回归 |
| 写入与最终展示 | 写入后固定按 UTF-8 核验；保存文档可能重新选择默认编码；展示也可能把 GBK 文件误标过期 | 保存前后保留文件编码，核验解码后的精确文本；无法表示的字符在修改文档前拒绝，避免替换字符。最终报告使用同一文本读取规则 |
| 配置测试依赖 | 尚未打开的 CRLF 构建文件以磁盘文本形成预览，提交时却与 LF 文档比较；GBK 构建文件和备份按 UTF-8 处理 | 统一预览/文档文本，磁盘变化按字节指纹检查；保存与备份保留原编码和检测到的换行。真实 CRLF/GBK POM 预览、修改和备份回归 |
| 编译与运行诊断 | 中文编译/运行输出与 UTF-8 读取端可能不一致 | 外部 javac 的编码明确设置；JUnit 默认重定向 stdout/stderr 为 UTF-8，保留生产代码 `file.encoding` / 默认 Charset 配置；GBK 默认 Charset 与中文输出同时验证 |
| 进程生命周期 | 旧回归只用 POSIX shell，不能证明 Windows 的进程树处理 | 新增真实 Java 父子进程回归，覆盖正常清理和中断标志；保留既有取消/超时门禁 |
| 回归入口 | 测试固定指向 `bin/gradle`，Gradle 多模块测试在 Windows 直接返回 | 选择 `bin/gradle.bat`，Maven/Gradle 导出回归支持 Windows；增加必须在 Windows 运行的专用任务和 CI |

参数文件行为依据 [Oracle javac 文档](https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html)、[OpenJDK 17 参数文件读取实现](https://github.com/openjdk/jdk17u/blob/master/src/jdk.compiler/share/classes/com/sun/tools/javac/main/CommandLine.java)，批处理规则参考 [Microsoft cmd 文档](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/cmd)。环境变量区别可核对 [OpenJDK Windows ProcessEnvironment](https://github.com/openjdk/jdk17u/blob/master/src/java.base/windows/classes/java/lang/ProcessEnvironment.java)。

## Windows 原生验收

工作流：`.github/workflows/windows-compatibility.yml`。

工作流的 checkout/setup-java 入口已按 [checkout 官方配置](https://github.com/actions/checkout)、[setup-java 官方配置](https://github.com/actions/setup-java) 核对为 Node 24 对应版本；这项 YAML 静态检查独立于插件回归，不作为 CI 已运行的证明。

- 在 Windows runner 的 `工作区 UnitMind` 路径检出，实际覆盖中文和空格路径。
- 使用 JDK 17 运行插件/IDE 测试，另安装 JDK 8 验证参数文件、Mockito 4/JUnit/JaCoCo 旧项目运行时。
- 预热 Maven help plugin 后，运行真实 Maven effective POM 和 Gradle Groovy/Kotlin 多模块导出。
- `windowsCompatibilityTest` 覆盖批处理、编译/JVM、40 KB classpath、进程树、编码、文件写入、运行门禁、依赖配置和插件 HTTP 类加载，并构建安装包。
- `windowsCompatibilityTest` 在非 Windows 主机直接失败，避免错误地生成“Windows 通过”结论；普通 test 仍可在其他平台验证可移植规则。

在 Windows、JDK 17 和可用 Maven 环境执行：

```powershell
.\gradlew.bat windowsCompatibilityTest buildPlugin --console=plain
```

有 JDK 8 时增加参数：

```powershell
.\gradlew.bat windowsCompatibilityTest buildPlugin "-PunitmindTestJava8Home=C:\Program Files\Java\jdk8" --console=plain
```

测试产物位于 `build/reports/tests/windowsCompatibilityTest/` 与 `build/test-results/windowsCompatibilityTest/`。
CI 会保存这些结果及安装包；当前工作区尚未提交或推送，工作流尚未在 GitHub 执行。

## 当前证据与边界

本机可执行的编码、参数转义、真实编译器/JVM、文件写入和进程树检查用于验证实现。
原生 `cmd.exe`、Windows 的实际文件系统/杀进程行为，以及实际 JDK 8 不可由 macOS 回归替代；本机没有执行这些原生步骤。
本轮未连接 Windows 主机，未进行 Windows IDEA 图形界面的完整操作验收、网络共享盘/UNC、企业权限策略或各类杀毒软件组合验证。
**在 Windows 原生任务成功前，1.0.50 的状态是“已完成专项修复、待原生验收”。**

最终结果记录于 `build/reports/verification-1.0.50/summary.json`：

- macOS / JDK 17 完整测试集：714 项，0 失败、0 错误；`test buildPlugin` 成功。本机不会执行 Windows 批处理和可选 JDK 8 原生步骤，测试引擎的通过计数不能替代这些平台的验收。
- 专项检查点：83 项；构建文件编码与换行修复检查点：10 项，均无失败。这些检查点与最终完整测试集有重叠，不相加。
- 308 个编译、测试、资源与构建输入的摘要在测试及打包过程中保持一致；CI 文件另存静态检查摘要，未实际运行。
- 包内版本为 1.0.50，关键修复类齐全；插件类为 Java 17 字节码；10 个内置 Mockito 运行时 JAR 的指纹均与索引一致，旧运行时基础类的字节码不高于 Java 8。
- 安装包：`build/distributions/UnitMind-1.0.50.zip`，23,970,365 字节。
- 安装包 SHA-256：`edd6b9a5316037e85480c7f277ef9a5d44f2bdd3f485e16e8888bff2d33cfa3e`。
