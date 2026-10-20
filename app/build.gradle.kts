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
    versionCode = 4
    versionName = "1.2.0"
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
}
