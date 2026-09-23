# 运行时错误误归因实验记录（2026-09-24）

## 问题

用户报告：插件在依赖繁多的真实项目上运行时，大量运行时错误（依赖缺失、框架启动失败、
外部资源访问等）被归咎到 Oracle——闭环以 `ORACLE_INCONCLUSIVE` + "Oracle 证据冲突或不足" 停止。

## 根因（五视角排查交叉证实）

1. **确定性分类器零覆盖环境家族**：`MockitoRuntimeDiagnosticEnricher` 只识别 Mockito 语义与
   AssertionError/IAE/ISE/NPE，NoClassDefFoundError、框架上下文失败、网络/资源类异常全部落入
   `TEST_RUNTIME_FAILURE` 兜底（UNCLASSIFIED）。
2. **cause 链污染（主动误分类）**：IAE/ISE/NPE 分支对含 Caused by 的全文做子串匹配——
   Spring "IllegalStateException: Failed to load ApplicationContext" 被判为"业务前置条件不满足"（DETERMINISTIC），
   ExceptionInInitializerError 因 cause 链 NPE 被判为"Fixture 空指针"甚至被升级为"可修的 FIXTURE_DEPENDENCY_MISSING"。
3. **终态漏斗不读分类**：`coverageStatusForOracle` 等四个函数把一切非 SUSPECTED_SUT_BUG 的仲裁
   （实际只剩 INCONCLUSIVE）一律映射为 `NEEDS_REVIEW + ORACLE_INCONCLUSIVE`。
4. **提示词把环境失败推向 INCONCLUSIVE**：R7"证据冲突或不足时返回 INCONCLUSIVE"，而环境类失败
   在 authorityItems 中不可能有契约证据；封闭分类列表中又没有任何运行期环境类别可选。

## 实验设计

语料：40 条依赖项目真实形态的运行时失败样本（JDK 类加载/静态初始化/链接冲突、Spring 上下文、
ORM、JDBC/网络、JSON 绑定、版本冲突、Mockito 残余语义、jsoup/commons-lang 子类漏检；
含 3 条应保留 Oracle 语义的环境敏感断言作对照组）。实验测试：

- `coverage/RuntimeMisattributionCorpusTest`：分类维度（enricher → FailureCategory）
- `service/RuntimeMisattributionRoutingTest`：终态路由维度（status/stopReason/message 不归咎 Oracle）

## 修复前基线（本文件记录时点）

- 分类误归因 **40/40（100%）**
- 终态误归因 **37/40（92.5%）**（3 条 EVIDENCE_REQUIRED 对照组正确保留 Oracle 待确认态）
- 静态初始化 NPE cause 被升级为 FIXTURE_DEPENDENCY_MISSING（误导修复方向）

## 修复（同日落地）

1. `FailureCategory` 新增 11 个运行期成员（全部 runtimeArbitratable=true，自动进入 R3c 封闭列表）：
   RUNTIME_DEPENDENCY_UNAVAILABLE / RUNTIME_LINKAGE_VERSION_CONFLICT / RUNTIME_STATIC_INIT_FAILURE /
   RUNTIME_EXTERNAL_RESOURCE_UNAVAILABLE / FRAMEWORK_CONTEXT_BOOTSTRAP_FAILURE / ORM_MAPPING_RUNTIME_FAILURE /
   RUNTIME_RESOURCE_LIMIT_EXCEEDED / DATA_BINDING_INPUT_INVALID / MOCKITO_UNNECESSARY_STUBBING /
   MOCKITO_STRICT_STUBBING_MISMATCH / MOCKITO_MOCKING_UNAVAILABLE
2. enricher：环境分支插在断言/IAE/ISE/NPE 之前；IAE/ISE/NPE 收紧为只看顶层证据（exceptionType +
   rootCauseType + message），消除 cause 链污染；DOMAIN 分支补子类名（numberformat/arithmetic/
   malformedurl/validation/selectorparse）。
3. `MissingFixtureDependencyDetector`：升级条件收紧为顶层 NPE。
4. 终态路由按 repairStrategy 分流：DETERMINISTIC→TEST_FAILED+TEST_DESIGN_ERROR；
   ENVIRONMENT→TEST_FAILED+RUNTIME_ENVIRONMENT_FAILURE（新枚举值，写入门禁保持受保护语义）；
   EVIDENCE_REQUIRED/UNCLASSIFIED→维持 NEEDS_REVIEW+ORACLE_INCONCLUSIVE（证据续修入口保留）。
5. `RuntimeOracleGate.adjudicateCategory`：模型取值必须是 runtimeArbitratable 类别。
6. 提示词：R7 限定 INCONCLUSIVE 仅用于 Oracle 语义类失败；新增 R3b 环境失败处置规则；
   专项规则补 Mockito 残余类别；classRepairPrompt 同步。

## 复验结果（同日落地后）

- 分类误归因 **0/40**（原 40/40）；终态误归因 **0/40**（原 37/40）；
  对照组 3 条环境敏感断言（Locale/时区/编码）正确保留 `NEEDS_REVIEW + ORACLE_INCONCLUSIVE`
  与“缺少可授权修改 Oracle 的独立证据”文案——真正的 Oracle 语义问题不受影响。
- cause 链污染专项：静态初始化 NPE cause 不再被升级为 FIXTURE_DEPENDENCY_MISSING。
- `./gradlew test` **358 个用例全绿**（含新增语料实验 5 例）。
- 附带修复：OracleStopMessageCategoryTest 旧的 UNCLASSIFIED 文案断言同步为新行为。
