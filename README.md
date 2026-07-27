# UnitMind

UnitMind 是一个 IntelliJ IDEA 插件，用于在 Java 项目中分析当前光标所在方法，并生成 JUnit 5 + Mockito 单元测试草稿。插件提供编辑器右键入口和右侧 Tool Window，按步骤展示方法概要、参数约束、测试场景、Mock 策略、测试代码和写入测试文件。

## 功能概览

- 在 Java 文件中右键选择 `UnitMind：生成单元测试`，自动打开右侧 `UnitMind` 面板。
- 自动定位当前光标所在方法，优先分析该方法；无法定位时退回到文件中的 public 方法或首个方法。
- 方法概要和参数约束只使用 JavaParser 静态分析，不调用大模型，不发 HTTP 请求。
- 测试场景、Mock 策略和测试代码在后续步骤按需生成；当前测试场景由规则规划，大模型主要增强 Mock 策略和测试代码。
- 生成的测试代码可在插件面板中编辑、复制、再次生成，并写入项目测试目录。
- 写入已有测试类时支持追加、覆盖，并用 UnitMind region 管理同一被测方法的旧生成代码。

## 技术栈

- IntelliJ Platform Plugin SDK
- Gradle Kotlin DSL
- Java 17
- JavaParser 3.25.10
- OkHttp 4.9.0
- org.json
- JUnit 5 + Mockito 代码生成模板

目标 IDE 平台配置在 [build.gradle.kts](/mnt/e/weiwei/AIGroup/GenerateTestCases/Generate/build.gradle.kts)：

- IntelliJ IDEA Community `2022.2.5`
- 插件兼容范围 `222` 到 `253.*`
- 插件名称 `UnitMind`

## 项目结构

```text
src/main/java/com/casc/unittestagent
├── action       # 编辑器右键动作
├── generator    # 测试场景、Mock 策略、测试代码生成
├── llm          # 大模型配置、Prompt、HTTP 客户端、响应解析
├── model        # 方法、参数、约束、场景、Mock、生成结果等数据模型
├── parser       # JavaParser 静态分析
├── service      # 生成流程编排
├── toolwindow   # UnitMind 右侧工具窗口
├── ui           # 设置弹窗、写入弹窗、步骤页和组件
├── util         # PSI 与字符串工具
└── writer       # 测试文件落盘与合并
```

插件入口注册在 [plugin.xml](/mnt/e/weiwei/AIGroup/GenerateTestCases/Generate/src/main/resources/META-INF/plugin.xml)，包含：

- Tool Window：`UnitMind`
- 编辑器右键菜单：`UnitMind：生成单元测试`
- 编辑器右键菜单：`UnitMind：测试数据生成配置`

## 核心流程

### 1. 右键入口

[GenerateUnitTestMvpAction.java](/mnt/e/weiwei/AIGroup/GenerateTestCases/Generate/src/main/java/com/casc/unittestagent/action/GenerateUnitTestMvpAction.java) 负责：

- 校验当前项目、编辑器和 PSI 文件。
- 只在 Java 文件中展示菜单。
- 打开右侧 `UnitMind` Tool Window。
- 将当前 `Editor` 和 `PsiFile` 传给面板，并立即触发静态分析。

### 2. Tool Window 状态编排

[UnitMindToolWindowPanel.java](/mnt/e/weiwei/AIGroup/GenerateTestCases/Generate/src/main/java/com/casc/unittestagent/toolwindow/UnitMindToolWindowPanel.java) 是主 UI 编排类，负责：

- 保存当前编辑器上下文。
- 管理六个步骤的状态。
- 触发后台分析任务。
- 切换步骤。
- 写入测试文件。

六个步骤为：

1. 方法概要
2. 参数约束
3. 测试场景
4. Mock 策略
5. 测试代码
6. 写入测试文件

线程模型：

- 右键自动分析和“重新分析”使用 `Task.Backgroundable` 后台任务。
- 后台任务完成后再回到 UI 线程刷新面板。
- 避免在 Swing EDT 上执行 JavaParser、文件分析、网络请求或大模型调用。

## 生成阶段划分

当前实现刻意把“静态分析”和“后续生成”拆成两个阶段，避免打开面板或重新分析时卡住 IDE。

### 阶段一：静态分析

入口方法：

- `UnitTestGenerationService.analyzeMethod(...)`

职责：

