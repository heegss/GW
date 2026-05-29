-keep class com.auro.portfolio.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
