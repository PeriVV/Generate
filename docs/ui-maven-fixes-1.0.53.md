# 1.0.53 UI 覆盖率与 Maven 测试依赖修复

状态：最终代码的完整本机回归、Windows 原生验收及三个 IDEA 版本的 API 兼容检查全部通过，安装包已完成指纹与内容核对。

## 三项反馈的处理

1. 删除覆盖率卡片标题中的“（含 lambda）”。仅调整文字，采集范围不变。
2. 运行态不再隐藏已完成候选的行/分支覆盖率。有效测量立即发布，主卡与轮次历史可在补测继续时显示数值；首次测量前仍显示执行中，无分支显示 N/A，失败/失效/取消等状态不虚构有效覆盖率。入队前复制统计和列表，防止下一轮修改后台报告导致旧回调读到新数据；任务结束后旧进度不能覆盖最终结果。
3. Maven 由通用 Reimport 动作改为明确关联所选 POM、重读模型、解析依赖、等待模块导入，再检查目标 Java 文件所属测试模块的 JUnit/Mockito 可见性。同步失败、取消或仍缺依赖均单独反馈；等待期间禁用生成、验证、执行等动作，完成后恢复。

## Maven 模式具体会做什么

- 修改当前 Java 文件最近的模块 pom.xml，补充 test 作用域的 JUnit Jupiter（或缺失的 engine）、Mockito core、Mockito Jupiter 扩展。保留已有依赖版本/BOM 管理；缺省 JUnit 5.12.0，Mockito 在 Java 8 使用 4.11.0、Java 11 及以上使用 5.18.0。
- 即使已经有 JUnit 库，也检查 JUnit 5 执行配置。缺少可用 Surefire 声明或已知旧于 2.22 时使用 3.5.4；处理本地版本属性及没有活动版本的情况，保留现有插件配置。
- JUnit 4/5 混合项目补充 Vintage 引擎，避免切到 JUnit Platform 后旧测试静默漏跑。保留已有 JUnit 4 版本，Vintage 跟随 Jupiter/BOM；已知低于 JUnit 4.12 时停止自动配置并说明原因。真实 Maven 对照夹具修复前只执行 1 个 Jupiter 测试，修复后两类共 2 个测试均实际执行并产生 XML 报告。规则依据 [Surefire 3.5.4 JUnit Platform 文档](https://maven.apache.org/surefire-archives/surefire-3.5.4/maven-surefire-plugin/examples/junit-platform.html)和 [JUnit 5.12 用户指南](https://docs.junit.org/5.12.0/user-guide/)。
- 不更换项目仓库或 Maven 离线设置，也不把插件内置 JAR 直接安装到 Maven 本地仓库。依赖解析沿用项目 Maven 设置；仓库不可达、离线缓存缺失、版本冲突会在同步或可见性检查中反馈。
- 先备份变更前的构建文件，创建 src/test/java，再通过 IDEA Maven 导入所选 POM。完成提示表示依赖对模块可见，不表示业务测试已经通过，仍需重新运行代码验证。
- “仅 IDEA：离线导入内置 JAR”是另一种模式：添加模块 TEST 库，不写入 Maven/Gradle 构建声明，不能代替 Maven/CI 的依赖配置。

## 兼容与回归

IDEA 222 的 Maven 解析与模块导入是分开步骤；较新版本的旧兼容入口可能同步执行，且已移除单独等待方法。相应调用放在后台，仅在旧版本存在公开等待方法时使用它。兼容声明仍为 222–262.*，插件仍使用 Java 17 字节码。

定向回归覆盖真实 JaCoCo 过程测量、Swing 数值刷新与忙闲状态、异步 Maven 阶段顺序/错误/取消、旧新 IDEA 等待接口、POM 幂等与版本处理，以及真实 Maven/Gradle 执行 JUnit/Mockito。已有依赖但 Surefire 过旧的 Maven 夹具也真实执行测试并检查 JUnit XML。

本轮未访问用户业务项目，未调用付费模型；测试结果不代表所有 IDEA 版本的人工 GUI 验收。完整验收和最终包指纹见下方最终验收记录。

## 中间兼容性诊断

初次 API 检查在 IDEA 2025.3/2026.2 发现 Promise.resolve 静态方法已移除。已替换为既有的 AsyncPromise 构造与完成接口；对应旧快照轮次 40181113089 取消，不用于交付。最终结果以修复后完整重跑为准。


## 最终验收

- 本地普通回归 766 项、独立开源探索任务 1 项，均为 0 失败、0 错误、0 跳过；冻结源码指纹一致。
- Windows x64 普通全量 766 项、专项全量 236 项、独立开源探索任务 1 项，均为 0 失败、0 错误、0 跳过。已逐项核对用例名称，确认与最终源码的用例清单一致。
- Windows 在中文与空格检出目录中运行，实际使用项目 JDK 8、11、17、21、25；外部旧 Gradle/IDEA 222 夹具的构建输出仍使用 ASCII 临时目录，限制详见上一版 Windows 验收记录。
- 本地及 Windows 产物均通过 IDEA 2022.2.5、2025.3、2026.2.0.1 的 API 兼容检查；支持声明保持 222–262.*，插件自身为 Java 17 字节码。这不代表每个补丁版本的人工 GUI 验收。
- Windows 验收源码：`9e90d9ec5a070f8a0b3d9dadab5eb31d71b3dbb3`；[成功验收轮次](https://github.com/PeriVV/Generate/actions/runs/40183815818)。
- 最终交付文件：`build/distributions/UnitMind-1.0.53.zip`。直接提取自上述成功 Windows 轮次，24,023,501 字节、12 个顶层 JAR；已核对修复类、字节码、内置运行库索引、重复类及依赖冲突。
- SHA-256：`04170fa562d882113b07f5be4b9d8beafe63c54dff1b7cfe7f14a3338a6d9a76`。
- 旧版 1.0.52 安装包和验收证据保留，可用于回退。

### 保留的 API 提示

- IC-222.4554.10：Compatible. 1 usage of deprecated API
- IU-253.28294.334：Compatible. 3 usages of scheduled for removal API and 8 usages of deprecated API. 1 usage of internal API
- IU-262.8665.337：Compatible. 4 usages of scheduled for removal API and 38 usages of deprecated API. 2 usages of internal API

### 探索质量与平台验收的区别

本地原始规则探索覆盖 14 个仓库、129 个方法，其中 78 个方法的全部候选通过、51 个仍有候选失败。Windows 原始规则探索覆盖 3 个仓库、42 个方法，其中 26 个方法的全部候选通过、16 个仍有候选失败。这些是独立的生成质量记录，不计作平台回归通过，也不表示所有生成草稿都正确；失败候选仍受正常写入门禁拦截。

升级插件后，在目标 Java 文件所属模块重新执行“配置测试依赖 → Maven”，查看实际 POM 差异并等待导入结果。若提示依赖仍不可见，按提示检查项目仓库、离线设置和 Maven 同步错误；仅 IDEA 的离线导入不能代替 Maven/CI 配置。
