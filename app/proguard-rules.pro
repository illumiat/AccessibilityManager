# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# 【SP3】预备规则（当前 minifyEnabled=false，规则不生效；开启混淆时无需再补）
# Shizuku（rikka.*）：跨进程 AIDL/反射结构，keep 全量防裁剪
-keep class rikka.** { *; }
# WorkManager：ListenableWorker 子类由框架反射实例化
-keep class * extends androidx.work.ListenableWorker { *; }