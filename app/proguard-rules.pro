-keepattributes LineNumberTable,*Annotation*,Signature

-dontwarn io.github.libxposed.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-adaptresourcefilecontents META-INF/xposed/scope.list

-keep public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
    public void on*(...);
}

-keep class app.spotxero.** { *; }

-keep class io.github.libxposed.service.XposedProvider { *; }
