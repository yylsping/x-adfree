-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# The coordinator and every hook callback are deliberate strong roots: the
# modern module entry instance is not guaranteed to remain strongly reachable
# after lifecycle callbacks return.
-keep,allowobfuscation class io.github.yylsping.xadfree.** {
    *;
}

# DexKit is loaded from a dynamically extracted native library. Keep all of
# its descriptors because JNI registration and FlatBuffers query classes are
# referenced reflectively/natively.
-keep class org.luckypray.dexkit.** { *; }
-keep class com.google.flatbuffers.** { *; }
-dontwarn org.luckypray.dexkit.**
