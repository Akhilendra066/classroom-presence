import java.util.Properties
import java.util.Base64

plugins { id("com.android.library"); kotlin("android"); kotlin("plugin.serialization"); id("com.google.devtools.ksp") }
android {
    namespace = "com.classroompresence.data"
    compileSdk = 36
    defaultConfig { minSdk = 33 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    val cloud = Properties().apply {
        rootProject.file("supabase.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val url = cloud.getProperty("SUPABASE_URL", "")
    val key = cloud.getProperty("SUPABASE_ANON_KEY", "")
    require(url.isEmpty() || url.matches(Regex("https://[a-z0-9-]+\\.supabase\\.co"))) { "Invalid Supabase URL" }
    require(key.isEmpty() || key.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid Supabase public key" }
    require(!key.startsWith("sb_secret_")) { "Never bundle a Supabase secret key" }
    if (key.count { it == '.' } == 2) {
        val payload = String(Base64.getUrlDecoder().decode(key.split('.')[1]))
        require(!payload.contains("service_role")) { "Never bundle a Supabase service-role key" }
    }
    defaultConfig {
        buildConfigField("String", "SUPABASE_URL", "\"$url\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$key\"")
    }
}
dependencies { implementation(project(":presence-core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1") }
