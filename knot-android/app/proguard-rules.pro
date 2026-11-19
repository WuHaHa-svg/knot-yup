# Shizuku
-dontwarn dev.rikka.**
-keep class dev.rikka.** { *; }

# 正式版剔除调试日志：v/d/i 无副作用可安全移除；w/e 保留（线上 adb logcat 取证）
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
