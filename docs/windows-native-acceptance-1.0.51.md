# Windows 原生验收记录（1.0.51）

状态：验收进行中，未交付最终安装包。

## 环境与门禁

- 使用 GitHub Actions 的真实 Windows x64 主机，源码检出目录包含中文和空格。
- IDEA 222 测试启动器在英语 Windows 的 ANSI 启动参数中会损坏中文路径，CI 将编译产物和 IDE 测试沙箱放在单独的 ASCII 目录，保留中文源码目录；功能用例另行实际创建中文项目、类路径和输出目录。
- Windows 核心专项包含原生 cmd、真实 javac/JVM、40 KB classpath、Java 8 参数文件、Mockito/JUnit/JaCoCo、多种编码与运行配置。
- 项目运行时验证 JDK 8、11、17、21、25；8/11/21/25 分别使用对应 JDK 编译对应字节码，并实际运行 JUnit、Mockito 和 JaCoCo；插件自身仍以 Java 17 字节码构建。
- 扩大后的 Windows 专项、完整测试、安装包构建及 IDEA API 兼容检查必须全部成功，才上传 `windows-verified-plugin` 安装包。
- API 检查选取 IDEA 2022.2.5、2025.3、2026.2 三档代表版本。API 检查与 IDEA 222 中的功能测试分别记录，不能等同于所有版本的人工 GUI 验收。
- 开源流水线使用固定 tag 的 Apache Commons Codec 原始源码，在 Windows 原生编译后验证；它不代表全部开源矩阵都已在 Windows 执行。
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
| Maven 可选依赖缺少配置文件 | 补齐 optional dependency 的 config-file 与对应描述文件 |

## 证据

验收分支：`codex/windows-native-1.0.50-20261023`。版本号以包内 1.0.51 为准，分支保留最初验收名称。

- 首轮完整 Windows 回归：715 项，22 项失败；记录于 `build/reports/windows-native-acceptance/job-120390071670.log`，用于确定后续修复清单。
- 核心专项已在后续轮次达到 19/19 通过；最终状态、完整计数、源代码提交与包指纹在最终验收完成后补入本节。
- 扩大后的专项首轮执行 157 项，7 项失败；完整 XML 诊断用于区分产品问题、上游构建工具行为和测试夹具问题，未跳过失败用例。
- 本机 API 检查在三个代表 IDE 中均给出 Compatible；存在弃用或内部 API 提示，另有一处配置缺陷已经修复。最终交付还需要使用最终安装包重新检查。

所有过程记录保存在 `build/reports/windows-native-acceptance/`。
