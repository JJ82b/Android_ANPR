rootProject.name = "androidipcamera"
include(":app")

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // JitPack repository for HyperLPR3
        maven { url = uri("https://jitpack.io") } // 加入這一行
    }
}