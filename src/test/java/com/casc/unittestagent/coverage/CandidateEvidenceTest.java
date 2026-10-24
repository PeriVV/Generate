package com.casc.unittestagent.coverage;

import junit.framework.TestCase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.List;

public class CandidateEvidenceTest extends TestCase {
    public void testConfigurationCreationAndEditingInvalidatePriorEvidence() throws Exception {
        Path root = Files.createTempDirectory("evidence 中文 ");
        try {
            var environment = environment(root);
            var first = CandidateEvidenceFactory.capture(environment, "class SubjectTest {}");
            assertTrue(CandidateEvidenceFactory.isCurrent(null, first));
            var configuration = root.resolve(".unitmind/test-runtime.json");
            Files.createDirectories(configuration.getParent()); Files.writeString(configuration, "{}");
            assertFalse(CandidateEvidenceFactory.isCurrent(null, first));
            var second = CandidateEvidenceFactory.capture(environment, "class SubjectTest {}");
            Files.writeString(configuration, "{\"runtime\":{\"systemProperties\":{\"profile\":\"changed\"}}}");
            assertFalse(CandidateEvidenceFactory.isCurrent(null, second));
        } finally { CompiledTestArtifact.deleteRecursively(root); }
    }

    public void testRuntimeOptionsHaveDifferentIdentitiesWithoutExposingSecrets() throws Exception {
        Path root = Files.createTempDirectory("evidence-runtime-");
        try {
            var environment = environment(root);
            var first = CandidateEvidenceFactory.capture(environment, "class SubjectTest {}");
            environment.runtimeConfiguration = new TestRuntimeConfiguration(List.of("-ea"), Map.of("secret", "private-value"),
                    Map.of(), Set.of(), true, root.toFile(), "", "local");
            var next = CandidateEvidenceFactory.capture(environment, "class SubjectTest {}");
            assertFalse(first.identity().equals(next.identity()));
            assertFalse(next.toString().contains("private-value"));
            assertEquals(first.identity().sourceHash(), next.identity().sourceHash());
        } finally { CompiledTestArtifact.deleteRecursively(root); }
    }

    public void testClasspathOrderIsPartOfTheCandidateIdentity() throws Exception {
        Path root = Files.createTempDirectory("evidence-order-");
        try {
            var environment = environment(root);
            var firstJar = Files.write(root.resolve("a.jar"), new byte[]{1}).toFile();
            var secondJar = Files.write(root.resolve("b.jar"), new byte[]{2}).toFile();
            environment.classpath.add(firstJar); environment.classpath.add(secondJar);
            var first = CandidateEvidenceFactory.capture(environment, "class SubjectTest {}");
            environment.classpath.clear(); environment.classpath.add(secondJar); environment.classpath.add(firstJar);
            assertFalse(first.identity().equals(CandidateEvidenceFactory.capture(environment, "class SubjectTest {}").identity()));
        } finally { CompiledTestArtifact.deleteRecursively(root); }
    }

    public void testNestedClasspathChangesInvalidatePriorEvidence() throws Exception {
        Path root = Files.createTempDirectory("evidence-dependency-");
        try {
            Path classes = Files.createDirectories(root.resolve("classes/pkg"));
            Files.write(classes.resolve("Old.class"), new byte[]{1});
            var environment = environment(root); environment.classpath.add(root.resolve("classes").toFile());
            var first = CandidateEvidenceFactory.capture(environment, "class SubjectTest {}");
            Files.write(classes.resolve("New.class"), new byte[]{2});
            assertFalse(CandidateEvidenceFactory.isCurrent(null, first));
        } finally { CompiledTestArtifact.deleteRecursively(root); }
    }

    public void testExternalMavenPropertiesChangesInvalidateExportedRuntimeEvidence() throws Exception {
        verifyExternalMavenRuntimeInput("runtime.properties");
    }

    public void testExternalMavenInputWithoutExtensionUsesContentEvenWhenMetadataIsUnchanged() throws Exception {
        verifyExternalMavenRuntimeInput("runtime-settings");
    }

    private void verifyExternalMavenRuntimeInput(String name) throws Exception {
        Path root = Files.createTempDirectory("evidence-maven-input-");
        try {
            Path module = Files.createDirectories(root.resolve("module"));
            Path shared = Files.createDirectories(root.resolve("shared 中文"));
            Path properties = shared.resolve(name);
            Files.writeString(properties, "mode=before\n");
            var modified = Files.getLastModifiedTime(properties);
            long size = Files.size(properties);
            Path exported = Files.createDirectories(root.resolve("export")).resolve("effective-pom.xml");
            Files.writeString(exported, "<project><build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId>"
                    + "<configuration><systemPropertiesFile>${basedir}/../shared 中文/" + name
                    + "</systemPropertiesFile></configuration></plugin></plugins></build></project>");
            var environment = environment(module);
            environment.runtimeConfiguration = MavenTestRuntimeConfiguration.parse(exported, module, "", Map.of())
                    .withOrigin("IDEA Maven settings").withJvmArguments(List.of("-ea"));
            assertEquals("before", environment.runtimeConfiguration.getSystemProperties().get("mode"));
            var first = CandidateEvidenceFactory.capture(environment, "class SubjectTest {}");
            assertTrue(CandidateEvidenceFactory.isCurrent(null, first));
            Files.writeString(properties, "mode=after!\n");
            Files.setLastModifiedTime(properties, modified);
            assertEquals(size, Files.size(properties));
            assertFalse("External runtime configuration changed after export; old evidence must expire",
                    CandidateEvidenceFactory.isCurrent(null, first));
            environment.runtimeConfiguration = MavenTestRuntimeConfiguration.parse(exported, module, "", Map.of());
            var next = CandidateEvidenceFactory.capture(environment, "class SubjectTest {}");
            assertFalse(first.identity().equals(next.identity()));
            assertFalse(next.toString().contains("after!"));
        } finally { CompiledTestArtifact.deleteRecursively(root); }
    }

    private ProjectTestEnvironment environment(Path root) {
        var environment = new ProjectTestEnvironment();
        environment.workingDirectory = root.toFile(); environment.sourceText = "class Subject {}";
        return environment;
    }
}
