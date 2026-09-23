package com.casc.unittestagent.coverage;

import junit.framework.TestCase;

import java.io.File;
import java.util.List;

/**
 * 覆盖率隔离 JVM 的 JUnit classpath 排序决策测试。
 * 用户 junit-platform-commons 老于或等于内置 console-standalone 时必须内置优先，
 * 否则 ConsoleLauncher 启动即抛 NoSuchMethodError；用户更新时保持用户优先。
 */
public class JacocoCoverageExecutorClasspathOrderTest extends TestCase {
    private static final String BUNDLED = "/plugins/UnitMind/lib/junit-platform-console-standalone-1.12.0.jar";

    public void testOlderUserPlatformPrefersBundledConsole() {
        assertFalse(JacocoCoverageExecutor.prefersUserJunitFirst(
                List.of(jar("~/.m2/org/junit/platform/junit-platform-commons/1.10.1/junit-platform-commons-1.10.1.jar")),
                BUNDLED));
    }

    public void testSameVersionAsBundledPrefersBundledConsole() {
        assertFalse(JacocoCoverageExecutor.prefersUserJunitFirst(
                List.of(jar("~/.m2/org/junit/platform/junit-platform-commons/1.12.0/junit-platform-commons-1.12.0.jar")),
                BUNDLED));
    }

    public void testNewerUserPlatformKeepsUserFirst() {
        assertTrue(JacocoCoverageExecutor.prefersUserJunitFirst(
                List.of(jar("~/.m2/org/junit/platform/junit-platform-commons/1.13.0/junit-platform-commons-1.13.0.jar"),
                        jar("~/.m2/org/junit/jupiter/junit-jupiter-engine/6.0.0/junit-jupiter-engine-6.0.0.jar")),
                BUNDLED));
    }

    public void testProjectWithoutJunitUsesBundledConsole() {
        assertFalse(JacocoCoverageExecutor.prefersUserJunitFirst(
                List.of(jar("~/.m2/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar")),
                BUNDLED));
    }

    public void testVersionParsingAndComparison() {
        assertEquals("1.12.0", JacocoCoverageExecutor.jarVersion(BUNDLED, "console-standalone"));
        assertEquals("1.10.1", JacocoCoverageExecutor.jarVersion(
                "/m2/junit-platform-commons-1.10.1.jar", "junit-platform-commons-"));
        assertNull(JacocoCoverageExecutor.jarVersion("/m2/commons-lang3-3.14.0.jar", "junit-platform-commons-"));
        assertTrue(JacocoCoverageExecutor.compareVersions("1.10.1", "1.12.0") < 0);
        assertTrue(JacocoCoverageExecutor.compareVersions("1.12.0", "1.12.0") == 0);
        assertTrue(JacocoCoverageExecutor.compareVersions("2.0", "1.13") > 0);
        assertTrue(JacocoCoverageExecutor.compareVersions("1.3", "1.2.9") > 0);
    }

    public void testDetectsUserPlatformLauncherPresence() {
        assertTrue(JacocoCoverageExecutor.hasUserPlatformLauncher(
                List.of(jar("~/.m2/org/junit/platform/junit-platform-launcher/1.13.0/junit-platform-launcher-1.13.0.jar"))));
        assertFalse(JacocoCoverageExecutor.hasUserPlatformLauncher(
                List.of(jar("~/.m2/org/junit/platform/junit-platform-commons/6.0.3/junit-platform-commons-6.0.3.jar"))));
        assertFalse(JacocoCoverageExecutor.hasUserPlatformLauncher(null));
    }

    public void testJUnitStackExclusionMatchesOnlyOfficialArtifacts() {
        assertTrue(JacocoCoverageExecutor.isUserJUnitStackJar("junit-platform-engine-6.0.3.jar"));
        assertTrue(JacocoCoverageExecutor.isUserJUnitStackJar("junit-jupiter-engine-6.0.3.jar"));
        assertTrue(JacocoCoverageExecutor.isUserJUnitStackJar("junit-vintage-engine-5.12.0.jar"));
        // 第三方适配 jar 名字里带 junit-jupiter 但不能剔除。
        assertFalse(JacocoCoverageExecutor.isUserJUnitStackJar("mockito-junit-jupiter-5.23.0.jar"));
        assertFalse(JacocoCoverageExecutor.isUserJUnitStackJar("testcontainers-junit-jupiter-2.0.5.jar"));
    }

    private static File jar(String path) {
        return new File(path.replace("~", System.getProperty("user.home")));
    }
}
