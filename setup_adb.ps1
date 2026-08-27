Copy-Item "arm64-v8a\bin\adb" -Destination "app\src\main\jniLibs\arm64-v8a\libadb.so" -Force
.\gradlew installDebug