- 读取当前 Java 源码文本。
- 通过 PSI 获取当前光标所在方法名。
- 使用 JavaParser 解析源码。
- 构建 `MethodModel`。
- 提取方法概要、参数、返回值、throws、import、方法体、约束、分支、依赖调用等信息。
- 使用 `TestDataGenerator` 为参数补充候选测试数据。

相关实现：

- [UnitTestGenerationService.java](/mnt/e/weiwei/AIGroup/GenerateTestCases/Generate/src/main/java/com/casc/unittestagent/service/UnitTestGenerationService.java)
- [JavaSourceAnalyzer.java](/mnt/e/weiwei/AIGroup/GenerateTestCases/Generate/src/main/java/com/casc/unittestagent/parser/JavaSourceAnalyzer.java)
- [ConstraintExtractor.java](/mnt/e/weiwei/AIGroup/GenerateTestCases/Generate/src/main/java/com/casc/unittestagent/parser/ConstraintExtractor.java)
- [DependencyExtractor.java](/mnt/e/weiwei/AIGroup/GenerateTestCases/Generate/src/main/java/com/casc/unittestagent/parser/DependencyExtractor.java)

### 阶段二：场景、Mock 和测试代码生成

入口方法：

- `UnitTestGenerationService.generateArtifacts(...)`

触发时机：

- 用户进入 `测试场景` 及后续步骤时才按需触发。

职责：

- 根据 `MethodModel` 使用规则规划测试场景。
- 根据依赖和场景使用规则规划 Mock 策略。
- 先生成一份规则兜底测试代码。
- 如果启用了大模型，则把静态分析上下文、规则场景和规则 Mock 信息发送给大模型，生成更完整的 Mock 策略和测试代码。
- 如果大模型失败，根据配置决定是否回退到规则生成代码。

明确边界：

- 允许调用大模型，但当前大模型响应只解析 `mockStrategies`、`testCode` 和 `warnings`。
- 所有网络请求必须在后台线程中执行。
- 不阻塞方法概要和参数约束展示。

相关实现：

- [TestScenarioPlanner.java](/mnt/e/weiwei/AIGroup/GenerateTestCases/Generate/src/main/java/com/casc/unittestagent/generator/TestScenarioPlanner.java)
- [MockStrategyPlanner.java](/mnt/e/weiwei/AIGroup/GenerateTestCases/Generate/src/main/java/com/casc/unittestagent/generator/MockStrategyPlanner.java)
- [JUnitMockitoTestGenerator.java](/mnt/e/weiwei/AIGroup/GenerateTestCases/Generate/src/main/java/com/casc/unittestagent/generator/JUnitMockitoTestGenerator.java)
- [OpenAiCompatibleLlmClient.java](/mnt/e/weiwei/AIGroup/GenerateTestCases/Generate/src/main/java/com/casc/unittestagent/llm/OpenAiCompatibleLlmClient.java)

## 静态分析方案

静态分析基于 JavaParser，语言级别配置为 Java 17。分析结果统一写入 `MethodModel`，供 UI 展示和后续生成使用。

主要提取内容：

- 包名、类名、方法名、返回类型。
- 方法参数及参数类型。
- throws 异常类型。
- import 列表。
- 方法体文本。
- 类中声明的方法名。
- 参数约束和分支条件。
- 字段依赖、静态调用、本类内部调用、局部对象调用。
- 当前类中对被测方法的调用点。

参数约束主要来自 if 条件、throw 分支、边界判断和空值判断。方法概要和参数约束阶段只依赖这些本地分析结果。

## 大模型方案

大模型模块位于 `llm` 包，采用 OpenAI-compatible Chat Completions 接口。

配置项：

- `enabled`：是否启用大模型。
- `apiUrl`：接口地址。
- `apiKey`：API Key。
- `model`：模型名称，默认 `gpt-4o-mini`。
- `timeoutSeconds`：超时时间，默认 60 秒。
- `maxTokens`：最大输出 token，默认 6000。
- `temperature`：采样温度，默认 0.2。
- `fallbackToRuleBased`：大模型失败时是否回退到规则生成。

API Key 读取顺序：

1. 环境变量 `UNITMIND_API_KEY`
2. IntelliJ `PasswordSafe`

普通配置保存到 IntelliJ `PropertiesComponent`，API Key 不写入普通配置项。

请求内容由 [LlmPromptBuilder.java](/mnt/e/weiwei/AIGroup/GenerateTestCases/Generate/src/main/java/com/casc/unittestagent/llm/LlmPromptBuilder.java) 构建，核心上下文包括：

