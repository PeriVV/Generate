# Windows 原生验收记录（1.0.51）

状态：Windows 原生验收门禁全部通过，安装包已完成指纹与内容核对。

## 最终结果

- [Windows 原生验收运行](https://github.com/PeriVV/Generate/actions/runs/39974738580)，源代码提交 `8de51d1bd4919b48eae3167992a6bab5ac7c1f63`。
- Windows 全量回归 `721` 项、专项 `166` 项，均为 `0` 失败、`0` 错误、`0` 跳过；两个任务有重叠，不合并为独立用例数量。
- 项目 JDK 8、11、17、21、25 的编译或执行路径已验证，8/11/21/25 使用真实对应 JDK 生成对应版本字节码并运行 JUnit、Mockito、JaCoCo。
- IDEA 2022.2.5、2025.3、2026.2.0.1 的 Plugin Verifier 结果均为 `Compatible`，没有二进制兼容问题、无效插件或缺失依赖错误；仍存在弃用、计划移除和内部 API 提示，不代表零提示或所有版本的人工 GUI 验收。
- 最终 ZIP 直接取自通过验收的 Windows 构建，大小 `23,973,373` 字节；插件主类均为 Java 17 字节码，12 个插件类路径 JAR 无重复 Kotlin/Spring/Mockito 类，两个离线 Mockito 包共 10 个 JAR 的索引指纹一致。

安装文件：`build/distributions/UnitMind-1.0.51.zip`。在 IDEA 的 Plugins 中选择 **Install Plugin from Disk**，选中 ZIP 后重启。

SHA-256：

```text
eaa228eabd32dcabf0ec40a7b904ba0b48f349404eee3d4b8de83088c8a9c4b5
```

### 验收计数与规则探索的区别

上述 721/166 是验收测试本身的结果。额外的规则探索扫描实际覆盖 3 个仓库的 42 个方法，42 个均执行到沙箱，无环境级错误；其中 26 个方法的全部原始生成候选通过，16 个方法存在候选断言失败。该扫描按照原有探索契约编目失败，不以任意方法都能一次生成成功作为门槛。这些结果不能表述为生成质量全部通过；产品的候选编译、运行与写入门禁仍需逐次通过。本轮未调用付费模型，未进行用户业务项目或所有 IDEA 版本的人工验收。

最终原始证据位于 `build/reports/windows-native-acceptance/final/`，包括 `summary.json`、包检查记录、逐用例 XML、三档 IDE 判定和完整规则探索报告。

## 环境与门禁

- 使用 GitHub Actions 的真实 Windows x64 主机，源码检出目录包含中文和空格。
- IDEA 222 测试启动器在英语 Windows 的 ANSI 启动参数中会损坏中文路径，CI 将编译产物和 IDE 测试沙箱放在单独的 ASCII 目录，保留中文源码目录；功能用例另行实际创建中文项目、类路径和输出目录。
- Windows 核心专项包含原生 cmd、真实 javac/JVM、40 KB classpath、Java 8 参数文件、Mockito/JUnit/JaCoCo、多种编码与运行配置。
- 项目运行时验证 JDK 8、11、17、21、25；8/11/21/25 分别使用对应 JDK 编译对应字节码，并实际运行 JUnit、Mockito 和 JaCoCo；插件自身仍以 Java 17 字节码构建。
- 扩大后的 Windows 专项、完整测试、安装包构建及 IDEA API 兼容检查必须全部成功，才上传 `windows-verified-plugin` 安装包。
- API 检查选取 IDEA 2022.2.5、2025.3、2026.2 三档代表版本。API 检查与 IDEA 222 中的功能测试分别记录，不能等同于所有版本的人工 GUI 验收。
- 开源流水线使用固定 tag 的 Apache Commons Codec、Commons IO、Commons Lang 原始源码，在 Windows 原生编译后验证；它不代表全部开源矩阵都已在 Windows 执行。保留批量扫描不少于 30 个方法的门槛，并在完整回归前单独执行扫描。
- 不调用付费模型。

### 外部构建工具的路径边界

Gradle 8 的测试 worker 在 Cp1252 Windows 上会把无法编码的中文 classpath 写成问号，原生子报告已复现 `ClassNotFoundException: example.SubjectTest`。上游记录见 [Gradle #30391](https://github.com/gradle/gradle/issues/30391) 和 [#30304](https://github.com/gradle/gradle/issues/30304)。验收保留中文源码目录，为外部 Maven/Gradle 夹具显式设置 ASCII 构建输出目录；插件 CI 也采用该布局。Maven 夹具额外用相对 `MAVEN_BASEDIR` 避免旧 Java 启动器的 Unicode 属性参数损坏。

这些设置限定外部构建工具用例的验证环境；插件不会擅自改动用户项目的构建输出目录，也不能将此结果解释为任意旧版 Gradle 的中文输出路径缺陷已修复。插件自己的沙箱编译、JUnit 与 JaCoCo 启动另有真实中文路径用例。

## 本轮已定位并修复的问题

| 问题 | 处理 |
| --- | --- |
| JDK 17 在 ANSI Windows 下损坏绝对中文 classpath | Gradle Wrapper 使用 ASCII 临时入口与 Manifest URL；插件对短中文命令也启用参数文件或 pathing JAR，并尽可能使用相对辅助文件名 |
| Windows 盘符根相对路径与 `lib/*` | 使用 Windows Path 解析根路径，在调用 Path API 前拆出通配符，保留通配 JAR 与空 classpath 条目含义 |
| 中文目录下 agent 的原生加载失败 | 保留 agent 原始字节，在临时目录中建立副本运行，结束后清理；覆盖率数据仍写入本轮目录 |
| cmd 多次解析损坏参数或无法启动批处理 | 使用临时 ASCII 批处理入口、Unicode 环境变量传值，关闭 AutoRun 和延迟扩展；保留字面 `%NAME%`、`!`、中文、空格和反斜杠 |
| Windows 环境变量原始拼写为 `Path` 时找不到 Maven | 采用 Windows 大小写不敏感的 `System.getenv("PATH")` 查询，避免对环境 Map 的大小写敏感查询 |
| 英文 javac 构造器参数不匹配被归入通用错误 | 补充 `cannot be applied to given types` 和构造器诊断映射，并让集成用例明确执行英文诊断 |
| Windows JavaParser 输出 CRLF 导致写入证明失配 | 准备最终内容时统一到 IDEA 的 LF 文本，再编译、运行、核验和写入；磁盘并发锁继续使用原始字节指纹 |
| 语义指纹受到 CRLF/LF 影响 | 对 AST 格式化结果统一换行，仍检测实际行为变化 |
| 测试夹具直接传递中文 Java 命令参数 | 编译、运行与进程树用例使用相同的安全启动边界；测试基础设施实际执行 Windows 分支 |
| 部分取消、超时测试曾在 Windows 提前返回 | 增加原生 Windows 父子进程夹具与 PowerShell 执行分支，实际验证编译、版本探测、JUnit 取消和超时；不再将提前返回当作原生覆盖 |
| IDEA 222 的平铺测试启动器抢先加载旧 ASM，无法解析 Java 21/25 字节码 | 在测试启动器前置与安装包一致的 ASM；另用真实插件类加载器读取安装包依赖，独立验证新字节码解析，保留真实 JDK 编译、执行和覆盖率测试 |
| Maven 离线导出缺少 `help` 前缀索引，构建夹具依赖未预缓存 | CI 先准备 Maven Help 插件与前缀元数据，允许公开合成夹具解析构建依赖；产品仍遵从项目的离线配置 |
| 开源批量扫描只准备一个仓库，12 个方法达不到原有门槛 | 在 Windows 编译三个固定版本仓库，保留不少于 30 方法的断言；报告按实际仓库数和未通过目标数统计，输出进入原生验收诊断包 |
| Maven 可选依赖缺少配置文件 | 补齐 optional dependency 的 config-file 与对应描述文件 |

## 证据

验收分支：`codex/windows-native-1.0.50-20261023`。版本号以包内 1.0.51 为准，分支保留最初验收名称。

- 首轮完整 Windows 回归：715 项，22 项失败；记录于 `build/reports/windows-native-acceptance/job-120390071670.log`，用于确定后续修复清单。
- 核心专项已在后续轮次达到 19/19 通过；最终状态、完整计数、源代码提交与包指纹在最终验收完成后补入本节。
- 扩大后的专项首轮执行 157 项，7 项失败；完整 XML 诊断用于区分产品问题、上游构建工具行为和测试夹具问题，未跳过失败用例。
- 后续专项达到 166/166 通过；完整 Windows 回归曾有 721 项中仅开源扫描准备门槛失败、其余 720 项通过的轮次。补齐夹具后整轮重跑，最终 721/721 通过。
- 相同产品源码的本机完整回归 721/721 通过；本机计数不用于代替 Windows 及可选 JDK 的真实运行证据。
- 本机及最终 Windows 安装包的三个代表 IDE API 检查均给出 Compatible；存在弃用或内部 API 提示，Maven 可选依赖描述文件缺失的配置缺陷已经修复。

所有过程记录保存在 `build/reports/windows-native-acceptance/`。
