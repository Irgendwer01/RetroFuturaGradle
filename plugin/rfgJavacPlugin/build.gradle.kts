import com.github.jengelman.gradle.plugins.shadow.tasks.ConfigureShadowRelocation
import org.gradle.jvm.toolchain.internal.DefaultToolchainSpec

plugins {
  // Apply the Java Gradle plugin development plugin to add support for developing Gradle plugins
  id("java-library")
  id("scala")
  id("com.github.johnrengelman.shadow") version "7.1.2"
  id("com.palantir.git-version") version "0.15.0"
  id("maven-publish")
  id("com.diffplug.spotless") version "6.12.0"
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(8))
    vendor.set(JvmVendorSpec.ADOPTIUM)
  }
  withSourcesJar()
  withJavadocJar()
}

repositories { mavenCentral() }

spotless {
  encoding("UTF-8")
  java {
    toggleOffOn()
    importOrder()
    removeUnusedImports()
    palantirJavaFormat("1.1.0")
  }
  kotlinGradle {
    toggleOffOn()
    ktfmt("0.39")
    trimTrailingWhitespace()
    indentWithSpaces(4)
    endWithNewline()
  }
}

dependencies {
  // Get JDK_ROOT/lib/tools.jar
  shadow(
      files(
          javaToolchains.compilerFor(java.toolchain).map { tc ->
            File(tc.executablePath.asFile.parentFile.parentFile, "lib/tools.jar")
          }))
  // These are provided by the scala compiler that runs with the plugin enabled
  compileOnly("org.scala-lang:scala-library:2.11.1")
  compileOnly("org.scala-lang:scala-compiler:2.11.5")
  // Allow gradle to detect Scala version when running tests
  testRuntimeOnly("org.scala-lang:scala-library:2.11.1")
  testRuntimeOnly("org.scala-lang:scala-compiler:2.11.5")

  implementation("org.apache.commons:commons-lang3:3.12.0")
  implementation("net.bytebuddy:byte-buddy:1.12.20")

  annotationProcessor("com.google.auto.service:auto-service:1.0.1")
  compileOnly("com.google.auto.service:auto-service-annotations:1.0.1")
}

val gitVersion: groovy.lang.Closure<String> by extra

group = "com.gtnewhorizons"

version = gitVersion().removeSuffix(".dirty")

// Relocate all dependencies into a subpackage
// https://imperceptiblethoughts.com/shadow/configuration/relocation/#filtering-relocation
val taskRelocateShadowJar =
    tasks.register<ConfigureShadowRelocation>("relocateShadowJar") {
      target = tasks.shadowJar.get()
      prefix = "com.gtnewhorizons.retrofuturagradle.javac.shadow"
    }

tasks.shadowJar.configure {
  dependsOn(taskRelocateShadowJar)
  archiveClassifier.set("")
}

tasks.jar.configure {
  enabled = false
  dependsOn(tasks.shadowJar)
}

tasks.addRule("Pattern: runTestWithJava<VERSION>") {
  val taskName = this
  if (startsWith("runTestWithJava")) {
    val jVersion = taskName.removePrefix("runTestWithJava")
    tasks.register<JavaCompile>(taskName) {
      group = "RFG"
      description = "Run javac with the plugin enabled on the test class"
      dependsOn("shadowJar")
      outputs.upToDateWhen { false }
      options.isIncremental = false
      val toolchain = DefaultToolchainSpec(objects)
      toolchain.languageVersion.set(JavaLanguageVersion.of(jVersion))
      toolchain.vendor.set(JvmVendorSpec.ADOPTIUM)
      javaCompiler.set(javaToolchains.compilerFor(toolchain))
      classpath = project.tasks.named("shadowJar").get().outputs.files
      // Can't escape spaces, so use URL-encoding
      val replacementsFile = project.file("test/replacements.properties").toURI()
      options.compilerArgs.add(
          "-Xplugin:RetrofuturagradleTokenReplacement ${replacementsFile.toASCIIString()}")
      options.isFork = true
      if (toolchain.languageVersion.get().asInt() > 8) {
        options.forkOptions.jvmArgs =
            listOf(
                "--add-exports",
                "jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
                "--add-exports",
                "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                "--add-exports",
                "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
                "--add-exports",
                "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
                "--add-exports",
                "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
                "--add-opens",
                "jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
                "--add-opens",
                "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED")
      }
      val testTree = project.fileTree(project.layout.projectDirectory.dir("test"))
      testTree.include("**/*.java")
      source = testTree
      destinationDirectory.set(project.layout.buildDirectory.dir("test-out-" + jVersion))
    }
  }
}

tasks.register<ScalaCompile>("runTestWithScala") {
  group = "RFG"
  description = "Run scalac with the plugin enabled on the test class"
  dependsOn("shadowJar")
  outputs.upToDateWhen { false }
  val toolchain = DefaultToolchainSpec(objects)
  toolchain.languageVersion.set(JavaLanguageVersion.of(8))
  toolchain.vendor.set(JvmVendorSpec.ADOPTIUM)

  javaLauncher.set(javaToolchains.launcherFor(toolchain))
  analysisMappingFile.set(
      project.layout.buildDirectory.file("tmp/scala/compilerAnalysis/" + this.name + ".mapping"))
  val incrementalOptions = scalaCompileOptions.incrementalOptions
  incrementalOptions.analysisFile.set(
      project.layout.buildDirectory.file("tmp/scala/compilerAnalysis/" + this.name + ".analysis"))
  incrementalOptions.classfileBackupDir.set(
      project.layout.buildDirectory.file("tmp/scala/classfileBackup/" + this.name + ".bak"))
  dependsOn(analysisFiles)
  options.isIncremental = false

  classpath = configurations.testRuntimeClasspath.get()
  scalaCompilerPlugins = project.tasks.named("shadowJar").get().outputs.files
  // Can't escape spaces, so use URL-encoding
  val replacementsFile = project.file("test/replacements.properties").toURI()
  scalaCompileOptions.additionalParameters =
      listOf("-P:RetrofuturagradleScalaTokenReplacement:${replacementsFile.toASCIIString()}")
  options.isFork = true
  val testTree = project.fileTree(project.layout.projectDirectory.dir("test"))
  testTree.include("**/*.java", "**/*.scala")
  source = testTree
  destinationDirectory.set(project.layout.buildDirectory.dir("test-out-scala"))

  doFirst { org.apache.commons.io.FileUtils.deleteDirectory(destinationDirectory.get().asFile) }
}

tasks.named<JavaCompile>("runTestWithJava8")

tasks.named<JavaCompile>("runTestWithJava11")

tasks.named<JavaCompile>("runTestWithJava17")

tasks.register("runTest") {
  group = "RFG"
  description = "Run javac (8, 11, 17) with the plugin enabled on the test class"
  dependsOn("runTestWithJava8", "runTestWithJava11", "runTestWithJava17", "runTestWithScala")
}

publishing {
  publications {
    create<MavenPublication>("rfgJavacPlugin") {
      shadow.component(this)
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))
      artifactId = "rfg-javac-plugin"
    }
  }

  repositories {
    maven {
      url = uri("http://jenkins.usrv.eu:8081/nexus/content/repositories/releases")
      isAllowInsecureProtocol = true
      credentials {
        username = System.getenv("MAVEN_USER") ?: "NONE"
        password = System.getenv("MAVEN_PASSWORD") ?: "NONE"
      }
    }
  }
}
