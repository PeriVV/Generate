# 智能单元测试用例生成智能体 Java MVP 需求说明

## 1. 背景与目标

当前项目是一个 IntelliJ IDEA 插件项目，使用 Gradle Kotlin DSL 构建，目标 IDEA 版本为 2022.2.5。插件已有或计划使用的技术栈如下：

- IntelliJ Platform Plugin SDK
- Java / Kotlin Gradle 插件，但源码统一使用 Java
- JavaParser：用于 Java 源码 AST 解析
- IntelliJ PSI：用于 IDE 内部语法树定位、文件读取、包名/类名识别和写文件
- OkHttp：用于后续调用大模型 HTTP 接口
- Swing / IntelliJ DialogWrapper：用于插件弹窗 UI

本次只实现 **Java 语言首期最小可运行版本**，目标不是一次性完成完整智能体平台，而是先打通以下闭环：

```text
在 IDEA 中选择 Java 类或方法
        ↓
插件读取源码
        ↓
JavaParser 解析 AST
        ↓
抽取方法签名、参数约束、异常分支、依赖调用
        ↓
生成结构化中间模型
        ↓
生成测试场景和 Mock 策略
        ↓
生成 JUnit5 + Mockito 测试代码
        ↓
在弹窗中预览
        ↓
用户确认后写入 src/test/java 下的测试文件
```

本需求文件的目标是让 Codex 可以直接按照本文进行 Java 代码实现。

---

## 2. 本期范围

### 2.1 必须实现

本期只实现 Java 项目的方法级或类级单元测试生成 MVP。

必须支持：

1. 在 IDEA 编辑器中右键当前 Java 文件、类或方法，出现菜单项：

```text
Generate Unit Test MVP
```

2. 点击菜单后，插件自动获取当前 Java 文件源码。

3. 如果光标位于某个方法内部，优先分析该方法；如果无法定位方法，则分析当前类中的第一个 public 方法。

4. 使用 JavaParser 解析源码，生成统一中间模型 `MethodModel`。

5. 从 AST 中抽取以下信息：

   - 包名
   - 类名
   - 方法名
   - 方法参数名称和类型
   - 返回类型
   - if 条件
   - if 条件中直接 throw 的异常路径
   - return 语句
   - 方法调用表达式
   - 字段依赖调用，例如 `taskMapper.selectById(...)`

6. 实现参数约束抽取规则：

   - 识别 `param == null`
   - 识别 `param != null`
   - 识别数值比较：`<`、`<=`、`>`、`>=`
   - 识别参数间关系，例如 `start >= end`
   - 识别异常块取反规则：如果 `if (condition) { throw ... }`，则：
     - `condition` 是异常输入条件
     - `not(condition)` 是正常输入条件

7. 实现默认测试数据生成配置与随机生成机制：

   默认测试数据不要固定写死为 `1`、`0`、`-1` 这类常量，而应基于 Java 基本类型和常见包装类型的默认取值范围进行随机生成。MVP 阶段需要提供一个 Swing 配置窗口，允许用户配置不同类型的随机生成范围。

   1. 新增“测试数据生成配置”窗口，窗口中以表格形式展示基本类型配置项，表格至少包含以下列：

      | 类型 | 最小值 | 最大值 | 小数位数 | 字符串规则 |
      |---|---:|---:|---:|---|
      | `byte` / `Byte` | `-128` | `127` | `-` | `-` |
      | `short` / `Short` | `-32768` | `32767` | `-` | `-` |
      | `int` / `Integer` | `-100` | `100` | `-` | `-` |
      | `long` / `Long` | `-1000` | `1000` | `-` | `-` |
      | `float` / `Float` | `-100.0` | `100.0` | `2` | `-` |
      | `double` / `Double` | `-100.0` | `100.0` | `2` | `-` |
      | `String` | `-` | `-` | `-` | 默认 `[A-Za-z0-9_]{6,12}` |

   2. 表格交互要求：

      * “类型”列只读；
      * “最小值”和“最大值”列允许用户编辑；
      * “小数位数”列只对 `float` / `Float` 和 `double` / `Double` 生效；
      * 整数类型的小数位数列显示为 `-`，不可编辑；
      * 用户点击“确定”后保存配置；
      * 用户点击“取消”后不保存修改；
      * 用户点击“恢复默认”后恢复系统默认配置；
      * 如果用户输入的最小值大于最大值，需要弹窗提示错误，不允许保存；
      * 如果用户输入的内容不是合法数字，需要弹窗提示错误，不允许保存。

   3. 随机数据生成规则：

      * `int` / `Integer`：在配置的最小值和最大值之间随机生成整数；
      * `long` / `Long`：在配置的最小值和最大值之间随机生成长整数，生成到测试代码中时追加 `L`；
      * `double` / `Double`：在配置的最小值和最大值之间随机生成小数，并按照配置的小数位数保留；
      * `float` / `Float`：在配置的最小值和最大值之间随机生成小数，并按照配置的小数位数保留，生成到测试代码中时追加 `f`；
      * `short` / `Short`：在配置的最小值和最大值之间随机生成整数；
      * `byte` / `Byte`：在配置的最小值和最大值之间随机生成整数；
      * `boolean` / `Boolean`：随机生成 `true` 或 `false`。
      * `String`：用正则表达式生成ASCII码的随机组合；

   4. 其他类型暂按简单规则处理：
      * `List` / `Collection`：生成 `List.of("test_" + 随机数字)`；
      * 其他对象类型：优先尝试生成 `new Type()`；
      * 如果对象类型无法通过无参构造函数创建，则使用 `null`，并在生成的测试代码中添加 `TODO` 注释，提示用户需要手动补充测试对象。

   5. 建议新增配置模型类：

      ```java
      public class TestDataRangeConfig {
          private String typeName;
          private String minValue;
          private String maxValue;
          private Integer decimalScale;
      }
      ```

   6. 建议新增配置管理类：

      ```java
      public class TestDataRangeConfigManager {
          public static List<TestDataRangeConfig> getConfigs();
          public static void updateConfigs(List<TestDataRangeConfig> configs);
          public static void resetToDefault();
          public static Optional<TestDataRangeConfig> findByType(String javaType);
      }
      ```

   7. 建议新增随机测试数据生成类：

      ```java
      public class RandomTestDataGenerator {

          public String generateValueExpression(String javaType) {
              // 根据 javaType 和用户配置，返回可以直接写入测试代码的 Java 表达式
          }
      }
      ```

   8. 生成结果示例：

      ```java
      int age = 37;
      Integer count = 12;
      long id = 582L;
      Long userId = 321L;
      double amount = 56.78;
      Double ratio = 0.35;
      float score = 88.50f;
      boolean enabled = true;
      Boolean deleted = false;
      String name = "test_123";
      List<String> names = List.of("test_456");
      User user = new User();
      ```

   9. MVP 阶段配置可以先保存在内存中，保证插件运行期间有效即可。后续版本再接入 IntelliJ 的 `PersistentStateComponent` 实现持久化保存。


