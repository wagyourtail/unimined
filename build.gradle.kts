import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URI

plugins {
    kotlin("jvm") version "1.7.10"
    `java-gradle-plugin`
    `maven-publish`
}
base {
    archivesName.set(project.properties["archives_base_name"] as String)
}

version = if (project.hasProperty("version_snapshot")) project.properties["version"] as String + "-SNAPSHOT" else project.properties["version"] as String
group = project.properties["maven_group"] as String

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    mavenCentral()
    maven {
        url = URI.create("https://maven.minecraftforge.net/")
    }
    maven {
        url = URI.create("https://maven.fabricmc.net/")
    }
}

sourceSets {
    create("api") {
        compileClasspath += main.get().compileClasspath
        runtimeClasspath += main.get().runtimeClasspath
    }
    create("mappings") {
        compileClasspath += main.get().compileClasspath + sourceSets["api"].output
        runtimeClasspath += main.get().runtimeClasspath + sourceSets["api"].output
    }
    create("minecraft") {
        compileClasspath += main.get().compileClasspath + sourceSets["mappings"].output + sourceSets["api"].output
        runtimeClasspath += main.get().runtimeClasspath + sourceSets["mappings"].output + sourceSets["api"].output
    }
    create("mod") {
        compileClasspath += main.get().compileClasspath + sourceSets["minecraft"].output + sourceSets["mappings"].output + sourceSets["api"].output
        runtimeClasspath += main.get().runtimeClasspath + sourceSets["minecraft"].output + sourceSets["mappings"].output + sourceSets["api"].output
    }
    create("sources") {
        compileClasspath += main.get().compileClasspath + sourceSets["mod"].output + sourceSets["minecraft"].output + sourceSets["api"].output
        runtimeClasspath += main.get().runtimeClasspath + sourceSets["mod"].output + sourceSets["minecraft"].output + sourceSets["api"].output
    }
    main {
        compileClasspath += sourceSets["sources"].output + sourceSets["mod"].output + sourceSets["minecraft"].output + sourceSets["mappings"].output + sourceSets["api"].output
        runtimeClasspath += sourceSets["sources"].output + sourceSets["mod"].output + sourceSets["minecraft"].output + sourceSets["mappings"].output + sourceSets["api"].output
    }
}

dependencies {
    testImplementation(kotlin("test"))
    // guava
    implementation("com.google.guava:guava:31.1-jre")

    // gson
    implementation("com.google.code.gson:gson:2.9.0")

    // artifact transformer
    implementation("net.minecraftforge:artifactural:3.0.8")

    // remapper
    implementation("net.fabricmc:tiny-remapper:0.8.6")

    // mappings
    implementation("net.fabricmc:mapping-io:0.3.0")

    // jetbrains annotations
    implementation("org.jetbrains:annotations-java5:23.0.0")

    // binpatcher
    implementation("net.minecraftforge:binarypatcher:1.1.1")

    // pack200 provided by apache commons-compress
    implementation("org.apache.commons:commons-compress:1.21")

    // aw
    implementation("net.fabricmc:access-widener:2.1.0")

    // at
    implementation("net.minecraftforge:accesstransformers:8.0.7")

    implementation("org.jetbrains.kotlinx:kotlinx-metadata-jvm:0.4.2") {
        isTransitive = false
    }
}

tasks.jar {
    from(sourceSets["api"].output, sourceSets["mappings"].output, sourceSets["minecraft"].output, sourceSets["mod"].output, sourceSets["main"].output, sourceSets["sources"].output)

    manifest {
        attributes.putAll(
            mapOf(
                "Implementation-Version" to project.version
            )
        )
    }

    // include subproject jar
    from(project(":fmlloadertargets").tasks.jar) {
        into("fmlloadertargets")
        rename(".+", "fmlloadertargets.jar")
    }
}

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        create("simplePlugin") {
            id = "xyz.wagyourtail.unimined"
            implementationClass = "xyz.wagyourtail.unimined.UniminedPlugin"
        }
    }
}

tasks.withType<JavaCompile> {
    val targetVersion = 8
    if (JavaVersion.current().isJava9Compatible) {
        options.release.set(targetVersion)
    }
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

publishing {
    repositories {
        maven {
            name = "WagYourMaven"
            if (project.hasProperty("version_snapshot")) {
                url = URI.create("https://maven.wagyourtail.xyz/snapshots/")
            } else {
                url = URI.create("https://maven.wagyourtail.xyz/releases/")
            }
            credentials {
                username = project.findProperty("mvn.user") as String? ?: System.getenv("USERNAME")
                password = project.findProperty("mvn.key") as String? ?: System.getenv("TOKEN")
            }
        }
    }
    publications {
        create<MavenPublication>("maven") {
            groupId = project.group as String
            artifactId = project.name
            version = project.version as String

            from(components["java"])
        }
    }
}