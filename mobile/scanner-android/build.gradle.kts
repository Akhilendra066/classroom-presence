plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "com.classroompresence.scanner"
    compileSdk = 36
    defaultConfig { minSdk = 33 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation(project(":presence-core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1") }