8. 生成测试场景：

   - 正常路径场景
   - 每个异常 throw 分支生成一个异常场景
   - 每个明显边界条件生成一个边界场景

9. 生成 Mock 策略：

   - 识别当前类字段依赖
   - 识别带有以下注解的字段：
     - `@Autowired`
     - `@Resource`
     - `@Inject`
   - 识别字段调用，例如 `taskMapper.insert(...)`
   - 为依赖字段生成 `@Mock`
   - 为被测类生成 `@InjectMocks`
   - 对异常分支中不应调用的依赖生成 `verify(mock, never())`
   - 对正常路径中出现的依赖调用生成 `verify(mock, times(1))`

10. 生成 JUnit5 + Mockito 测试代码，基本结构如下：

```java
@ExtendWith(MockitoExtension.class)
class XxxTest {

    @Mock
    private SomeDependency someDependency;

    @InjectMocks
    private Xxx xxx;

    @Test
    void shouldThrowWhenStartGreaterThanOrEqualEnd() {
        assertThrows(IllegalArgumentException.class, () -> {
            xxx.method(2, 1);
        });
    }

    @Test
    void shouldReturnNormallyWhenInputValid() {
        xxx.method(1, 2);
        verify(someDependency, times(1)).someCall(any());
    }
}
```

11. 使用 Swing 或 IntelliJ `DialogWrapper` 展示生成结果。

弹窗至少包含以下内容：

- 被测方法摘要
- 参数约束
- 测试场景
- Mock 策略
- 生成的测试代码

12. 弹窗提供两个按钮：

- `Copy`：复制生成的测试代码到剪贴板
- `Write Test File`：写入测试文件

13. 写入路径规则：

```text
src/main/java/com/example/service/TaskService.java
        ↓
src/test/java/com/example/service/TaskServiceTest.java
```

如果 `src/test/java` 不存在，需要自动创建目录。

---

## 3. 本期不做

以下内容不在 MVP 范围内，不要在本期实现复杂逻辑：

1. 不做多语言支持。
2. 不做 Python、C/C++、TypeScript、Go、C#。
3. 不做完整知识库。
4. 不做需求文档检索。
5. 不做历史缺陷库。
6. 不做覆盖率统计。
7. 不做 JaCoCo 集成。
8. 不做自动执行 Gradle 测试。
9. 不做自动修复闭环。
10. 不强制调用大模型。
11. 不做复杂跨文件符号解析。
12. 不做复杂 Spring 上下文启动测试。
13. 不生成 `@SpringBootTest`。
14. 不生成 MockMvc 测试。
15. 不要求所有真实项目生成的测试 100% 可编译。

本期目标是 **插件能运行、能分析 Java 方法、能生成可读的 JUnit5 + Mockito 测试代码草稿**。

---

## 4. 推荐包结构

请按以下包结构实现。把现有项目调整为如下结构。

```text
src/main/java/
  com/casc/unittestagent/
    action/
      GenerateUnitTestMvpAction.java
    service/
      UnitTestGenerationService.java
    parser/
      JavaSourceAnalyzer.java
      ConstraintExtractor.java
      DependencyExtractor.java
    model/
      ProjectProfile.java
      MethodModel.java
      ParameterModel.java
      ConstraintModel.java
      BranchModel.java
      DependencyModel.java
      TestScenario.java
      MockStrategy.java
      GeneratedTestFile.java
      GenerationResult.java
    generator/
      TestDataGenerator.java
      TestScenarioPlanner.java
      MockStrategyPlanner.java
      JUnitMockitoTestGenerator.java
    writer/
      TestFileWriter.java
    ui/
      GenerationPreviewDialog.java
    llm/
      LlmClient.java
      StubLlmClient.java
      OkHttpLlmClient.java
    util/
      PsiUtils.java
      StringUtils.java
```


---

## 5. 核心数据模型

所有模型优先使用普通 Java class，不使用 Lombok，不使用 record，避免兼容性问题。

### 5.1 MethodModel

```java
public class MethodModel {
    private String language;
    private String packageName;
    private String className;
    private String methodName;
    private String returnType;
    private List<ParameterModel> parameters;
    private List<ConstraintModel> constraints;
    private List<BranchModel> branches;
    private List<DependencyModel> dependencies;
}
```

### 5.2 ParameterModel

```java
public class ParameterModel {
    private String name;
    private String type;
    private boolean nullable;
    private List<String> normalValues;
    private List<String> boundaryValues;
    private List<String> invalidValues;
}
```

### 5.3 ConstraintModel

```java
public class ConstraintModel {
    private String parameterName;
    private String expression;
    private String safeExpression;
    private String kind;
    private String source;
    private double confidence;
}
```

`kind` 建议使用字符串即可，先不用 enum，便于后续扩展。取值包括：

```text
TYPE
NULL_CHECK
NOT_NULL_CHECK
RANGE
RELATION
EXCEPTION_GUARD
DEFAULT_FALLBACK
```

### 5.4 BranchModel

```java
public class BranchModel {
    private String condition;
    private String safeCondition;
    private String action;
    private String exceptionType;
}
```

### 5.5 DependencyModel

```java
public class DependencyModel {
    private String fieldName;
    private String fieldType;
    private String dependencyType;
    private List<String> calledMethods;
}
```

`dependencyType` 可先简单判断：

```text
DATABASE
REMOTE_CLIENT
CACHE
FILE
PERMISSION
UNKNOWN
```

判断规则：

