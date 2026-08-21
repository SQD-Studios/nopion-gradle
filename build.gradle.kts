plugins {
  id("java-gradle-plugin")
  alias(libs.plugins.publish.plugin)
  alias(libs.plugins.indra)
  alias(libs.plugins.indra.spotless)
}

gradlePlugin {
  website = "https://github.com/SQD-Studios/nopion-gradle"
  vcsUrl = "https://github.com/SQD-Studios/nopion-gradle"

  plugins.register("nopion") {
    id = "net.chamosmp.nopion.gradle"
    displayName = "Nopion"
    description = "Gradle plugin for publishing to Nopion"
    tags = listOf("nopion", "publishing")
    implementationClass = "net.chamosmp.nopion.gradle.NopionPlugin"
  }
}

indra {
  apache2License()

  github("sqd-studios", "nopion-gradle")

  javaVersions {
    target(21)
  }
}

indraSpotlessLicenser {
  licenseHeaderFile(rootProject.file("license_header.txt"))
}

repositories {
  mavenCentral()
  gradlePluginPortal()
}

dependencies {
  compileOnlyApi(libs.jspecify)

  implementation(libs.guava)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.datatype.jsr310)
  implementation(libs.mammoth)
  implementation(libs.jgit)

  testImplementation(libs.junit)
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
