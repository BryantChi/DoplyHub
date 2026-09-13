# Jsoup
-keeppackagenames org.jsoup.nodes

# OkHttp / Okio 不必自己寫 -dontwarn：兩者的 AAR（含 media3-datasource-okhttp）都帶了
# 精準的規則。寫成 `-dontwarn okhttp3.**` 會把真正缺類別的警告一起蓋掉，
# 出事時反而查不到。

# Room
-keep class * extends androidx.room.RoomDatabase

# Hilt
-keep class dagger.hilt.** { *; }

# Media3 不需要額外 keep：media3-ui / extractor / datasource-okhttp 各自的 AAR 都帶了
# consumer rules，反射進入點（SphericalGLSurfaceView、VideoDecoderGLSurfaceView、
# ExoPlayer 的名稱、ImageOutput、各 extractor）已經涵蓋。
# 原本那條 `-keep class androidx.media3.** { *; }` 讓 seeds.txt 的 40,335 條裡有
# 38,379 條是 media3，等於 R8 對整個播放器完全沒優化到。