- 名称包含 `mapper`、`repository`、`dao`：`DATABASE`
- 名称包含 `client`、`api`、`rest`、`feign`：`REMOTE_CLIENT`
- 名称包含 `redis`、`cache`：`CACHE`
- 名称包含 `file`、`storage`：`FILE`
- 名称包含 `permission`、`auth`、`userContext`：`PERMISSION`
- 其他：`UNKNOWN`

### 5.6 TestScenario

```java
public class TestScenario {
    private String name;
    private String type;
    private String description;
    private List<String> inputExpressions;
    private List<String> expectedAssertions;
    private List<String> coveredBranches;
}
```

`type` 取值：

```text
NORMAL
BOUNDARY
EXCEPTION
```

### 5.7 MockStrategy

```java
public class MockStrategy {
    private List<String> mockFields;
    private List<String> whenRules;
    private List<String> verifyRules;
    private List<String> neverRules;
}
```

### 5.8 GenerationResult

```java
public class GenerationResult {
    private MethodModel methodModel;
    private List<TestScenario> scenarios;
    private MockStrategy mockStrategy;
    private GeneratedTestFile generatedTestFile;
    private List<String> warnings;
}
```

---

## 6. IntelliJ Action 实现要求

### 6.1 GenerateUnitTestMvpAction

创建一个 `GenerateUnitTestMvpAction`，继承：

```java
com.intellij.openapi.actionSystem.AnAction
```

基本逻辑：

```text
actionPerformed
  ↓
获取 Project
  ↓
获取 Editor
  ↓
获取 PsiFile
  ↓
确认 PsiFile 是 Java 文件
  ↓
读取源码文本
  ↓
调用 UnitTestGenerationService.generate(project, psiFile, editor)
  ↓
打开 GenerationPreviewDialog
```

如果当前文件不是 Java 文件，弹窗提示：

```text
Please open a Java file before generating unit tests.
```

### 6.2 plugin.xml 注册

在 `plugin.xml` 中注册 action。

菜单位置建议放到编辑器右键菜单：

```xml
<actions>
    <action id="UnitTestAgent.GenerateUnitTestMvp"
            class="com.example.unittestagent.action.GenerateUnitTestMvpAction"
            text="Generate Unit Test MVP"
            description="Generate JUnit5 Mockito unit test draft for current Java method">
        <add-to-group group-id="EditorPopupMenu" anchor="last"/>
    </action>
</actions>
```

如果现有项目已有 actions 配置，请合并，不要覆盖其他配置。

---

## 7. PSI 使用要求

PSI 在本期主要用于：

1. 获取当前文件内容。
2. 获取当前光标所在方法。
3. 获取文件路径和包路径。
4. 写入测试文件后刷新项目视图。

实现工具类：

```java
public class PsiUtils {
    public static String getSourceText(PsiFile psiFile);
    public static String getCurrentMethodName(PsiFile psiFile, Editor editor);
    public static String getPackageName(PsiFile psiFile);
    public static VirtualFile getVirtualFile(PsiFile psiFile);
}
```

如果 `getCurrentMethodName` 失败，返回 `null`，后续由 JavaParser 选择第一个 public 方法。

---

## 8. JavaParser 分析要求

### 8.1 JavaSourceAnalyzer

实现入口：

```java
public MethodModel analyze(String sourceText, String preferredMethodName);
```

逻辑：

1. 使用 `StaticJavaParser.parse(sourceText)` 解析为 `CompilationUnit`。
2. 获取 package。
3. 获取第一个 class 或当前文件主类。
4. 如果 `preferredMethodName` 不为空，查找同名方法。
5. 如果找不到，选择第一个 public 方法。
6. 如果仍找不到，选择第一个方法。
7. 提取方法签名、返回类型、参数。
8. 调用 `ConstraintExtractor`。
9. 调用 `DependencyExtractor`。
10. 返回 `MethodModel`。

### 8.2 ConstraintExtractor

实现入口：

```java
public List<ConstraintModel> extract(MethodDeclaration methodDeclaration);
public List<BranchModel> extractBranches(MethodDeclaration methodDeclaration);
```

必须支持：

1. 遍历 `IfStmt`。
2. 获取 `condition.toString()`。
3. 如果 then 分支内存在 `ThrowStmt`：
   - `BranchModel.condition = condition.toString()`
   - `BranchModel.safeCondition = negate(condition)`
   - `BranchModel.action = "throw"`
   - `BranchModel.exceptionType = 异常类型字符串`
4. 对条件中的 `BinaryExpr` 进行规则识别：
   - `param == null`
   - `param != null`
   - `param < literal`
   - `param <= literal`
   - `param > literal`
   - `param >= literal`
   - `param1 >= param2`
   - `param1 < param2`
5. 生成 `ConstraintModel`。

### 8.3 negate 简化实现

先实现常见条件取反，不做完整布尔代数。

规则：

```text
a == null      -> a != null
a != null      -> a == null
a >= b         -> a < b
a > b          -> a <= b
a <= b         -> a > b
a < b          -> a >= b
a && b         -> !(a && b)
a || b         -> !(a || b)
其他            -> !(原表达式)
```

---

## 9. 依赖识别要求

### 9.1 DependencyExtractor

实现入口：

```java
public List<DependencyModel> extract(ClassOrInterfaceDeclaration classDeclaration, MethodDeclaration methodDeclaration);
```

逻辑：

1. 扫描类字段。
2. 找出字段名称和字段类型。
3. 判断字段是否是依赖：
   - 有 `@Autowired`
   - 有 `@Resource`
   - 有 `@Inject`
   - 字段名符合 mapper、repository、dao、client、redis、cache、file、permission 等模式
4. 扫描当前方法中的 `MethodCallExpr`。
5. 如果方法调用形如：

```java
taskMapper.selectById(id)
```

则将 `taskMapper` 识别为依赖字段，记录调用方法 `selectById`。

6. 每个依赖只生成一个 `DependencyModel`，`calledMethods` 去重。

---

## 10. 测试数据生成要求

### 10.1 TestDataGenerator

实现入口：

```java
public void enrich(MethodModel methodModel);
```

对每个参数填充：

```java
normalValues
boundaryValues
invalidValues
```

示例：

