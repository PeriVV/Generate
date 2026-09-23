package com.casc.unittestagent.coverage;

import java.util.List;

/**
 * “运行时错误被归咎到 Oracle”实验语料：依赖繁多的真实项目上常见的运行期失败样本。
 * 每条样本按 JUnit XML 真实形态构造（type=外层 FQCN、message 单行、failureDetails 含
 * Caused by 链），理想分类与修复策略来自 2026-09-24 五视角排查（含 evaluation/cases
 * 库家族与真实事故：JUnit 平台版本错配、spring-petclinic 发现失败等）。
 */
public final class RuntimeMisattributionCorpus {
    public static final class Case {
        public final String name;
        public final String exceptionType;
        public final String message;
        public final String failureDetails;
        public final String rootCauseType;
        public final String idealCategory;
        public final String strategy;
        public final String expectedValue;
        public final String actualValue;

        Case(String name, String exceptionType, String message,
             String failureDetails, String rootCauseType, String idealCategory, String strategy) {
            this(name, exceptionType, message, failureDetails, rootCauseType, idealCategory, strategy, "", "");
        }

        Case(String name, String exceptionType, String message,
             String failureDetails, String rootCauseType, String idealCategory, String strategy,
             String expectedValue, String actualValue) {
            this.name = name;
            this.exceptionType = exceptionType;
            this.message = message;
            this.failureDetails = failureDetails;
            this.rootCauseType = rootCauseType;
            this.idealCategory = idealCategory;
            this.strategy = strategy;
            this.expectedValue = expectedValue;
            this.actualValue = actualValue;
        }
    }

