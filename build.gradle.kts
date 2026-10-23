import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.jetbrains.intellij.tasks.PrepareSandboxTask
import java.security.MessageDigest

plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.13.3"
}

group = "com.example"
version = "1.0.51"

// Old IDEA test launchers cannot decode Unicode classpath arguments on an ANSI Windows locale.
// Keep the source checkout unchanged and allow CI to place JVM bootstrap artifacts in an ASCII path.
providers.gradleProperty("unitmindBuildDirectory").orNull?.let {
    layout.buildDirectory.set(file(it))
}

base {
    archivesName.set("UnitMind")
}

repositories {
    maven { url = uri("https://plugins.jetbrains.com/maven") }
    mavenCentral()
    maven { url = uri("https://maven.aliyun.com/nexus/content/groups/public/") }
    maven { url = uri("https://maven.aliyun.com/repository/google")  }
    maven { url = uri("https://maven.aliyun.com/nexus/content/repositories/jcenter") }
    maven {
        url = uri("https://plugins.gradle.org/m2/")
    }

    mavenLocal()
    google()
}

// 配置 Gradle IntelliJ 插件，用于声明目标 IDE 版本和插件依赖。
// 参考文档：https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    pluginName.set("UnitMind")
    version.set("2022.2.5")
    type.set("IC") // 目标 IDE 平台为 IntelliJ IDEA Community

    plugins.set(arrayListOf("java", "maven"))
}

// OkHttp/Okio 的传递依赖不能私带另一套 Kotlin，必须复用目标 IDEA 的运行时。
// 仅设置 kotlin.stdlib.default.dependency 无法排除 Java 依赖带入的 stdlib。
configurations.configureEach {
    listOf("kotlin-stdlib", "kotlin-stdlib-common", "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8")
        .forEach { exclude(group = "org.jetbrains.kotlin", module = it) }
}

// 仅作为资源打包，绝不进入插件/IDE 的运行时 classpath。
val bundledMockitoJava8 by configurations.creating
val bundledMockitoJava11 by configurations.creating
listOf(bundledMockitoJava8, bundledMockitoJava11).forEach {
    it.isCanBeConsumed = false
    listOf("org.junit.jupiter", "org.junit.platform", "org.opentest4j", "org.apiguardian")
        .forEach { group -> it.exclude(group = group) }
}
// Byte Buddy 仍支持 Java 8；统一使用同时支持新 JVM 的版本。
bundledMockitoJava8.resolutionStrategy.force("net.bytebuddy:byte-buddy:1.17.5", "net.bytebuddy:byte-buddy-agent:1.17.5")
val bundleTestRuntimes by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("generated/test-runtimes"))
    from(bundledMockitoJava8) { into("runtime/mockito/java8") }
    from(bundledMockitoJava11) { into("runtime/mockito/java11") }
    doLast {
        listOf("java8", "java11").forEach { variant ->
            val folder = destinationDir.resolve("runtime/mockito/$variant")
            val index = folder.listFiles()!!.filter { it.extension == "jar" }.sortedBy { it.name }.joinToString("\n") {
                val hash = MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
                "${it.name} $hash"
            }
            folder.resolve("index.txt").writeText(index + "\n")
        }
    }
}
sourceSets.main { resources.srcDir(bundleTestRuntimes) }

