plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.8.21"
    id("org.jetbrains.intellij") version "1.13.3"
}

group = "com.example"
version = "1.0.0"

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

    plugins.set(arrayListOf("java", "gradle"))
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
        untilBuild.set("253.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
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
    implementation("org.gradle:gradle-tooling-api:7.3-20210825160000+0000")
    implementation("com.squareup.okhttp3:okhttp:4.9.0")
    implementation ("org.json:json:20210307")
}
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