    public static List<Case> all() {
        return List.of(
                // --- JDK 类加载 / 静态初始化 / 链接 ---
                new Case("jdk-classloading-noclassdeffound-slf4j", "java.lang.NoClassDefFoundError",
                        "org/slf4j/Logger", "", "", "RUNTIME_DEPENDENCY_UNAVAILABLE", "ENVIRONMENT"),
                new Case("jdk-classloading-noclassdeffound-jaxb", "java.lang.NoClassDefFoundError",
                        "javax/xml/bind/JAXBContext", "", "", "RUNTIME_DEPENDENCY_UNAVAILABLE", "ENVIRONMENT"),
                // 静态初始化失败但 cause 链带 NPE——修复前会被 NPE 分支误抢（cause 链污染）。
                new Case("jdk-staticinit-exceptionininitializer-npe-cause", "java.lang.ExceptionInInitializerError",
                        "", "java.lang.ExceptionInInitializerError\n"
                        + "\tat com.acme.Config.<clinit>(Config.java:42)\n"
                        + "Caused by: java.lang.NullPointerException: cannot invoke \"String.isEmpty()\" "
                        + "because the return value of \"java.lang.System.getenv(String)\" is null\n"
                        + "\tat com.acme.Config.<clinit>(Config.java:38)\n",
                        "java.lang.NullPointerException", "RUNTIME_STATIC_INIT_FAILURE", "ENVIRONMENT"),
                new Case("jdk-staticinit-could-not-initialize-log4j2", "java.lang.NoClassDefFoundError",
                        "Could not initialize class org.apache.logging.log4j.LogManager", "", "",
                        "RUNTIME_STATIC_INIT_FAILURE", "ENVIRONMENT"),
                new Case("jdk-serviceloader-configuration-error", "java.util.ServiceConfigurationError",
                        "javax.xml.stream.XMLInputFactory: Provider com.ctc.wstx.stax.WstxInputFactory not a subtype",
                        "", "", "RUNTIME_LINKAGE_VERSION_CONFLICT", "ENVIRONMENT"),
                new Case("jdk-module-illegalaccess", "java.lang.IllegalAccessError",
                        "class com.acme.report.Reflector cannot access class java.util.AbstractList "
                                + "(in module java.base) because module java.base does not export java.util "
                                + "to unnamed module @1b6d3586", "", "", "RUNTIME_LINKAGE_VERSION_CONFLICT", "ENVIRONMENT"),
                // --- Spring / 框架上下文 ---
                // Spring 5.x 用 IllegalStateException 包装上下文失败——修复前被 ISE 分支误抢为“业务前置条件”。
                new Case("spring-context-failed-to-load-illegalstate-misroute", "java.lang.IllegalStateException",
                        "Failed to load ApplicationContext", "java.lang.IllegalStateException: Failed to load "
                        + "ApplicationContext\n\tat org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate.loadContext(...)\n",
                        "", "FRAMEWORK_CONTEXT_BOOTSTRAP_FAILURE", "ENVIRONMENT"),
                new Case("spring-context-applicationcontextfailure-datasource",
                        "org.springframework.test.context.ApplicationContextFailureException",
                        "Failed to load ApplicationContext for [MergedContextConfiguration@7a8c testClass = com.acme.OrderIT]",
                        "", "", "FRAMEWORK_CONTEXT_BOOTSTRAP_FAILURE", "ENVIRONMENT"),
                new Case("spring-bean-nosuchbeandefinition",
                        "org.springframework.beans.factory.NoSuchBeanDefinitionException",
                        "No qualifying bean of type 'com.acme.order.FraudClient' available: "
                                + "expected at least 1 bean which qualifies as autowire candidate",
                        "", "", "FRAMEWORK_CONTEXT_BOOTSTRAP_FAILURE", "ENVIRONMENT"),
                new Case("spring-flyway-connection-refused", "org.flywaydb.core.api.FlywayException",
                        "Unable to obtain connection from database: Communications link failure",
                        "org.flywaydb.core.api.FlywayException: Unable to obtain connection from database\n"
                        + "Caused by: java.net.ConnectException: Connection refused (Connection refused)\n",
                        "java.net.ConnectException", "RUNTIME_EXTERNAL_RESOURCE_UNAVAILABLE", "ENVIRONMENT"),
                // --- ORM ---
                new Case("mybatis-binding-invalid-statement", "org.apache.ibatis.binding.BindingException",
                        "Invalid bound statement (not found): com.acme.order.OrderMapper.selectById", "", "",
                        "ORM_MAPPING_RUNTIME_FAILURE", "DETERMINISTIC"),
                new Case("mybatis-persistence-table-missing", "org.apache.ibatis.exceptions.PersistenceException",
                        "### Error querying database. Cause: java.sql.SQLSyntaxErrorException: "
                                + "Table 'acme.t_order' doesn't exist",
                        "org.apache.ibatis.exceptions.PersistenceException: ### Error querying database.\n"
                        + "Caused by: java.sql.SQLSyntaxErrorException: Table 'acme.t_order' doesn't exist\n",
                        "java.sql.SQLSyntaxErrorException", "ORM_MAPPING_RUNTIME_FAILURE", "DETERMINISTIC"),
                new Case("orm-hibernate-lazy-init", "org.hibernate.LazyInitializationException",
                        "failed to lazily initialize a collection of role: com.acme.Customer.orders, "
                                + "could not initialize proxy - no Session", "", "",
                        "ORM_MAPPING_RUNTIME_FAILURE", "DETERMINISTIC"),
                // --- JDBC / 外部资源 ---
                new Case("jdbc-sqlexception-no-suitable-driver", "java.sql.SQLException",
                        "No suitable driver found for jdbc:mysql://localhost:3306/acme", "", "",
                        "RUNTIME_DEPENDENCY_UNAVAILABLE", "ENVIRONMENT"),
                new Case("jdbc-hikari-pool-timeout", "java.sql.SQLTransientConnectionException",
                        "HikariPool-1 - Connection is not available, request timed out after 30000ms",
                        "", "", "RUNTIME_EXTERNAL_RESOURCE_UNAVAILABLE", "ENVIRONMENT"),
                new Case("http-connectexception-refused", "java.net.ConnectException",
                        "Connection refused (Connection refused)", "", "",
                        "RUNTIME_EXTERNAL_RESOURCE_UNAVAILABLE", "ENVIRONMENT"),
                new Case("http-unknownhost-external-api", "java.net.UnknownHostException",
                        "api.acme.com", "", "", "RUNTIME_EXTERNAL_RESOURCE_UNAVAILABLE", "ENVIRONMENT"),
                new Case("http-sockettimeout-read", "java.net.SocketTimeoutException",
                        "Read timed out", "", "", "RUNTIME_EXTERNAL_RESOURCE_UNAVAILABLE", "ENVIRONMENT"),
                new Case("http-ssl-plaintext", "javax.net.ssl.SSLException",
                        "Unrecognized SSL message, plaintext connection?", "", "",
                        "RUNTIME_EXTERNAL_RESOURCE_UNAVAILABLE", "ENVIRONMENT"),
                new Case("http-feign-retryable", "feign.RetryableException",
                        "Read timed out executing GET https://api.acme.com/v1/quote",
                        "feign.RetryableException: Read timed out executing GET https://api.acme.com/v1/quote\n"
                        + "Caused by: java.net.SocketTimeoutException: Read timed out\n",
                        "java.net.SocketTimeoutException", "RUNTIME_EXTERNAL_RESOURCE_UNAVAILABLE", "ENVIRONMENT"),
                // --- JSON 数据绑定 ---
                new Case("json-jackson-invalidformat-date", "com.fasterxml.jackson.databind.exc.InvalidFormatException",
                        "Cannot deserialize value of type `java.time.LocalDate` from String \"31/02/2024\": "
                                + "Failed to deserialize java.time.LocalDate", "", "",
                        "DATA_BINDING_INPUT_INVALID", "DETERMINISTIC"),
                new Case("json-jackson-unrecognized-field", "com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException",
                        "Unrecognized field \"discount\" (class com.acme.Order), not marked as ignorable", "", "",
                        "DATA_BINDING_INPUT_INVALID", "DETERMINISTIC"),
                new Case("json-jackson-jsr310-module-missing", "com.fasterxml.jackson.databind.exc.InvalidDefinitionException",
                        "Java 8 date/time type `java.time.Instant` not supported by default: "
                                + "add Module \"com.fasterxml.jackson.datatype:jackson-datatype-jsr310\"", "", "",
                        "RUNTIME_DEPENDENCY_UNAVAILABLE", "ENVIRONMENT"),
                // --- 版本冲突（含两个 cause 链污染样本） ---
                new Case("versioning-nosuchmethod-slf4j-logback", "java.lang.NoSuchMethodError",
                        "org/slf4j/Logger.atLevel(Lorg/slf4j/Level;)Lorg/slf4j/spi/LoggingEventBuilder;", "", "",
                        "RUNTIME_LINKAGE_VERSION_CONFLICT", "ENVIRONMENT"),
                new Case("versioning-nosuchmethod-junit-platform", "java.lang.NoSuchMethodError",
                        "org.junit.platform.commons.util.ReflectionUtils.tryToLoadClass(Ljava/lang/String;)"
                                + "Lorg/junit/platform/commons/function/Try;", "", "",
                        "RUNTIME_LINKAGE_VERSION_CONFLICT", "ENVIRONMENT"),
                new Case("versioning-noclassdeffound-junit6-launcher-mismatch", "java.lang.NoClassDefFoundError",
                        "org/junit/platform/engine/OutputDirectoryCreator", "", "",
                        "RUNTIME_DEPENDENCY_UNAVAILABLE", "ENVIRONMENT"),
                new Case("versioning-abstractmethod-mysql", "java.lang.AbstractMethodError",
                        "Receiver class com.mysql.cj.jdbc.ConnectionImpl does not define or inherit "
                                + "the implementation of the resolved method abstract", "", "",
                        "RUNTIME_LINKAGE_VERSION_CONFLICT", "ENVIRONMENT"),
                // IAE 形态的版本冲突——修复前被 IAE 分支误抢为“业务前置条件”。
                new Case("versioning-unsupported-class-file-misroute", "java.lang.IllegalArgumentException",
                        "Unsupported class file major version 66", "", "",
                        "RUNTIME_LINKAGE_VERSION_CONFLICT", "ENVIRONMENT"),
                // --- 环境敏感断言（保持 Oracle 语义，验证不误伤） ---
                new Case("locale-turkish-dotless-i", "org.opentest4j.AssertionFailedError",
                        "expected: <title> but was: <tıtle>", "", "",
                        "ASSERTION_VALUE_MISMATCH", "EVIDENCE_REQUIRED",
                        "title", "tıtle"),
                new Case("timezone-midnight-crossing", "org.opentest4j.AssertionFailedError",
                        "expected: <2024-06-30> but was: <2024-07-01>", "", "",
                        "ASSERTION_VALUE_MISMATCH", "EVIDENCE_REQUIRED",
                        "2024-06-30", "2024-07-01"),
                new Case("charset-mojibake-assert", "org.opentest4j.AssertionFailedError",
                        "expected: <café> but was: <cafÃ©>", "", "",
                        "ASSERTION_VALUE_MISMATCH", "EVIDENCE_REQUIRED",
                        "café", "cafÃ©"),
                // --- 既有类别的子类漏检（evaluation/cases 库家族） ---
                new Case("jsoup-validationexception-subclass-miss", "eval.oss.jsoup.classreal004.ValidationException",
                        "Object must not be null", "", "", "DOMAIN_PRECONDITION_FAILURE", "DETERMINISTIC"),
                new Case("jsoup-selector-parse-exception", "org.jsoup.select.Selector$SelectorParseException",
                        "Could not parse query 'div[': unexpected token at '['", "", "",
                        "DOMAIN_PRECONDITION_FAILURE", "DETERMINISTIC"),
                new Case("jsoup-stringutil-malformedurl", "java.net.MalformedURLException",
                        "no protocol", "", "", "DOMAIN_PRECONDITION_FAILURE", "DETERMINISTIC"),
                new Case("commons-lang-numberformat-create-number", "java.lang.NumberFormatException",
                        "For input string: \"12.3eZ\"", "", "",
                        "DOMAIN_PRECONDITION_FAILURE", "DETERMINISTIC"),
                new Case("commons-lang-fraction-overflow", "java.lang.ArithmeticException",
                        "overflow: mulPos", "", "", "DOMAIN_PRECONDITION_FAILURE", "DETERMINISTIC"),
                // --- Mockito 残余语义 ---
                new Case("mockito-instrumentation-jdk-conflict", "org.mockito.exceptions.base.MockitoException",
                        "Cannot mock/spy class com.acme.FinalGateway. Mockito cannot mock this class. "
                                + "The Java version 21 is not supported by the mock maker",
                        "", "", "MOCKITO_MOCKING_UNAVAILABLE", "DETERMINISTIC"),
                new Case("mockito-unnecessary-stubbing", "org.mockito.exceptions.misusing.UnnecessaryStubbingException",
                        "Unnecessary stubbings detected in test: ordering. Clean and maintainable tests "
                                + "require unnecessary stubbings to be removed", "", "",
                        "MOCKITO_UNNECESSARY_STUBBING", "DETERMINISTIC"),
                new Case("mockito-strict-stubbing-mismatch", "org.mockito.exceptions.misusing.PotentialStubbingProblem",
                        "Strict stubbing argument mismatch. This stubbing is not needed here", "", "",
                        "MOCKITO_STRICT_STUBBING_MISMATCH", "DETERMINISTIC"),
                // --- 注解类型缺失（可选依赖） ---
                new Case("annotation-typenotpresent-jspecify", "java.lang.TypeNotPresentException",
                        "Type org.jspecify.annotations.Nullable not present", "", "",
                        "RUNTIME_DEPENDENCY_UNAVAILABLE", "ENVIRONMENT"));
    }

    private RuntimeMisattributionCorpus() {
    }
}