```java
int start:
  normalValues: ["1"]
  boundaryValues: ["0"]
  invalidValues: ["-1"]

String name:
  normalValues: ["\"test\""]
  boundaryValues: ["\"\""]
  invalidValues: ["null"]
```

### 10.2 参数间关系处理

如果存在 `start >= end` 异常条件，则异常输入可生成：

```text
start = 2
end = 1
```

正常输入可生成：

```text
start = 1
end = 2
```

本期只需要支持双参数数值关系。

---

## 11. 测试场景生成要求

### 11.1 TestScenarioPlanner

实现入口：

```java
public List<TestScenario> plan(MethodModel methodModel);
```

生成规则：

1. 默认生成一个正常场景：

```text
name: shouldReturnNormallyWhenInputValid
type: NORMAL
description: Valid input should execute the method normally.
```

2. 每个 `BranchModel` 如果 `action = throw`，生成一个异常场景：

```text
name: shouldThrowWhen<ConditionDescription>
type: EXCEPTION
description: Invalid input should trigger <exceptionType>.
```

3. 如果存在边界值，生成一个边界场景：

```text
name: shouldHandleBoundaryValues
type: BOUNDARY
description: Boundary input should be handled.
```

方法名生成要做简单清洗：

- 去掉空格
- 去掉括号
- `>=` 转成 `GreaterThanOrEqual`
- `>` 转成 `GreaterThan`
- `<=` 转成 `LessThanOrEqual`
- `<` 转成 `LessThan`
- `==` 转成 `Equal`
- `!=` 转成 `NotEqual`
- `null` 转成 `Null`

---

## 12. Mock 策略生成要求

### 12.1 MockStrategyPlanner

实现入口：

```java
public MockStrategy plan(MethodModel methodModel, List<TestScenario> scenarios);
```

规则：

1. `mockFields` 包含所有 `DependencyModel.fieldName`。
2. 正常场景中，对每个依赖调用生成 verify：

```java
verify(taskMapper, times(1)).insert(any());
```

3. 异常场景中，对每个依赖调用生成 never：

```java
verify(taskMapper, never()).insert(any());
```

4. 如果依赖调用有返回值，本期不强制生成精确 `when`，可以根据方法名启发生成：

```text
select/find/get/query -> when(...).thenReturn(null 或 new Object())
save/insert/update/delete -> 不生成 when
```

5. 生成的 Mock 策略要在 UI 中展示，便于用户人工调整。

---

## 13. JUnit5 + Mockito 代码生成要求

### 13.1 JUnitMockitoTestGenerator

实现入口：

```java
public GeneratedTestFile generate(MethodModel methodModel, List<TestScenario> scenarios, MockStrategy mockStrategy);
```

生成内容包括：

1. package 行，与被测类保持一致。
2. imports：

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
```

3. 测试类：

```java
@ExtendWith(MockitoExtension.class)
class XxxTest {
}
```

4. 对每个依赖字段生成：

```java
@Mock
private DependencyType dependencyName;
```

5. 对被测类生成：

```java
@InjectMocks
private Xxx xxx;
```

6. 每个测试场景生成一个 `@Test` 方法。

### 13.2 异常测试生成规则

对于 throw 分支：

```java
@Test
void shouldThrowWhenStartGreaterThanOrEqualEnd() {
    assertThrows(IllegalArgumentException.class, () -> {
        taskService.createTask(2, 1);
    });

    verify(taskMapper, never()).insert(any());
}
```

如果无法识别异常类型，使用：

```java
RuntimeException.class
```

### 13.3 正常测试生成规则

对于非 void 返回方法：

```java
@Test
void shouldReturnNormallyWhenInputValid() {
    Result result = taskService.createTask(1, 2);

    assertNotNull(result);
}
```

对于 void 方法：

```java
@Test
void shouldReturnNormallyWhenInputValid() {
    taskService.createTask(1, 2);

    verify(taskMapper, times(1)).insert(any());
}
```

如果返回类型是基础类型：

```text
int / Integer / long / Long / boolean / Boolean / String
```

使用对应断言：

```java
assertNotNull(result);
```

先不要做复杂业务断言。

### 13.4 构造入参规则

入参生成优先级：

1. 如果场景来自异常条件，使用能触发该异常条件的数据。
2. 如果是正常场景，使用满足 `safeCondition` 的数据。
3. 如果是边界场景，使用 boundaryValues。
4. 如果没有约束，使用默认正常值。

---

## 14. UI 要求

### 14.1 GenerationPreviewDialog

使用 IntelliJ `DialogWrapper` 或 Swing `JDialog` 实现。

推荐使用 `DialogWrapper`。

界面可以简单，不追求美观，但必须可用。

建议布局：

```text
上方：标题和被测方法
中间：JTabbedPane
  - Summary
  - Constraints
  - Scenarios
  - Mock Strategy
  - Test Code
下方：
  - Copy
  - Write Test File
  - Close
```

`Test Code` 使用 `JTextArea` 或 `EditorTextField` 展示，必须可复制。

### 14.2 Copy 按钮

点击后将测试代码复制到系统剪贴板。

### 14.3 Write Test File 按钮

点击后调用 `TestFileWriter` 写入测试文件。

写入成功后提示：

```text
Test file written successfully.
```

失败后提示异常信息。

---

## 15. 写文件要求

### 15.1 TestFileWriter

实现入口：

```java
public void write(Project project, PsiFile sourceFile, GeneratedTestFile generatedTestFile);
```

写入路径：

1. 从源文件路径中找到：

```text
src/main/java
```

2. 替换为：

```text
src/test/java
```

3. 文件名改成：

```text
<SourceClassName>Test.java
```

4. 如果目录不存在，自动创建。

5. 使用 IntelliJ 写操作：

```java
WriteCommandAction.runWriteCommandAction(project, () -> {
    // create or overwrite file
});
```

6. 写入后刷新：

```java
LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
```

如果源文件路径不包含 `src/main/java`，则在当前文件同级目录旁边生成：

```text
generated-test/<ClassName>Test.java
```

---

## 16. LLM 接口要求

本期不强制调用大模型，但要预留接口。

### 16.1 LlmClient

```java
public interface LlmClient {
    String complete(String prompt) throws Exception;
}
```

### 16.2 StubLlmClient

默认实现，不访问网络。

```java
public class StubLlmClient implements LlmClient {
    @Override
    public String complete(String prompt) {
        return "";
    }
}
```

### 16.3 OkHttpLlmClient

可以只实现基础骨架，不接入真实配置。

要求：

- 使用 OkHttp
- 从系统环境变量读取 API 地址和 Key
- 如果没有配置，直接抛出清晰异常
- 本期不要让主流程依赖该类

环境变量名称：

```text
UNIT_TEST_AGENT_API_URL
UNIT_TEST_AGENT_API_KEY
```

---

## 17. 服务编排要求

### 17.1 UnitTestGenerationService

实现入口：

```java
public GenerationResult generate(Project project, PsiFile psiFile, Editor editor);
```

内部流程：

```text
sourceText = PsiUtils.getSourceText(psiFile)
preferredMethodName = PsiUtils.getCurrentMethodName(psiFile, editor)

