plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.8.21"
    id("org.jetbrains.intellij") version "1.13.3"
}

group = "com.example"
version = "1.0-SNAPSHOT"

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

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set("2022.2.5")
    type.set("IC") // Target IDE Platform

    plugins.set(arrayListOf("java", "gradle"))
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("222")
        untilBuild.set("232.*")
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
    // https://mvnrepository.com/artifact/com.github.javaparser/javaparser-core
    implementation("com.github.javaparser:javaparser-core:3.25.1")
    // https://mvnrepository.com/artifact/com.github.javaparser/javaparser-symbol-solver-core
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.23.0")

    implementation("org.apache.commons:commons-lang3:3.12.0")// 添加 Apache Commons Lang 依赖
    implementation("org.gradle:gradle-tooling-api:7.3-20210825160000+0000")
    implementation("com.squareup.okhttp3:okhttp:4.9.0") // 添加HTTP客户端库的依赖。如果你打算使用OkHttp，添加这个依赖。请检查最新版本
}

