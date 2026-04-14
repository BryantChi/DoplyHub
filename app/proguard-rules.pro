# Jsoup
-keeppackagenames org.jsoup.nodes

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Room
-keep class * extends androidx.room.RoomDatabase

# Hilt
-keep class dagger.hilt.** { *; }

# Media3
-keep class androidx.media3.** { *; }