methodModel = javaSourceAnalyzer.analyze(sourceText, preferredMethodName)
testDataGenerator.enrich(methodModel)
scenarios = testScenarioPlanner.plan(methodModel)
mockStrategy = mockStrategyPlanner.plan(methodModel, scenarios)
generatedTestFile = junitMockitoTestGenerator.generate(methodModel, scenarios, mockStrategy)

return GenerationResult
```

异常处理：

- JavaParser 解析失败：返回错误提示
- 找不到方法：提示 `No method found in current Java file.`
- 生成测试代码失败：提示异常信息

---

## 18. 生成代码风格要求

1. 所有新 Java 文件必须有清晰类名。
2. 不使用 Lombok。
3. 不使用复杂设计模式。
4. 不过度抽象。
5. 每个类控制在合理长度。
6. 关键规则添加注释。
7. 不引入 Spring Boot 运行时依赖。
8. 不引入数据库依赖。
9. 不引入真实大模型调用作为必需流程。
10. 所有新增代码必须能通过 Gradle 编译。

---

## 19. Gradle 依赖要求

如果当前 `build.gradle.kts` 已有 JavaParser、OkHttp、IntelliJ 插件依赖，不要重复添加。

如果缺少 JavaParser，请添加：

```kotlin
implementation("com.github.javaparser:javaparser-core:3.25.8")
```

如果缺少 OkHttp，请添加：

```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")
```

插件生成的是用户项目中的测试代码，因此用户项目需要有 JUnit5 和 Mockito 依赖。但插件自身不需要运行这些测试。

不要把 JUnit5、Mockito 作为插件主流程必需运行依赖，除非现有项目已经使用。

---

## 20. 最小验收样例

请使用下面的 Java 类作为手工验收样例。

### 20.1 输入源码

```java
package com.example.demo;

import org.springframework.beans.factory.annotation.Autowired;

public class TaskService {

    @Autowired
    private TaskMapper taskMapper;

    public void createTask(int start, int end, String name) {
        if (name == null) {
            throw new IllegalArgumentException("name is required");
        }
        if (start >= end) {
            throw new IllegalArgumentException("start must be less than end");
        }
        taskMapper.insert(name);
    }
}
```

### 20.2 期望识别结果

插件应识别：

```text
className = TaskService
methodName = createTask
parameters = start:int, end:int, name:String
dependencies = taskMapper
dependency calledMethods = insert
branches:
  name == null -> throw IllegalArgumentException
  start >= end -> throw IllegalArgumentException
safe conditions:
  name != null
  start < end