- 包名、类名、方法名、方法签名、返回类型。
- 被测方法源码。
- import、类字段、参数列表。
- 参数约束、分支、依赖、调用点。
- 测试场景。
- 测试框架和 Mock 框架。
- 用户补充要求。

响应解析由 [LlmResponseParser.java](/mnt/e/weiwei/AIGroup/GenerateTestCases/Generate/src/main/java/com/casc/unittestagent/llm/LlmResponseParser.java) 完成，期望大模型返回结构化 JSON，主要字段为：

```json
{
  "mockStrategies": [],
  "testCode": "...",
  "warnings": []
}
```

其中 `testCode` 不能为空，否则视为解析失败。

## 测试文件写入方案

写入逻辑位于 [TestFileWriter.java](/mnt/e/weiwei/AIGroup/GenerateTestCases/Generate/src/main/java/com/casc/unittestagent/writer/TestFileWriter.java)。

目标路径推导规则：

- 如果源文件位于 `src/main/java`，测试文件写入对应的 `src/test/java` 包路径。
- 如果不是标准 Maven/Gradle 目录，则写入源文件同级的 `generated-test` 目录。

写入模式：

- 新建测试文件。
- 覆盖已有测试文件。
- 追加到已有测试文件。

追加时由 [TestClassMerger.java](/mnt/e/weiwei/AIGroup/GenerateTestCases/Generate/src/main/java/com/casc/unittestagent/writer/TestClassMerger.java) 合并：

- 合并 import。
- 合并字段。
- 将本次生成内容包裹在 `UnitMind generated tests for <method>` region 中。
- 同一方法已存在旧 region 时，可选择替换旧 region。

写入动作通过 IntelliJ `WriteCommandAction` 和 VFS API 执行，保证 IDE 文件视图和文档模型同步。

## 构建和运行

编译：

```bash
./gradlew compileJava
```

打包插件：

```bash
./gradlew buildPlugin
```

插件包输出路径：

```text
build/distributions/UnitMind-1.0.0.zip
```

本地安装：

1. 打开 IntelliJ IDEA。
2. 进入 `Settings | Plugins`。
3. 点击齿轮菜单。
4. 选择 `Install Plugin from Disk...`。
5. 选择 `build/distributions/UnitMind-1.0.0.zip`。
6. 重启 IDE。

开发运行：

```bash
./gradlew runIde
```

## 使用方式

1. 在 IDEA 中打开 Java 项目。
2. 打开一个 Java 文件。
3. 将光标放到需要生成测试的方法内部。
4. 右键选择 `UnitMind：生成单元测试`。
5. 右侧 `UnitMind` 面板会自动展示方法概要和参数约束。
6. 点击下一步进入测试场景，插件开始按需生成场景、Mock 策略和测试代码。
7. 在测试代码页检查或编辑生成结果。
8. 在写入步骤选择写入测试文件。

## 大模型配置

在 UnitMind 右侧面板点击 `设置`，配置：

- 是否启用大模型。
- API 地址。
- 模型名称。
- API Key。
- 超时时间、max tokens、temperature。
- 大模型失败时是否允许规则兜底。

也可以通过环境变量提供 API Key：

```bash
export UNITMIND_API_KEY=your-api-key
```

启用大模型后，UnitMind 会在进入后续生成阶段时，把当前方法源码、静态分析结果、规则场景和规则 Mock 上下文发送到配置的大模型接口，用于增强 Mock 策略和测试代码。方法概要和参数约束阶段不会发送这些数据。

## 设计原则

- 快速反馈：右键打开后只做本地静态分析，尽快展示方法概要。
- 明确边界：概要和参数约束不调用大模型；进入场景及后续步骤后才允许调用大模型。
- UI 不阻塞：耗时工作放到后台任务，避免 Swing EDT freeze。
- 可恢复：大模型失败时可回退到规则生成，避免测试代码页空白。
- 可编辑：生成的测试代码在写入前可人工修改。
- 可合并：写入已有测试类时尽量追加并保留用户已有代码。

## 注意事项

- 当前静态分析语言级别为 Java 17；如果源码使用更高版本语法，JavaParser 可能解析失败。
- 大模型接口必须兼容 OpenAI Chat Completions 响应格式。
- 如果未启用大模型，测试代码由规则生成器兜底，复杂业务断言仍需要人工补充。
- 写入已有测试类前建议先查看预览，确认追加或覆盖策略符合预期。
