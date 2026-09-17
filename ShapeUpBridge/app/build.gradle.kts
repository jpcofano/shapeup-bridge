plugins {
    alias(libs.plugins.android.application)
    // Lee app/google-services.json (fuera del repo) y genera default_web_client_id.
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.jpcofano.shapeupbridge"
    // androidx.core 1.19.0 exige compilar contra API 37 o superior.
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.jpcofano.shapeupbridge"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        // versionPuente de cada documento subido sale de BuildConfig.VERSION_NAME.
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // Samsung Health Data SDK 1.1.0. El .aar no trae POM, asi que sus dependencias de runtime
    // se declaran a mano: coroutines (API suspend), parcelize (Parceler de los Companion de
    // ReadDataRequest, DataResponse, etc.) y gson (lo usan ExerciseSession y clases internas).
    // Sin parcelize la app compila pero ReadDataRequest falla con NoClassDefFoundError.
    implementation(files("libs/samsung-health-data-api-1.1.0.aar"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlin.parcelize.runtime)
    implementation(libs.gson)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // PU2: transporte a Firestore con la cuenta de Google de la web de ShapeUp.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    // PU3: corrida periodica sin la app abierta.
    implementation(libs.androidx.work.runtime)

    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}