```

### 20.3 期望生成测试代码要点

生成的测试代码应至少包含：

```java
@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskMapper taskMapper;

    @InjectMocks
    private TaskService taskService;

    @Test
    void shouldThrowWhenNameEqualNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            taskService.createTask(1, 2, null);
        });

        verify(taskMapper, never()).insert(any());
    }

    @Test
    void shouldThrowWhenStartGreaterThanOrEqualEnd() {
        assertThrows(IllegalArgumentException.class, () -> {
            taskService.createTask(2, 1, "test");
        });

        verify(taskMapper, never()).insert(any());
    }

    @Test
    void shouldReturnNormallyWhenInputValid() {
        taskService.createTask(1, 2, "test");

        verify(taskMapper, times(1)).insert(any());
    }
}
```

实际生成代码不要求与上面逐字一致，但结构和核心逻辑必须一致。

---

## 21. 验收标准

本期完成后，需要满足：

1. 插件可以通过 Gradle 编译。
2. IDEA 中右键 Java 文件/类/单个被测函数 可以看到 `Generate Unit Test`。
3. 点击后能打开生成结果预览弹窗。
4. 对验收样例可以正确识别：
   - 方法名
   - 参数
   - 约束条件：
   - `name == null`
   - `start >= end`
   - 异常类型 `IllegalArgumentException`
   - 依赖字段 `taskMapper`
   - 依赖调用 `insert`
5. 可以生成 JUnit5 + Mockito 测试代码。
6. 可以复制生成代码。
7. 可以写入 `src/test/java/.../TaskServiceTest.java`。
8. 即使遇到复杂代码，也不能导致插件崩溃；应展示 warning。
9. 主流程不依赖网络。
10. 主流程不依赖真实大模型 API。

---

## 23. 注意事项

1. 本期重点是 Java MVP，不要把方案扩展成复杂平台。
2. 代码生成要尽量稳定，优先模板化，不要完全依赖大模型。
3. JavaParser 解析失败时必须捕获异常并提示用户。
4. 生成测试代码时，如果遇到无法构造的对象，允许生成注释和保守默认值，但插件不能崩溃。
5. Mock 策略先做简单启发式，不做完整语义分析。
6. UI 先保证能看、能复制、能写入文件，不追求复杂交互。
7. 所有功能都必须围绕“最小可运行项目”实现。
---

## 24. 旧项目代码复用说明

本项目已有一批旧插件代码，可以作为实现 Java MVP 的参考。Codex 在实现时应遵循以下原则：

1. 旧代码只能作为参考，不要原样复制 `package Action`、`package MyUtils`、`package Service` 的包结构。
2. 旧代码中的 `System.out.println` 调试输出、中文临时日志、硬编码路径、直接文件 IO，需要清理或改成 IntelliJ 平台推荐写法。
3. 旧代码中与本期 MVP 无关的运行配置、覆盖率、相关性分析、自动执行测试、复杂调用链分析，暂不实现。
4. 旧代码中的大模型请求代码只保留 OkHttp 调用思路，严禁保留硬编码 API Key。API 地址和 Key 必须从环境变量或后续配置页读取，且主流程默认不调用网络。
5. 若 Codex 发现旧代码和本文需求冲突，以本文需求为准。

### 24.1 旧代码参考价值总览

| 旧代码文件 | 可复用价值 | 本期处理方式 |
|---|---|---|
| `SelectTestMethodAction.java` | IntelliJ `AnAction` 获取编辑器选中文本、调用 JavaParser 解析方法、Swing 表格选择变量、表格中配置 `MinValue / MaxValue / DoubleScale` | 参考交互思路，重构为 `GenerateUnitTestMvpAction`、`TestDataRangeConfigDialog`、`GenerationPreviewDialog` |
| `MethodExtractor.java` | 使用 `StaticJavaParser.parse(code)` 遍历 `CompilationUnit`，按方法名找到 `MethodDeclaration`，记录方法起止行 | 可重构进 `JavaSourceAnalyzer` 或 `PsiUtils` |
| `PrimeVariablesExtractor.java` / `AllVariableExtractor.java` | 使用 JavaParser 提取方法参数、局部变量、字面量、断言输出变量 | 可重构为 `ParameterExtractor`、`LiteralExtractor`，本期优先提取方法参数 |
| `VariableInfo.java` | 变量名称、类型、是否选中、起止位置的数据模型 | 可改造成 `ParameterModel` 或 UI 行模型 |
| `VariableTableModel.java` | Swing `AbstractTableModel` 展示变量选择表格 | 可改造成 `TestDataRangeTableModel` |
| `CombinedAction.java` | 使用 PSI 递归访问方法，跟踪集合 `add/remove/clear` 后的集合大小，记录方法调用和参数 | 本期可不实现，只作为后续集合边界约束增强参考 |
| `SafeConditionAction.java` | 使用 PSI 定位当前方法、递归解析调用、解析常量、收集安全条件 | 本期只复用“PSI 定位当前方法”和“递归深度限制”的思路，不做复杂跨方法约束传播 |
| `ExtractLiteralCallerMethodAction.java` | 使用 PSI 解析字面量参数所属调用方法和构造函数 | 本期可不实现，后续用于调用链约束增强 |
| `Editors.java` | 通过 `LocalFileSystem`、`FileDocumentManager` 获取 `Document`，通过 `EditorFactory` 创建编辑器 | 本期可作为 `PsiUtils` / `EditorUtils` 参考 |
| `ExtractVariableAction.java` / `ExtractVariable.java` | 调用 IDEA Refactoring API，把复杂断言表达式提取为变量 | 本期不实现，可作为后续“测试代码重构/自动修复”参考 |
| `TextReplacement.java` | 用 `TextRange + replacementText` 表达文本替换 | 可作为后续安全批量替换工具类参考 |
| `IndexUsageInfo.java` | 表达数组索引使用位置 | 可作为后续数组索引约束模型参考 |
| `ChatGPTRequester.java` | OkHttp 请求结构 | 只参考 OkHttp 写法；必须删除硬编码密钥；本期主流程不依赖真实 LLM |

### 24.2 可复用代码模式 1：从选中文本定位方法并用 JavaParser 解析

旧代码中已经有“编辑器选中文本 -> 解析当前方法”的雏形。本期不要要求用户必须选中方法名，而应优先使用 PSI 从光标位置定位方法；如果定位失败，再使用 JavaParser 选择第一个 public 方法。

参考改造方向：

```java
public class PsiUtils {

    public static String getCurrentMethodName(PsiFile psiFile, Editor editor) {
        if (psiFile == null || editor == null) {
            return null;
        }

        int offset = editor.getCaretModel().getOffset();
        PsiElement elementAt = psiFile.findElementAt(offset);
        PsiMethod method = PsiTreeUtil.getParentOfType(elementAt, PsiMethod.class, false);

        return method == null ? null : method.getName();
    }
}
```

旧 `MethodExtractor.parseSelectedMethod` 的核心逻辑可以重构为：

```java
public class JavaSourceAnalyzer {

    private MethodDeclaration findMethod(CompilationUnit cu, String preferredMethodName) {
        List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);

        if (preferredMethodName != null && !preferredMethodName.isBlank()) {
            for (MethodDeclaration method : methods) {
                if (preferredMethodName.equals(method.getNameAsString())) {
                    return method;
                }
            }
        }

        for (MethodDeclaration method : methods) {
            if (method.isPublic()) {
                return method;
            }
        }

        return methods.isEmpty() ? null : methods.get(0);
    }
}
```

### 24.3 可复用代码模式 2：测试数据范围配置表格

旧 `SelectTestMethodAction.java` 中已经出现过 `Variable Name / Variable Type / Input/Output / MinValue / MaxValue / DoubleScale` 表格。MVP 中不要再按输入输出变量弹两层窗口，而是改成一个独立的“测试数据生成配置”窗口。

建议实现为：

```java
public class TestDataRangeConfigDialog extends DialogWrapper {

    private final TestDataRangeTableModel tableModel;

    public TestDataRangeConfigDialog(Project project) {
        super(project);
        setTitle("测试数据生成配置");
        this.tableModel = new TestDataRangeTableModel(
                TestDataRangeConfigManager.getConfigs()
        );
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        JTable table = new JTable(tableModel);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        table.getColumnModel().getColumn(0).setPreferredWidth(160);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        table.getColumnModel().getColumn(3).setPreferredWidth(100);
        table.getColumnModel().getColumn(4).setPreferredWidth(180);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JScrollPane(table), BorderLayout.CENTER);

        JButton resetButton = new JButton("恢复默认");
        resetButton.addActionListener(e -> {
            TestDataRangeConfigManager.resetToDefault();
            tableModel.setConfigs(TestDataRangeConfigManager.getConfigs());
        });

