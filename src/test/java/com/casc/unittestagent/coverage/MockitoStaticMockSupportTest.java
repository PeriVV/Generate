package com.casc.unittestagent.coverage;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class MockitoStaticMockSupportTest extends TestCase {
    public void testMockitoFourCoreWithoutInlineIsUnsupported() throws Exception {
        Path root = Files.createTempDirectory("unitmind-mockito4-");
        Path core = root.resolve("mockito-core-4.11.0.jar");
        Files.write(core, new byte[0]);

        MockitoStaticMockSupport support = MockitoStaticMockSupport.detect(List.of(core.toFile()));

        assertFalse(support.isSupported());
        assertTrue(support.getDescription().contains("4.11.0"));
    }

    public void testMockitoFiveCoreUsesInlineMockMakerByDefault() throws Exception {
        Path root = Files.createTempDirectory("unitmind-mockito5-");
        Path core = root.resolve("mockito-core-5.14.2.jar");
        Files.write(core, new byte[0]);

        MockitoStaticMockSupport support = MockitoStaticMockSupport.detect(List.of(core.toFile()));

        assertTrue(support.isSupported());
    }

    public void testExplicitInlineExtensionIsSupported() throws Exception {
        Path root = Files.createTempDirectory("unitmind-mock-maker-");
        Path extension = root.resolve("mockito-extensions/org.mockito.plugins.MockMaker");
        Files.createDirectories(extension.getParent());
        Files.writeString(extension, "mock-maker-inline", StandardCharsets.UTF_8);

        MockitoStaticMockSupport support = MockitoStaticMockSupport.detect(List.of(root.toFile()));

        assertTrue(support.isSupported());
    }

    public void testDetectsStaticMockUsage() {
        assertTrue(MockitoStaticMockSupport.usesStaticMocking(
                "try (MockedStatic<Clock> clock = Mockito.mockStatic(Clock.class)) {}"));
        assertFalse(MockitoStaticMockSupport.usesStaticMocking(
                "Clock clock = mock(Clock.class);"));
    }
}
