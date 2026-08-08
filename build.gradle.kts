// Top-level build file where you can add configuration options common to all subprojects/modules.
buildscript {
    dependencies {
        classpath(libs.kgp)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}

allprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-Xlint:deprecation")
    }
}
