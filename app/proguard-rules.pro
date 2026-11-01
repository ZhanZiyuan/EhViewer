-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-keepclassmembers,allowobfuscation class com.hippo.ehviewer.coil.AnimatedWebPDrawable {
    java.nio.ByteBuffer source;
}

-assumevalues public class androidx.compose.runtime.ComposeRuntimeFlags {
    static boolean isLinkBufferComposerEnabled() return true;
}

# Ktor logger
-dontwarn org.slf4j.impl.StaticLoggerBinder

# CMP resources 1.11.1 optionally reads instrumented assets and catches NoClassDefFoundError.
# Production assets use its manifest ContentProvider; keep other missing-class errors fatal.
-dontwarn androidx.test.platform.app.InstrumentationRegistry

# https://issuetracker.google.com/222232895
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.Sidecar*

-keepattributes LineNumberTable

-allowaccessmodification
-repackageclasses