        panel.add(resetButton, BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(720, 320));
        return panel;
    }

    @Override
    protected void doOKAction() {
        List<String> errors = tableModel.validateRows();
        if (!errors.isEmpty()) {
            Messages.showErrorDialog(String.join("\n", errors), "配置错误");
            return;
        }

        TestDataRangeConfigManager.updateConfigs(tableModel.getConfigs());
        super.doOKAction();
    }
}
```

### 24.4 可复用代码模式 3：Swing 表格模型

旧 `VariableTableModel.java` 的 `AbstractTableModel` 写法可以直接作为 `TestDataRangeTableModel` 的参考，但列要改成测试数据范围配置。

建议实现为：

```java
public class TestDataRangeTableModel extends AbstractTableModel {

    private final String[] columns = {"类型", "最小值", "最大值", "小数位数", "字符串规则"};
    private List<TestDataRangeConfig> configs;

    public TestDataRangeTableModel(List<TestDataRangeConfig> configs) {
        this.configs = new ArrayList<>(configs);
    }

    @Override
    public int getRowCount() {
        return configs.size();
    }

    @Override
    public int getColumnCount() {
        return columns.length;
    }

    @Override
    public String getColumnName(int column) {
        return columns[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        TestDataRangeConfig config = configs.get(rowIndex);
        switch (columnIndex) {
            case 0:
                return config.getTypeName();
            case 1:
                return config.getMinValue();
            case 2:
                return config.getMaxValue();
            case 3:
                return config.getDecimalScale() == null ? "-" : config.getDecimalScale();
            case 4:
                return config.getStringRegex() == null ? "-" : config.getStringRegex();
            default:
                return "";
        }
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        TestDataRangeConfig config = configs.get(rowIndex);
        if (columnIndex == 0) {
            return false;
        }
        if (config.isStringType()) {
            return columnIndex == 4;
        }
        if (config.isFloatingType()) {
            return columnIndex == 1 || columnIndex == 2 || columnIndex == 3;
        }
        return columnIndex == 1 || columnIndex == 2;
    }

    @Override
    public void setValueAt(Object value, int rowIndex, int columnIndex) {
        TestDataRangeConfig config = configs.get(rowIndex);
        String text = value == null ? "" : value.toString().trim();

        switch (columnIndex) {
            case 1:
                config.setMinValue(text);
                break;
            case 2:
                config.setMaxValue(text);
                break;
            case 3:
                config.setDecimalScale("-".equals(text) || text.isBlank() ? null : Integer.parseInt(text));
                break;
            case 4:
                config.setStringRegex(text);
                break;
            default:
                break;
        }

        fireTableCellUpdated(rowIndex, columnIndex);
    }

    public List<TestDataRangeConfig> getConfigs() {
        return new ArrayList<>(configs);
    }

    public void setConfigs(List<TestDataRangeConfig> configs) {
        this.configs = new ArrayList<>(configs);
        fireTableDataChanged();
    }

    public List<String> validateRows() {
        List<String> errors = new ArrayList<>();

        for (TestDataRangeConfig config : configs) {
            if (config.isStringType()) {
                if (config.getStringRegex() == null || config.getStringRegex().isBlank()) {
                    errors.add("String 类型的字符串规则不能为空。");
                }
                continue;
            }

            try {
                double min = Double.parseDouble(config.getMinValue());
                double max = Double.parseDouble(config.getMaxValue());
                if (min > max) {
                    errors.add(config.getTypeName() + " 的最小值不能大于最大值。");
                }
            } catch (NumberFormatException ex) {
                errors.add(config.getTypeName() + " 的最小值或最大值不是合法数字。");
            }

            if (config.isFloatingType()) {
                Integer scale = config.getDecimalScale();
                if (scale == null || scale < 0 || scale > 10) {
                    errors.add(config.getTypeName() + " 的小数位数必须在 0 到 10 之间。");
                }
            }
        }

        return errors;
    }
}
```

### 24.5 可复用代码模式 4：变量模型

旧 `VariableInfo.java` 可以用于理解“变量名、类型、选择状态、起止位置”的模型设计。本期核心模型仍使用 `ParameterModel`，UI 内部可以新增轻量行模型，不必直接复用旧类。

建议将测试数据配置模型扩展为：

```java
public class TestDataRangeConfig {
    private String typeName;
    private String minValue;
    private String maxValue;
    private Integer decimalScale;
    private String stringRegex;

    public boolean isFloatingType() {
        return "float".equals(typeName)
                || "Float".equals(typeName)
                || "double".equals(typeName)
                || "Double".equals(typeName)
                || "float / Float".equals(typeName)
                || "double / Double".equals(typeName);
    }

    public boolean isStringType() {
        return "String".equals(typeName)
                || "string / String".equals(typeName)
                || "String".equalsIgnoreCase(typeName);
    }
}
```

### 24.6 可复用代码模式 5：方法参数、局部变量和字面量提取

旧 `PrimeVariablesExtractor.java` 和 `AllVariableExtractor.java` 中已经使用 JavaParser 的 `findAll` 能力遍历：

- `Parameter`
- `VariableDeclarationExpr`
- `MethodCallExpr`
- `LiteralExpr`

本期优先只把“方法参数提取”纳入主流程，局部变量和字面量作为辅助信息放入 warning 或后续扩展。

建议实现：

```java
private List<ParameterModel> extractParameters(MethodDeclaration methodDeclaration) {
    List<ParameterModel> parameters = new ArrayList<>();

    for (Parameter parameter : methodDeclaration.getParameters()) {
        ParameterModel model = new ParameterModel();
        model.setName(parameter.getNameAsString());
        model.setType(parameter.getType().toString());
        model.setNullable(!parameter.getType().isPrimitiveType());
        parameters.add(model);
    }

    return parameters;
}
```

后续增强时，可以参考旧代码中的字面量识别逻辑：

```java
private String inferLiteralType(Expression expression) {
    if (expression.isBooleanLiteralExpr()) {
        return "boolean";
    }
    if (expression.isIntegerLiteralExpr()) {
        return "int";
    }
    if (expression.isDoubleLiteralExpr()) {
        return "double";
    }
    if (expression.isCharLiteralExpr()) {
        return "char";
    }
    if (expression.isStringLiteralExpr()) {
        return "String";
    }
    return "String";
}
```

### 24.7 可复用代码模式 6：PSI 定位当前方法与递归访问

旧 `SafeConditionAction.java`、`CombinedAction.java` 和 `ExtractLiteralCallerMethodAction.java` 都包含类似模式：

```java
PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
int offset = editor.getCaretModel().getOffset();
PsiElement elementAt = psiFile.findElementAt(offset);
PsiMethod method = PsiTreeUtil.getParentOfType(elementAt, PsiMethod.class, false);
```

这部分可以直接作为 `PsiUtils.getCurrentMethodName` 的参考。

对于递归访问，旧代码用了 `visited` 集合和最大深度控制，避免循环解析。本期如果实现依赖调用扫描，也必须保留类似保护：

```java
private void visitElement(PsiElement element, Set<PsiElement> visited, int depth) {
    if (element == null || depth > 3 || visited.contains(element)) {
        return;
    }

    visited.add(element);

    for (PsiElement child : element.getChildren()) {
        visitElement(child, visited, depth + 1);
    }
}
```

### 24.8 可复用代码模式 7：集合边界约束的后续参考

旧 `CombinedAction.java` 中已有集合大小的启发式分析思路，例如：

- 局部变量初始集合大小记为 0；
- 遇到 `add` / `push` 时大小加 1；
- 遇到 `remove` / `pop` / `poll` 时大小减 1；
- 遇到 `clear` 时大小归零；
- 遇到 `copy(start, end)` 时根据起止位置估算新集合大小。

这部分不进入 MVP 主流程，但可以在后续实现集合边界测试时扩展为：

```java
public class CollectionConstraintExtractor {

