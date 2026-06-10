# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /Users/android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and change the file name in build.gradle.

# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any custom keep rules for Room or third-party libraries if needed
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>(...);
}
