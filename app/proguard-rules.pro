# ProGuard / R8 rules for Monos JNI virtualization runtime

# Keep native methods and their enclosing classes from being renamed or stripped
-keepclasseswithmembernames class * {
    native <methods>;
}

# Explicitly keep JNI mappings for canvas and renderer bindings
-keep class com.monos.app.ui.components.X11CanvasViewKt {
    native <methods>;
}

-keep class com.monos.app.ui.components.CustomKeyboardOverlayKt {
    native <methods>;
}

-keep class com.monos.app.virtualization.ClipboardSyncManagerKt {
    native <methods>;
}

# Keep the models and serialized fields if any
-keep class com.monos.app.ui.DisplayState { *; }
-keep class com.monos.app.ui.DisplayMode { *; }
-keep class com.monos.app.ui.FilteringMode { *; }
-keep class com.monos.app.ui.ScaleMode { *; }
-keep class com.monos.app.ui.ScreenOrientation { *; }
-keep class com.monos.app.ui.InputMode { *; }

# Protect Jetpack Compose runtime attributes
-keepclassmembers class * extends androidx.compose.runtime.RecomposeScope { *; }

# Keep OkHttp and Okio if used
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
