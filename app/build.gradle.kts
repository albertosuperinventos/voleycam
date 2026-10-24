plugins {
  id("com.android.application")
}

android {
  namespace = "app.superinventos.sportcam"
  compileSdk = 36

  defaultConfig {
    applicationId = "app.superinventos.sportcam"
    minSdk = 29
    targetSdk = 35
    versionCode = 11
    versionName = "1.8.1"
  }

  signingConfigs {
    // Clave fija para que cada APK nuevo se instale encima del anterior sin desinstalar.
    create("sportcam") {
      storeFile = file("sportcam.keystore")
      storePassword = "sportcam123"
      keyAlias = "sportcam"
      keyPassword = "sportcam123"
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("sportcam")
    }
    debug {
      signingConfig = signingConfigs.getByName("sportcam")
    }
  }

  // La web de control (carpeta docs) va empaquetada dentro de la app
  sourceSets {
    getByName("main") {
      assets.srcDir("../docs")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")
  implementation("androidx.appcompat:appcompat:1.7.1")
  implementation("androidx.webkit:webkit:1.12.1")
  implementation("com.google.zxing:core:3.5.3")
  implementation("com.google.android.gms:play-services-auth:21.2.0")
  implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
  implementation("androidx.media3:media3-transformer:1.4.1")
  implementation("androidx.media3:media3-effect:1.4.1")
  implementation("androidx.media3:media3-common:1.4.1")
}
