# GimyTV - Android TV App

## Project Overview
Android TV 影視串流 App，從 gimymax.com / gimytv.ai 抓取影片資料，提供遙控器友善的觀影體驗。
- **Why:** 網頁版在 TV 上體驗差（遙控器不友善、廣告多、無進度記錄）
- 所有功能開發以 TV 遙控器操作為優先，不需要考慮觸控

## Tech Stack
- Kotlin + Jetpack Compose for TV + Material3 TV
- Media3 ExoPlayer (HLS m3u8)
- Jsoup + OkHttp (web scraping)
- Room + DataStore (local storage)
- Hilt (DI), Coil (images), Coroutines + Flow
- Min SDK 23, Target SDK 35, AGP 8.7.3, Gradle 8.9

## Architecture
Pure client-side, no backend. Clean Architecture: UI → Domain → Data layers.

## Key Conventions
- Code comments and variable names in English
- Git commits in English
- User communication in Traditional Chinese (zh-TW)
- Design theme: Cinematic Dark (OLED black + red accent #E11D48)
