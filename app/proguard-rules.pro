# Keep JNI bridge methods in RootMouseController
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.virtualmouse.app.RootMouseController { *; }
