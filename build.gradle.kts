plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.8.21"
    id("org.jetbrains.intellij") version "1.13.3"
}

group = "com.example"
version = "1.0.15"

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

    plugins.set(arrayListOf("java"))
}

tasks {
    // 设置 Java 与 Kotlin 编译产物的 JVM 兼容版本
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.encoding = "UTF-8"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
        kotlinOptions.freeCompilerArgs += "-Xjsr305=strict"
    }

    patchPluginXml {
        sinceBuild.set("222")
        untilBuild.set("262.*")
    }

    runPluginVerifier {
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

    named<Test>("test") {
        // Real API evaluation is paid and must never run as part of the ordinary test suite.
        exclude("**/UnitMindRealApiEvaluationTest.class")
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
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation("com.alibaba:fastjson:1.2.83")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.1.0.202203080745-r"){
        exclude("org.slf4j", "slf4j-api")
    }
    implementation("org.eclipse.jdt:org.eclipse.jdt.core:3.24.0")
    implementation("org.eclipse.platform:org.eclipse.core.resources:3.14.0")
    implementation("org.eclipse.platform:org.eclipse.equinox.common:3.14.100")
    implementation("org.eclipse.platform:org.eclipse.core.runtime:3.20.100")
    // JavaParser 核心库，用于解析 Java 源码结构。
    implementation("com.github.javaparser:javaparser-core:3.25.10")
    // JavaParser 符号求解库，为后续更复杂的类型分析预留依赖。
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.25.10")

    implementation("org.apache.commons:commons-lang3:3.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.9.0")
    implementation ("org.json:json:20210307")
    // UnitMind 自带覆盖率执行运行时，不要求修改被测项目的构建文件。
    implementation("org.jacoco:org.jacoco.core:0.8.15")
    implementation("org.jacoco:org.jacoco.agent:0.8.15")
    implementation("org.junit.platform:junit-platform-console-standalone:1.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.18.0")
}
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
