
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keepnames class **_Impl

-keepnames @androidx.room.Entity class *
-keepnames @androidx.room.ColumnInfo class *

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-dontwarn androidx.room.**
