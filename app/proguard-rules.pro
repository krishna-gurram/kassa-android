# SQLCipher calls back into these classes from native code.
-keep class net.zetetic.database.** { *; }
-keep interface net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

# Room's generated implementations are found by name at runtime.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Keep line numbers so crash traces stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