tasks {
    // 设置 Java 编译产物的 JVM 兼容版本
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.encoding = "UTF-8"
    }

    patchPluginXml {
        sinceBuild.set("222")
        untilBuild.set("262.*")
    }

    runPluginVerifier {
        providers.gradleProperty("unitmindVerifierJavaHome").orNull?.let { runtimeDir.set(it) }
        failureLevel.set(listOf(
            org.jetbrains.intellij.tasks.RunPluginVerifierTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            org.jetbrains.intellij.tasks.RunPluginVerifierTask.FailureLevel.INVALID_PLUGIN,
            org.jetbrains.intellij.tasks.RunPluginVerifierTask.FailureLevel.MISSING_DEPENDENCIES))
        val localIdea262Path = providers.gradleProperty("idea262Path").orNull
        if (localIdea262Path.isNullOrBlank()) {
            ideVersions.set(listOf("IC-2022.2.5", "IC-2025.3", "IU-2026.2.0.1"))
        } else {
            ideVersions.set(listOf("IC-2022.2.5", "IC-2025.3"))
            val localIdeaHome = file(localIdea262Path).let {
                if (it.name.endsWith(".app")) it.resolve("Contents") else it
            }
            localPaths.set(listOf(localIdeaHome))
        }
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }

    withType<Test>().configureEach {
        val sandbox = named<PrepareSandboxTask>("prepareSandbox")
        dependsOn(sandbox)
        doFirst {
            systemProperty("unitmind.test.pluginLibDirectory",
                sandbox.get().destinationDir.resolve("${sandbox.get().pluginName.get()}/lib").absolutePath)
        }
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        systemProperty("unitmind.test.gradleExecutable", gradle.gradleHomeDir!!.resolve(if (windows) "bin/gradle.bat" else "bin/gradle").absolutePath)
        systemProperty("unitmind.test.java8.home", providers.gradleProperty("unitmindTestJava8Home").orElse("").get())
        listOf(11, 21, 25).forEach { feature ->
            systemProperty("unitmind.test.java$feature.home", providers.gradleProperty("unitmindTestJava${feature}Home").orElse("").get())
        }
        systemProperty("unitmind.test.setupFixturesOnline", providers.gradleProperty("unitmindSetupFixturesOnline").orElse("false").get())
    }

    named<Test>("test") {
        exclude("**/UnitMindRealApiEvaluationTest.class")
    }

    register<Test>("windowsCompatibilityTest") {
        group = "verification"
        description = "Runs native Windows process, encoding, build export, coverage and write-gate regressions."
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        outputs.upToDateWhen { false }
        include("**/WindowsBatchExecutionTest.class", "**/LongCommandLineLauncherTest.class",
                "**/BundledMockitoRuntimeTest.class",
                "**/ProcessTreeLifecycleTest.class", "**/ProjectFileEncodingIntegrationTest.class",
                "**/BuildRuntimeConfigurationIntegrationTest.class", "**/ProjectTestRuntimeConfigurationTest.class",
                "**/TestSandboxCompilerTest.class", "**/TestRuntimeExecutionTest.class",
                "**/TestFileWriterGateTest.class", "**/FinalTestFileReportPanelTest.class",
                "**/PluginHttpClassLoadingTest.class", "**/TestDependencySetupTest.class",
                "**/RuntimeWriteGateIntegrationTest.class", "**/TestClassMergerTest.class",
                "**/TestBuildFileExecutionTest.class")
        doFirst {
            check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                "windowsCompatibilityTest requires a real Windows host; ordinary test can exercise portable contracts on other OSes."
            }
        }
    }

    named<Zip>("buildPlugin") {
        // 检查最终发行包，也能发现未来依赖将 Kotlin 类打进其他名称 JAR 的情况。
        doLast {
            ZipFile(archiveFile.get().asFile).use { distribution ->
                distribution.entries().asSequence().filter { it.name.endsWith(".jar") }.forEach { jar ->
                    ZipInputStream(distribution.getInputStream(jar)).use { contents ->
                        var entry = contents.nextEntry
                        while (entry != null) {
                            check(!(entry.name.startsWith("kotlin/") && entry.name.endsWith(".class"))) {
                                "Plugin must use IDEA's Kotlin runtime; bundled class: ${jar.name}!/${entry.name}"
                            }
                            entry = contents.nextEntry
                        }
                    }
                }
            }
        }
    }

    register<Test>("unitMindEval") {
        group = "verification"
        description = "Runs the checkpointed UnitMind real-API generation evaluation suite."
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        include("**/UnitMindRealApiEvaluationTest.class")
        maxParallelForks = 1
        maxHeapSize = "3g"
        failFast = false
        outputs.upToDateWhen { false }

        systemProperty("unitmind.eval.enabled", "true")
        systemProperty("unitmind.eval.root", rootProject.projectDir.absolutePath)
        systemProperty("unitmind.eval.reports",
                layout.buildDirectory.dir("reports/unitmind-eval").get().asFile.absolutePath)
        systemProperty("unitmind.eval.profile",
                providers.gradleProperty("unitmindEvalProfile").orElse("regular").get())
        systemProperty("unitmind.eval.case",
                providers.gradleProperty("unitmindEvalCase").orElse("").get())
        systemProperty("unitmind.eval.resume",
                providers.gradleProperty("unitmindEvalResume").orElse("").get())
        systemProperty("unitmind.eval.promoteBaseline",
                providers.gradleProperty("unitmindEvalPromoteBaseline").orElse("false").get())
        systemProperty("unitmind.eval.gitRevision",
                providers.environmentVariable("GIT_COMMIT").orElse("unknown").get())

        testLogging {
            events("passed", "failed", "skipped")
            showStandardStreams = true
        }
    }
}
dependencies {
    add(bundledMockitoJava8.name, "org.mockito:mockito-core:4.11.0")
    add(bundledMockitoJava8.name, "org.mockito:mockito-junit-jupiter:4.11.0")
    add(bundledMockitoJava11.name, "org.mockito:mockito-core:5.18.0")
    add(bundledMockitoJava11.name, "org.mockito:mockito-junit-jupiter:5.18.0")
    // JavaParser 核心库，用于解析 Java 源码结构。
    implementation("com.github.javaparser:javaparser-core:3.25.10")
    implementation("com.squareup.okhttp3:okhttp:4.9.0")
    implementation ("org.json:json:20210307")
    // UnitMind 自带覆盖率执行运行时，不要求修改被测项目的构建文件。
    implementation("org.jacoco:org.jacoco.core:0.8.15")
    implementation("org.jacoco:org.jacoco.agent:0.8.15")
    implementation("org.junit.platform:junit-platform-console-standalone:1.12.0")
    testImplementation("junit:junit:4.13.2")
    // Real property injection/context-cache regressions; Spring never enters the plugin runtime.
    testImplementation("org.springframework:spring-context:6.2.11")
    testImplementation("org.springframework:spring-test:6.2.11")
    // UnitMind validates generated tests against the IDE module's test compile classpath.
    // The standalone console launcher bundles Jupiter only for runtime, so declare the
    // Jupiter API directly to make JUnit 5 visible to IntelliJ and javac.
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.12.0")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.18.0")
    testImplementation("org.projectlombok:lombok:1.18.36")
}
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
