# 开源项目自动化矩阵

用真实开源仓库自动验证 UnitMind 的两条链路，替代在 IDE 里逐个项目手工测试：

1. **覆盖率闭环矩阵**（`OpenSourceCoverageClosureMatrixTest`）：初始候选执行 → 补测候选执行，
   断言方法级行/分支覆盖率真实提升。任何一步失败都会带出沙箱编译、JUnit 执行或 JaCoCo 采集的原始错误。
2. **规则生成流水线**（`OpenSourceRuleGenerationPipelineTest`）：对每个目标方法完整执行插件的无大模型
   生成链路 `JavaSourceAnalyzer → TestScenarioPlanner → MockStrategyPlanner → JUnitMockitoTestGenerator`
   → 沙箱编译 → 隔离 JVM 执行。契约：必须能定位方法、规划出可执行场景、生成的测试在真实项目
   classpath 上编译通过、能被 JUnit 5 发现执行；执行通过率与覆盖率作为质量趋势写入报告，不让测试失败。

## 使用

```bash
# 1. 克隆并构建矩阵项目（固定 tag，可只构建指定项目；部分项目会自动选用 JDK 17）
./evaluation/opensource/prepare-oss-matrix.sh [commons-codec|commons-io|commons-lang|commons-text|guava|jackson-databind|HikariCP]

# 2. 运行两个自动化测试（未准备的项目自动跳过，不影响普通 ./gradlew test）
./gradlew test \
  --tests "com.casc.unittestagent.coverage.OpenSourceCoverageClosureMatrixTest" \
  --tests "com.casc.unittestagent.coverage.OpenSourceRuleGenerationPipelineTest"

# 3. 查看报告
cat build/reports/unitmind/oss-coverage-matrix.md
cat build/reports/unitmind/oss-rule-pipeline.md
```

导出的 `target/unitmind.classpath` 使用 test scope，与 IDE 中模块 test classpath 形态一致
（包含项目自带的 JUnit 5 / Mockito），专门暴露插件运行时与项目 JUnit 版本组合产生的真实问题。

## 当前项目

| 项目 | 版本 | 许可证 | 目标方法 | 构建模式 |
|---|---|---|---|---|
| commons-codec | 1.16.1 | Apache-2.0 | `Hex#toDigit` | root |
| commons-io | 2.16.1 | Apache-2.0 | `FilenameUtils#getName` | root |
| commons-lang | 3.14.0 | Apache-2.0 | `NumberUtils#isCreatable` | root |
| commons-text | 1.11.0 | Apache-2.0 | `JaroWinklerSimilarity#apply` | root |
| guava | 33.0.0 | Apache-2.0 | `Strings#repeat` | 模块独立（`guava/`） |
| jackson-databind | 2.17.0 | Apache-2.0 | `TypeParser#parseType` | root |
| jackson-core | 2.17.0 | Apache-2.0 | `NumberInput#inLongRange` | root |
| HikariCP | 5.1.0 | Apache-2.0 | `UtilityElf#getTransactionIsolation` | root（JPMS 项目） |
| mybatis-3 | 3.5.16 | Apache-2.0 | `GenericTokenParser#parse` | root |
| spring-petclinic | main | Apache-2.0 | `PetValidator#validate` | root（Spring Boot 3 + JUnit 6，最接近企业内网栈） |

另有 jsoup 冒烟测试（`JsoupLocalCoverageClosureTest`），需设置 `UNITMIND_JSOUP_REPO` 与
`UNITMIND_JSOUP_CLASSPATH_FILE` 环境变量后执行。

## 暂未纳入矩阵的项目及原因

| 项目 | 原因 |
|---|---|
| mockito、resilience4j | Gradle 多模块构建，当前准备脚本只支持 Maven；沙箱执行本身可以工作，欢迎补充脚本支持 |
| vavr 0.10.4 | 构建依赖 Scala 代码生成插件，在 JDK 11+ 无法编译，需要项目专属旧 JDK toolchain |
| eclipse-collections 11.1.0 | impl 模块 `mvn install` 在 JDK 17 下仍有插件执行失败，需项目专属构建环境 |
| joda-time 2.12.7 | 2.12.7 tag 在 JDK 17 下 `mvn compile` 失败（tz 数据生成插件），需项目专属旧 JDK toolchain |
| Spring 系等重 Lombok 项目 | 已知边界：沙箱编译不含注解处理器输出（IDE 内同样会失败），等 Lombok 支持后再纳入 |

直连 GitHub 不通时可用镜像前缀准备：`UNITMIND_GIT_PREFIX="https://gh-proxy.com/" ./evaluation/opensource/prepare-oss-matrix.sh`。

## 已通过该矩阵发现并修复的问题

- **JUnit Platform 版本冲突（NoSuchMethodError）**：项目自带 junit-platform-commons < 1.12 时，
  ConsoleLauncher（来自内置 standalone 1.12）启动即崩，报“未发现可执行的 JUnit 5 测试”。
  修复：`JacocoCoverageExecutor` 按用户 platform 版本决定 classpath 顺序。
- **生成代码缺异常 import**：异常场景 `assertThrows(DecoderException.class, ...)` 引用业务异常
  但未生成 import，导致编译失败（commons-codec 上暴露）。
  修复：`JUnitMockitoTestGenerator` 为方法 throws 与分支异常类型补充 import。
- **接口参数被 new**：`new CharSequence()` 这类必然编译失败的参数值（commons-text 上暴露）。
  修复：`RandomTestDataGenerator` 为常见 JDK 接口/抽象类型给出兼容字面量。
- **嵌套类型参数不可见**：被测方法参数是嵌套类（jackson `TypeParser.MyTokenizer`）时，裸简单名
  在生成的测试类里不可见，new/mock/参数声明全部编译失败。
  修复：`JavaSourceAnalyzer` 把嵌套类型改写为“顶层类.嵌套类”限定名，生成器对限定类型使用
  `mock(限定名.class)`，完全无法解析的类型退回 null 保住可编译性。
- **JPMS 项目沙箱编译**：classpath 上出现 `module-info.class` 会触发模块解析并因缺依赖模块报
  “找不到模块”（HikariCP 上暴露）。测试沙箱按类路径模式运行，拷贝产物时跳过 module-info.class；
  IDE 内 JPMS 模块路径场景仍在验证中。
- **JUnit 6 缺 launcher 错配**（spring-petclinic 暴露）：项目用 JUnit 6.0.3（jupiter/engine/commons
  全 6.x）但 test classpath 无 `junit-platform-launcher`，launcher 只能来自内置 standalone 1.12，
  engine↔launcher 版本错配在发现阶段抛 `OutputDirectoryCreator not available`。
  修复：`JacocoCoverageExecutor.runtimeClasspath` 在“用户平台更新且无 launcher”时整体对齐内置
  全套（standalone 前置并按 org.junit 官方 artifact 前缀剔除用户 junit 栈 jar）。
- **第三方接口参数被 new**（spring-petclinic 暴露）：`new Errors()`——`Errors` 是 Spring 接口，
  不在名称启发式后缀表内，必然编译失败。
  修复：新增 `ClasspathTypeProbe`（classpath 简单名索引 + 反射判定接口/抽象/无公共无参构造），
  规则生成流水线以真实 classpath 判定可实例性，启发式退化为无探针时兜底。
- **重载选择错误**（jackson-core 暴露）：`inLongRange(String, boolean)` 被按名称匹配成 4 参重载，
  规则流水线测错方法。修复：流水线按声明文本定位行号后调用 `analyze(source, name, line)`。
