# setup proguard
# keep native methods so jni works
-keepclasseswithmembernames class * {
    native <methods>;
}
