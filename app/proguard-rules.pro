# CyberDrill ProGuard rules. Release builds currently ship without minification;
# keep Firebase AI Logic / ML Kit model classes if minification is enabled later.
-keep class com.google.firebase.** { *; }
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.mlkit.**