    public List<ConstraintModel> extract(PsiMethod method) {
        // 后续版本实现：
        // 1. 跟踪 List / Set / Queue / Stack 局部变量；
        // 2. 识别 add/remove/clear/size/get(index)；
        // 3. 生成 empty、single、multi、index out of bounds 测试场景。
        return Collections.emptyList();
    }
}
```

### 24.9 可复用代码模式 8：安全的大模型接口骨架

旧 `ChatGPTRequester.java` 中有 OkHttp 请求结构，但存在硬编码 API Key，不能复用原样代码。MVP 中默认使用 `StubLlmClient`，真实 OkHttp 客户端只做预留。

必须实现成下面这种安全形式：

```java
public class OkHttpLlmClient implements LlmClient {

    private final OkHttpClient client = new OkHttpClient();

    @Override
    public String complete(String prompt) throws IOException {
        String apiUrl = System.getenv("UNIT_TEST_AGENT_API_URL");
        String apiKey = System.getenv("UNIT_TEST_AGENT_API_KEY");

        if (apiUrl == null || apiUrl.isBlank()) {
            throw new IllegalStateException("UNIT_TEST_AGENT_API_URL is not configured.");
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("UNIT_TEST_AGENT_API_KEY is not configured.");
        }

        MediaType mediaType = MediaType.get("application/json; charset=utf-8");
        String jsonBody = buildJsonBody(prompt);

        Request request = new Request.Builder()
                .url(apiUrl)
                .post(RequestBody.create(jsonBody, mediaType))
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("LLM request failed: " + response.code());
            }
            return response.body() == null ? "" : response.body().string();
        }
    }

    private String buildJsonBody(String prompt) {
        String escaped = prompt
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");

        return "{\"messages\":[{\"role\":\"user\",\"content\":\"" + escaped + "\"}]}";
    }
}
```

### 24.10 Codex 实现时的复用顺序

Codex 应按以下顺序吸收旧代码价值：

1. 先实现新的 `GenerateUnitTestMvpAction`，只参考旧 Action 获取 `Editor / Project / PsiFile` 的方式。
2. 再实现 `PsiUtils.getCurrentMethodName`，参考旧 PSI 定位当前方法逻辑。
3. 再实现 `JavaSourceAnalyzer`，参考旧 `MethodExtractor` 的 JavaParser 遍历方式，但不要使用静态 `startLine/endLine`。
4. 再实现 `TestDataRangeConfigDialog` 和 `TestDataRangeTableModel`，参考旧 `SelectTestMethodAction` 与 `VariableTableModel`。
5. 再实现 `RandomTestDataGenerator`，使用配置范围生成可直接写进测试代码的 Java 表达式。
6. 最后实现 `GenerationPreviewDialog` 和 `TestFileWriter`。
7. `SafeConditionAction`、`CombinedAction`、`ExtractLiteralCallerMethodAction`、`ExtractVariableAction` 只作为后续增强参考，本期不要扩大范围。

### 24.11 旧代码不允许直接带入的问题

Codex 必须避免以下问题：

1. 不要把旧代码中的 `package Action;`、`package MyUtils;`、`package Service;` 原样复制进新项目。
2. 不要在新代码中保留硬编码 API Key、硬编码外部 URL 或任何真实密钥。
3. 不要在主流程中强制访问网络。
4. 不要依赖用户手动选中方法名；应优先根据光标所在 PSI 方法定位。
5. 不要用 `System.out.println` 作为用户提示；用户可见错误使用 `Messages.showErrorDialog` 或预览弹窗 warning。
6. 不要直接用 `new FileWriter` 修改 IDEA 项目文件；写项目文件时优先使用 `WriteCommandAction`，写完刷新 VFS。
7. 不要在 MVP 阶段实现复杂的覆盖率运行、测试执行、相关性分析、自动修复。
8. 不要把旧的变量替换逻辑当成本期测试生成主线，本期只生成新的测试文件，不修改原测试方法。

---

## 25. 追加验收标准：复用旧代码后的最小检查

在原有验收标准基础上，增加以下检查：

1. 打开 Java 文件后，右键菜单能触发新的 `Generate Unit Test MVP`，不需要用户手动选中方法名。
2. 光标在某个方法内部时，优先生成该方法的测试；光标不在方法中时，回退到第一个 public 方法。
3. “测试数据生成配置”窗口能够打开，表格能显示 `byte/short/int/long/float/double/String` 配置。
4. 修改 `int` 的最小值和最大值后，生成测试代码中的 `int` 参数应落在配置范围内。
5. 修改 `double` 的小数位数后，生成测试代码中的 `double` 参数应符合小数位数要求。
6. `String` 默认按 `[A-Za-z0-9_]{6,12}` 生成；如果暂时不实现完整正则生成，至少生成符合该默认规则的 ASCII 字符串。
7. 新代码中不得出现任何硬编码 API Key。
8. 主流程断网时仍可完成 Java 方法分析、测试代码预览、复制和写入文件。
