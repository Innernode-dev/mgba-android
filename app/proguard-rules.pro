# ProGuard / R8 configuration for mGBA Android

# Preserve native method bindings
-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve JNI Core class and members
-keep class com.retro.mgba.core.** { *; }

# Room SQLite DB preservation
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
