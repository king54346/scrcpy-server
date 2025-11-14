-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# ==================== 优化配置（降低激进度）====================
-allowaccessmodification
-repackageclasses

# ⚠️ 重要：不要使用过于激进的优化
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*


# ==================== Android 框架类（重要！）====================
# 保留所有 Android 系统类，避免 AbstractMethodError
-keep class android.content.** { *; }
-keep class android.provider.** { *; }
-keep class android.media.** { *; }
-keep interface android.media.** { *; }

# ==================== MediaCodec 特定修复 ====================

# 保留 MediaCodec 及其内部类
-keep class android.media.MediaCodec { *; }
-keep class android.media.MediaCodec$** { *; }
-keep class android.media.codec.** { *; }

# 保留 MediaCodec 回调
-keep interface android.media.MediaCodec$Callback {
    public void onInputBufferAvailable(android.media.MediaCodec, int);
    public void onOutputBufferAvailable(android.media.MediaCodec, int, android.media.MediaCodec$BufferInfo);
    public void onError(android.media.MediaCodec, android.media.MediaCodec$CodecException);
    public void onOutputFormatChanged(android.media.MediaCodec, android.media.MediaFormat);
}

# 保留 MediaFormat
-keep class android.media.MediaFormat { *; }
-keep class android.media.MediaFormat$** { *; }

# ==================== Scrcpy 音频相关 ====================

-keep class com.genymobile.scrcpy.** { *; }

# ==================== JNI 相关 ====================

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ==================== Android 组件 ====================

-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application

# ==================== 序列化 ====================

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}

# ==================== Service Binding ====================

-keep class * extends android.os.IInterface {
    public <methods>;
}

-keepclassmembers class * implements android.content.ServiceConnection {
    public void onServiceConnected(android.content.ComponentName, android.os.IBinder);
    public void onServiceDisconnected(android.content.ComponentName);
}

# ==================== 枚举 ====================

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}