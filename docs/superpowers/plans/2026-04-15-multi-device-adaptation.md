# Multi-Device Adaptation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adapt GimyTV from TV-only to support phones and tablets in a single APK, using WindowSizeClass-based inline layout branching.

**Architecture:** Single APK detects device type via `WindowSizeClass` + Leanback feature flag. Each Screen composable branches layout internally. Navigation switches between BottomNavigation (phone), NavigationRail (tablet), and existing sidebar (TV). Player gains embedded mode + touch gestures on mobile.

**Tech Stack:** Compose Material3 + TV Material3 (dual), WindowSizeClass, ExoPlayer (unchanged), DataStore (theme pref)

**Design Spec:** `docs/superpowers/specs/2026-04-15-multi-device-adaptation-design.md`

**Commit convention:** Chinese messages, no AI attribution.

---

## Task 1: Build 配置與 Manifest 更新

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts:15` (minSdk)
- Modify: `app/build.gradle.kts:45-108` (dependencies)
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: 更新 libs.versions.toml，新增 window-size-class**

```toml
# [libraries] section 新增：
material3-window-size = { group = "androidx.compose.material3", name = "material3-window-size-class" }
```

Note: version managed by existing compose-bom, no version.ref needed.

- [ ] **Step 2: 更新 build.gradle.kts**

```kotlin
// android block: line 15
minSdk = 28  // was 23

// dependencies block 新增:
implementation(libs.material3.window.size)
```

- [ ] **Step 3: 更新 AndroidManifest.xml**

```xml
<!-- Line 8-9: leanback required → false -->
<uses-feature
    android:name="android.software.leanback"
    android:required="false" />

<!-- Line 26: add orientation|screenSize to configChanges -->
<activity
    android:name=".ui.MainActivity"
    android:exported="true"
    android:configChanges="keyboard|keyboardHidden|navigation|orientation|screenSize|smallestScreenSize">

<!-- Line 28-29: add LAUNCHER alongside LEANBACK_LAUNCHER -->
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
```

- [ ] **Step 4: Build 驗證**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/AndroidManifest.xml
git commit -m "適配手機平板：更新 Build 配置與 Manifest"
```

---

## Task 2: Dimensions 系統與 CompositionLocals

**Files:**
- Create: `app/src/main/java/com/gimy/tv/ui/theme/Dimensions.kt`

- [ ] **Step 1: 建立 Dimensions.kt**

```kotlin
package com.gimy.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class GimyDimensions(
    val screenHorizontalPadding: Dp,
    val screenVerticalPadding: Dp,
    val cardWidth: Dp,
    val cardHeight: Dp,
    val cardSpacing: Dp,
    val heroBannerHeight: Dp,
    val coverWidth: Dp,
    val coverHeight: Dp,
    val gridMinCellWidth: Dp,
    val episodeColumns: Int,
    val loadingIndicatorSize: Dp,
)

val TvDimensions = GimyDimensions(
    screenHorizontalPadding = 48.dp,
    screenVerticalPadding = 20.dp,
    cardWidth = 154.dp,
    cardHeight = 248.dp,
    cardSpacing = 14.dp,
    heroBannerHeight = 340.dp,
    coverWidth = 175.dp,
    coverHeight = 250.dp,
    gridMinCellWidth = 166.dp,
    episodeColumns = 14,
    loadingIndicatorSize = 48.dp,
)

val TabletDimensions = GimyDimensions(
    screenHorizontalPadding = 24.dp,
    screenVerticalPadding = 16.dp,
    cardWidth = 130.dp,
    cardHeight = 200.dp,
    cardSpacing = 12.dp,
    heroBannerHeight = 280.dp,
    coverWidth = 150.dp,
    coverHeight = 220.dp,
    gridMinCellWidth = 140.dp,
    episodeColumns = 10,
    loadingIndicatorSize = 40.dp,
)

val PhoneDimensions = GimyDimensions(
    screenHorizontalPadding = 16.dp,
    screenVerticalPadding = 12.dp,
    cardWidth = 110.dp,
    cardHeight = 170.dp,
    cardSpacing = 10.dp,
    heroBannerHeight = 200.dp,
    coverWidth = 120.dp,
    coverHeight = 180.dp,
    gridMinCellWidth = 110.dp,
    episodeColumns = 6,
    loadingIndicatorSize = 36.dp,
)

val LocalDimensions = staticCompositionLocalOf { TvDimensions }
val LocalIsTelevision = staticCompositionLocalOf { true }
```

- [ ] **Step 2: Build 驗證**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/gimy/tv/ui/theme/Dimensions.kt
git commit -m "新增裝置尺寸系統與 CompositionLocals"
```

---

## Task 3: Theme 系統改造

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/ui/theme/Theme.kt`

- [ ] **Step 1: 改造 Theme.kt — 新增淺色配色、雙主題支援**

Current Theme.kt defines `CinemaDarkScheme` (TV-only darkColorScheme) and `GimyTVTheme` wrapping `androidx.tv.material3.MaterialTheme`.

Replace the entire file with:

```kotlin
package com.gimy.tv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api

// ── Cinema Color Palette ──
val CinemaBlack = Color(0xFF000000)
val CinemaBase = Color(0xFF0A0A0A)
val CinemaElevated = Color(0xFF141414)
val CinemaCard = Color(0xFF1A1A1A)
val CinemaSurface = Color(0xFF1E1E1E)
val CinemaBorder = Color(0xFF2A2A2A)

val CinemaRed = Color(0xFFE11D48)
val CinemaRedDim = Color(0xFFBE123C)
val CinemaIndigo = Color(0xFF6366F1)

val CinemaTextPrimary = Color(0xFFE5E5E5)
val CinemaTextMuted = Color(0xFF737373)
val CinemaGold = Color(0xFFF59E0B)

// ── Light Theme Colors ──
val CinemaLightBackground = Color(0xFFF5F5F5)
val CinemaLightSurface = Color(0xFFFFFFFF)
val CinemaLightCard = Color(0xFFFFFFFF)
val CinemaLightElevated = Color(0xFFFAFAFA)
val CinemaLightBorder = Color(0xFFE0E0E0)
val CinemaLightTextPrimary = Color(0xFF1A1A1A)
val CinemaLightTextMuted = Color(0xFF737373)

// ── TV Dark Color Scheme (existing, unchanged) ──
@OptIn(ExperimentalTvMaterial3Api::class)
private val TvDarkColorScheme = androidx.tv.material3.darkColorScheme(
    primary = CinemaRed,
    onPrimary = Color.White,
    primaryContainer = CinemaRedDim,
    secondary = CinemaIndigo,
    background = CinemaBlack,
    onBackground = CinemaTextPrimary,
    surface = CinemaSurface,
    onSurface = CinemaTextPrimary,
    surfaceVariant = CinemaCard,
    onSurfaceVariant = CinemaTextMuted,
    border = CinemaBorder,
)

// ── Mobile Dark Color Scheme ──
private val MobileDarkColorScheme = androidx.compose.material3.darkColorScheme(
    primary = CinemaRed,
    onPrimary = Color.White,
    primaryContainer = CinemaRedDim,
    secondary = CinemaIndigo,
    background = CinemaBlack,
    onBackground = CinemaTextPrimary,
    surface = CinemaSurface,
    onSurface = CinemaTextPrimary,
    surfaceVariant = CinemaCard,
    onSurfaceVariant = CinemaTextMuted,
    outline = CinemaBorder,
)

// ── Mobile Light Color Scheme ──
private val MobileLightColorScheme = androidx.compose.material3.lightColorScheme(
    primary = CinemaRed,
    onPrimary = Color.White,
    primaryContainer = CinemaRedDim,
    secondary = CinemaIndigo,
    background = CinemaLightBackground,
    onBackground = CinemaLightTextPrimary,
    surface = CinemaLightSurface,
    onSurface = CinemaLightTextPrimary,
    surfaceVariant = CinemaLightCard,
    onSurfaceVariant = CinemaLightTextMuted,
    outline = CinemaLightBorder,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GimyTheme(
    isTelevision: Boolean = true,
    darkTheme: Boolean = if (isTelevision) true else isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalIsTelevision provides isTelevision) {
        if (isTelevision) {
            androidx.tv.material3.MaterialTheme(
                colorScheme = TvDarkColorScheme,
                content = content
            )
        } else {
            val colorScheme = if (darkTheme) MobileDarkColorScheme else MobileLightColorScheme
            androidx.compose.material3.MaterialTheme(
                colorScheme = colorScheme,
                content = content
            )
        }
    }
}

// Keep backward-compatible alias
@Composable
fun GimyTVTheme(content: @Composable () -> Unit) = GimyTheme(isTelevision = true, content = content)
```

- [ ] **Step 2: Build 驗證**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL (existing code still uses `GimyTVTheme` which now delegates to `GimyTheme`)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/gimy/tv/ui/theme/Theme.kt
git commit -m "Theme 改造：新增淺色主題與手機端 Material3 支援"
```

---

## Task 4: 共用元件適配

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/ui/components/VodCard.kt`
- Create: `app/src/main/java/com/gimy/tv/ui/components/GimyComponents.kt`

- [ ] **Step 1: 建立 GimyComponents.kt — 跨平台 Button 和 Surface 包裝**

```kotlin
package com.gimy.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.gimy.tv.ui.theme.LocalIsTelevision
import com.gimy.tv.ui.theme.CinemaRed
import com.gimy.tv.ui.theme.CinemaCard
import com.gimy.tv.ui.theme.CinemaTextPrimary

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GimyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(6.dp),
    containerColor: Color = CinemaRed,
    contentColor: Color = Color.White,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 9.dp),
    content: @Composable RowScope.() -> Unit
) {
    if (LocalIsTelevision.current) {
        androidx.tv.material3.Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = androidx.tv.material3.ButtonDefaults.shape(shape = shape),
            colors = androidx.tv.material3.ButtonDefaults.colors(
                containerColor = containerColor,
                contentColor = contentColor,
            ),
            contentPadding = contentPadding,
            content = content,
        )
    } else {
        androidx.compose.material3.Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor,
            ),
            contentPadding = contentPadding,
            content = content,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GimyOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
    content: @Composable RowScope.() -> Unit
) {
    if (LocalIsTelevision.current) {
        androidx.tv.material3.OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shape = androidx.tv.material3.ButtonDefaults.shape(shape = shape),
            contentPadding = contentPadding,
            content = content,
        )
    } else {
        androidx.compose.material3.OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            border = BorderStroke(1.dp, CinemaRed),
            contentPadding = contentPadding,
            content = content,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GimySurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(0.dp),
    color: Color = CinemaCard,
    contentColor: Color = CinemaTextPrimary,
    content: @Composable () -> Unit
) {
    if (LocalIsTelevision.current) {
        androidx.tv.material3.Surface(
            modifier = modifier,
            shape = shape,
            colors = androidx.tv.material3.SurfaceDefaults.colors(
                containerColor = color,
                contentColor = contentColor,
            ),
            content = { content() },
        )
    } else {
        androidx.compose.material3.Surface(
            modifier = modifier,
            shape = shape,
            color = color,
            contentColor = contentColor,
            content = content,
        )
    }
}
```

- [ ] **Step 2: 改造 VodCard.kt — 響應式尺寸 + 觸控/焦點分流**

Replace the entire VodCard composable. Key changes:
- Read card size from `LocalDimensions` instead of hardcoded 154×248
- TV: keep `Card` with `focusedScale` and `focusedBorder`
- Mobile: use standard `Card` with `clickable` + ripple

```kotlin
package com.gimy.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import coil.compose.AsyncImage
import com.gimy.tv.domain.model.SourceType
import com.gimy.tv.domain.model.Vod
import com.gimy.tv.ui.theme.*

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VodCard(
    vod: Vod,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val dims = LocalDimensions.current
    val isTV = LocalIsTelevision.current
    val cardShape = RoundedCornerShape(8.dp)

    if (isTV) {
        // TV: existing card with focus animations
        var isFocused by remember { mutableStateOf(false) }
        androidx.tv.material3.Card(
            onClick = onClick,
            onLongClick = onLongClick ?: {},
            modifier = modifier
                .width(dims.cardWidth)
                .height(dims.cardHeight)
                .onFocusChanged { isFocused = it.isFocused },
            shape = CardDefaults.shape(shape = cardShape),
            scale = CardDefaults.scale(focusedScale = 1.05f),
            border = CardDefaults.border(
                focusedBorder = androidx.tv.material3.Border(
                    border = BorderStroke(2.dp, CinemaRed), shape = cardShape
                )
            ),
            colors = CardDefaults.colors(containerColor = CinemaCard),
        ) {
            VodCardContent(vod, dims)
        }
    } else {
        // Mobile: standard card with touch feedback
        androidx.compose.material3.Card(
            modifier = modifier
                .width(dims.cardWidth)
                .height(dims.cardHeight)
                .clip(cardShape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
            shape = cardShape,
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = CinemaCard,
            ),
        ) {
            VodCardContent(vod, dims)
        }
    }
}

@Composable
private fun VodCardContent(vod: Vod, dims: GimyDimensions) {
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = vod.coverUrl,
            contentDescription = vod.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // Bottom gradient
        Box(
            Modifier
                .fillMaxWidth()
                .height(82.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                    )
                )
        )
        // Title
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            androidx.compose.material3.Text(
                text = vod.title,
                color = CinemaTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Source badge (top-left)
        val sourceName = when (vod.sourceType) {
            SourceType.GIMY -> "Gimy"
            SourceType.MOVIEFFM -> "MovieFFM"
        }
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
                .background(CinemaRed.copy(alpha = 0.85f), RoundedCornerShape(3.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp)
        ) {
            androidx.compose.material3.Text(
                text = sourceName, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold
            )
        }
        // Status badge (top-right)
        if (vod.status.isNotBlank()) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(CinemaBlack.copy(alpha = 0.7f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                androidx.compose.material3.Text(
                    text = vod.status, color = CinemaGold, fontSize = 9.sp
                )
            }
        }
    }
}
```

- [ ] **Step 3: Build 驗證**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/gimy/tv/ui/components/GimyComponents.kt \
       app/src/main/java/com/gimy/tv/ui/components/VodCard.kt
git commit -m "共用元件適配：VodCard 響應式尺寸、GimyButton/GimySurface 跨平台包裝"
```

---

## Task 5: 導航框架重構

**Files:**
- Create: `app/src/main/java/com/gimy/tv/ui/components/AdaptiveNavigation.kt`
- Modify: `app/src/main/java/com/gimy/tv/ui/MainActivity.kt`

- [ ] **Step 1: 建立 AdaptiveNavigation.kt**

```kotlin
package com.gimy.tv.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.gimy.tv.ui.navigation.Screen
import com.gimy.tv.ui.theme.*

data class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

val navItems = listOf(
    NavItem(Screen.Home.route, "首頁", Icons.Default.Home),
    NavItem(Screen.Search.route, "搜尋", Icons.Default.Search),
    NavItem("browse/gimy/0", "分類", Icons.Default.VideoLibrary),
    NavItem(Screen.Favorites.route, "收藏", Icons.Default.Favorite),
    NavItem(Screen.History.route, "紀錄", Icons.Default.History),
)

@Composable
fun AdaptiveScaffold(
    widthSizeClass: WindowWidthSizeClass,
    isTelevision: Boolean,
    navController: NavController,
    content: @Composable () -> Unit
) {
    if (isTelevision) {
        // TV: no scaffold, just content (existing sidebar handled inside HomeScreen)
        content()
        return
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Hide nav bar on detail/player screens
    val showNavBar = currentRoute in listOf(
        Screen.Home.route, Screen.Search.route, Screen.Favorites.route, Screen.History.route
    ) || currentRoute?.startsWith("browse/") == true

    when (widthSizeClass) {
        WindowWidthSizeClass.Compact -> {
            // Phone: bottom navigation
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { content() }
                if (showNavBar) {
                    NavigationBar(
                        containerColor = CinemaElevated,
                        contentColor = CinemaTextPrimary,
                    ) {
                        navItems.forEach { item ->
                            val selected = isNavItemSelected(currentRoute, item.route)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateToTab(navController, item.route) },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label, fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = CinemaRed,
                                    selectedTextColor = CinemaRed,
                                    unselectedIconColor = CinemaTextMuted,
                                    unselectedTextColor = CinemaTextMuted,
                                    indicatorColor = CinemaRed.copy(alpha = 0.12f),
                                ),
                            )
                        }
                    }
                }
            }
        }
        else -> {
            // Tablet: navigation rail
            Row(Modifier.fillMaxSize()) {
                if (showNavBar) {
                    NavigationRail(
                        containerColor = CinemaElevated,
                        contentColor = CinemaTextPrimary,
                    ) {
                        Spacer(Modifier.weight(1f))
                        navItems.forEach { item ->
                            val selected = isNavItemSelected(currentRoute, item.route)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToTab(navController, item.route) },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label, fontSize = 11.sp) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = CinemaRed,
                                    selectedTextColor = CinemaRed,
                                    unselectedIconColor = CinemaTextMuted,
                                    unselectedTextColor = CinemaTextMuted,
                                    indicatorColor = CinemaRed.copy(alpha = 0.12f),
                                ),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    }
                }
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }
}

private fun isNavItemSelected(currentRoute: String?, itemRoute: String): Boolean {
    if (currentRoute == itemRoute) return true
    // Browse routes: match any browse/* to the browse nav item
    if (itemRoute.startsWith("browse/") && currentRoute?.startsWith("browse/") == true) return true
    return false
}

private fun navigateToTab(navController: NavController, route: String) {
    navController.navigate(route) {
        popUpTo(Screen.Home.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
```

- [ ] **Step 2: 重構 MainActivity.kt**

Replace `MainActivity` setContent block. Key changes:
- Calculate `WindowSizeClass`
- Detect TV vs mobile
- Provide `LocalDimensions` and `LocalIsTelevision`
- Wrap NavHost in `AdaptiveScaffold`
- Switch from `GimyTVTheme` to `GimyTheme`

```kotlin
package com.gimy.tv.ui

import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gimy.tv.ui.browse.BrowseScreen
import com.gimy.tv.ui.components.AdaptiveScaffold
import com.gimy.tv.ui.components.SplashOverlay
import com.gimy.tv.ui.detail.DetailScreen
import com.gimy.tv.ui.favorites.FavoritesScreen
import com.gimy.tv.ui.history.HistoryScreen
import com.gimy.tv.ui.home.HomeScreen
import com.gimy.tv.ui.navigation.Screen
import com.gimy.tv.ui.player.PlayerScreen
import com.gimy.tv.ui.search.SearchScreen
import com.gimy.tv.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val isTelevision = remember {
                packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            }
            val widthSizeClass = windowSizeClass.widthSizeClass

            val dimensions = remember(widthSizeClass, isTelevision) {
                when {
                    isTelevision -> TvDimensions
                    widthSizeClass == WindowWidthSizeClass.Compact -> PhoneDimensions
                    else -> TabletDimensions
                }
            }

            GimyTheme(isTelevision = isTelevision) {
                CompositionLocalProvider(LocalDimensions provides dimensions) {
                    var showSplash by remember { mutableStateOf(true) }
                    var contentReady by remember { mutableStateOf(false) }

                    LaunchedEffect(Unit) {
                        contentReady = true
                        delay(1800)
                        showSplash = false
                    }

                    Box(Modifier.fillMaxSize()) {
                        if (contentReady) {
                            val navController = rememberNavController()

                            AdaptiveScaffold(
                                widthSizeClass = widthSizeClass,
                                isTelevision = isTelevision,
                                navController = navController,
                            ) {
                                NavHost(
                                    navController = navController,
                                    startDestination = Screen.Home.route,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    composable(Screen.Home.route) {
                                        HomeScreen(
                                            onVodClick = { sourceType, vodId ->
                                                navController.navigate(Screen.Detail.createRoute(sourceType.name, vodId))
                                            },
                                            onNavigate = { route -> navController.navigate(route) },
                                        )
                                    }
                                    composable(
                                        Screen.Browse.route,
                                        arguments = listOf(
                                            navArgument("sourceType") { type = NavType.StringType },
                                            navArgument("typeId") { type = NavType.IntType },
                                        )
                                    ) {
                                        BrowseScreen(
                                            onVodClick = { sourceType, vodId ->
                                                navController.navigate(Screen.Detail.createRoute(sourceType.name, vodId))
                                            },
                                            onBack = { navController.popBackStack() },
                                        )
                                    }
                                    composable(Screen.Search.route) {
                                        SearchScreen(
                                            onVodClick = { sourceType, vodId ->
                                                navController.navigate(Screen.Detail.createRoute(sourceType.name, vodId))
                                            },
                                            onBack = { navController.popBackStack() },
                                        )
                                    }
                                    composable(
                                        Screen.Detail.route,
                                        arguments = listOf(
                                            navArgument("sourceType") { type = NavType.StringType },
                                            navArgument("vodId") { type = NavType.LongType },
                                        )
                                    ) {
                                        DetailScreen(
                                            onPlayClick = { sourceType, vodId, sourceId, episodeNum ->
                                                navController.navigate(
                                                    Screen.Player.createRoute(sourceType.name, vodId, sourceId, episodeNum)
                                                )
                                            },
                                            onVodClick = { sourceType, vodId ->
                                                navController.navigate(Screen.Detail.createRoute(sourceType.name, vodId))
                                            },
                                            onBack = { navController.popBackStack() },
                                        )
                                    }
                                    composable(
                                        Screen.Player.route,
                                        arguments = listOf(
                                            navArgument("sourceType") { type = NavType.StringType },
                                            navArgument("vodId") { type = NavType.LongType },
                                            navArgument("sourceId") { type = NavType.IntType },
                                            navArgument("episodeNum") { type = NavType.IntType },
                                        )
                                    ) {
                                        PlayerScreen(onBack = { navController.popBackStack() })
                                    }
                                    composable(Screen.Favorites.route) {
                                        FavoritesScreen(
                                            onVodClick = { sourceType, vodId ->
                                                navController.navigate(Screen.Detail.createRoute(sourceType.name, vodId))
                                            },
                                            onBack = { navController.popBackStack() },
                                        )
                                    }
                                    composable(Screen.History.route) {
                                        HistoryScreen(
                                            onVodClick = { sourceType, vodId ->
                                                navController.navigate(Screen.Detail.createRoute(sourceType.name, vodId))
                                            },
                                            onBack = { navController.popBackStack() },
                                        )
                                    }
                                }
                            }
                        }
                        AnimatedVisibility(visible = showSplash, exit = fadeOut()) {
                            SplashOverlay()
                        }
                    }
                }
            }
        }
    }
}
```

**IMPORTANT:** The exact lambda signatures for each screen composable (onVodClick, onNavigate, onBack, onPlayClick) must match the current code. The exploration shows these are the existing signatures. Verify against current `MainActivity.kt` before implementing.

- [ ] **Step 3: Build 驗證**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/gimy/tv/ui/components/AdaptiveNavigation.kt \
       app/src/main/java/com/gimy/tv/ui/MainActivity.kt
git commit -m "導航框架重構：AdaptiveScaffold + BottomNav/NavigationRail"
```

---

## Task 6: HomeScreen 適配

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/ui/home/HomeScreen.kt`

- [ ] **Step 1: 替換所有硬編碼尺寸為 LocalDimensions**

At the top of the `HomeScreen` composable, add:
```kotlin
val dims = LocalDimensions.current
val isTV = LocalIsTelevision.current
```

Then apply these replacements throughout the file:

| Line(s) | Old | New |
|---------|-----|-----|
| 62 | `PaddingValues(bottom = 48.dp)` | `PaddingValues(bottom = dims.screenHorizontalPadding)` |
| 106 | `padding(horizontal = 48.dp, vertical = 20.dp)` | `padding(horizontal = dims.screenHorizontalPadding, vertical = dims.screenVerticalPadding)` |
| 164 | `padding(horizontal = 48.dp, vertical = 6.dp)` | `padding(horizontal = dims.screenHorizontalPadding, vertical = 6.dp)` |
| 165 | `.height(340.dp)` | `.height(dims.heroBannerHeight)` |
| 205 | `padding(start = 48.dp, ...)` | `padding(start = dims.screenHorizontalPadding, ...)` |
| 237 | `padding(start = 96.dp, ...)` | `padding(start = dims.screenHorizontalPadding * 2, ...)` |
| 289 | `padding(start = 48.dp, ...)` | `padding(start = dims.screenHorizontalPadding, ...)` |
| 303 | `PaddingValues(horizontal = 48.dp)` | `PaddingValues(horizontal = dims.screenHorizontalPadding)` |
| 303 | `spacedBy(14.dp)` | `spacedBy(dims.cardSpacing)` |
| 322-323 | `width(154.dp).height(248.dp)` (MoreCard) | `width(dims.cardWidth).height(dims.cardHeight)` |

- [ ] **Step 2: Guard D-pad 邏輯**

At `HomeScreen.kt:242-248` (onPreviewKeyEvent for DPAD_LEFT/RIGHT carousel nav), wrap with:
```kotlin
if (isTV) {
    Modifier.onPreviewKeyEvent { event ->
        // existing DPAD handling unchanged
    }
} else {
    Modifier
}
```

- [ ] **Step 3: 手機佈局微調**

For phone, the TopBar nav chips (Home/Search/Browse/Favorites/History) are redundant since there's a BottomNavigation. Hide them on mobile:

```kotlin
// Around line 106 (TopBar)
if (isTV) {
    // existing TopBar with nav chips
    Row(modifier = Modifier.padding(...)) { ... }
}
```

Keep the GimyTV logo visible on all devices but adjust padding.

- [ ] **Step 4: Build 驗證**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/gimy/tv/ui/home/HomeScreen.kt
git commit -m "HomeScreen 適配：響應式尺寸、D-pad 保護、手機導航精簡"
```

---

## Task 7: DetailScreen 適配

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/ui/detail/DetailScreen.kt`

- [ ] **Step 1: 新增 dimensions 引用並替換硬編碼尺寸**

At the top of the composable:
```kotlin
val dims = LocalDimensions.current
val isTV = LocalIsTelevision.current
```

Key replacements:

| Line | Old | New |
|------|-----|-----|
| 67 | `height(400.dp)` | `height(if (isTV) 400.dp else 250.dp)` |
| 76 | `padding(horizontal = 48.dp, vertical = 32.dp)` | `padding(horizontal = dims.screenHorizontalPadding, vertical = if (isTV) 32.dp else 16.dp)` |
| 81 | `width(175.dp).height(250.dp)` | `width(dims.coverWidth).height(dims.coverHeight)` |
| 135 | `padding(horizontal = 48.dp)` | `padding(horizontal = dims.screenHorizontalPadding)` |
| 217 | `padding(horizontal = 48.dp)` | `padding(horizontal = dims.screenHorizontalPadding)` |
| 232 | `padding(horizontal = 48.dp)` | `padding(horizontal = dims.screenHorizontalPadding)` |
| 235 | `chunked(14)` | `chunked(dims.episodeColumns)` |
| 243 | `width(56.dp)` | `width(if (isTV) 56.dp else 44.dp)` |

- [ ] **Step 2: 手機版直向佈局**

On phone (Compact), change the header from horizontal (poster left, info right) to vertical (poster top, info below):

```kotlin
// Around line 76 (header section)
if (isTV || widthSizeClass != WindowWidthSizeClass.Compact) {
    // Existing horizontal layout: Row { poster, info }
    Row(modifier = Modifier.padding(horizontal = dims.screenHorizontalPadding, vertical = 32.dp)) {
        // ... existing code
    }
} else {
    // Phone vertical layout: Column { poster, info }
    Column(
        modifier = Modifier.padding(horizontal = dims.screenHorizontalPadding, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = detail.coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(dims.coverWidth)
                .height(dims.coverHeight)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.height(12.dp))
        // Info column (title, meta, buttons) - same content as existing, just vertical
        Column { /* existing info content */ }
    }
}
```

**Note:** The exact info content (title, meta badges, action buttons) must be extracted into a shared `@Composable private fun DetailInfo(...)` function to avoid duplication between horizontal and vertical layouts.

- [ ] **Step 3: Build 驗證**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/gimy/tv/ui/detail/DetailScreen.kt
git commit -m "DetailScreen 適配：手機直向佈局、響應式尺寸"
```

---

## Task 8: SearchScreen + BrowseScreen 適配

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/ui/search/SearchScreen.kt`
- Modify: `app/src/main/java/com/gimy/tv/ui/browse/BrowseScreen.kt`

- [ ] **Step 1: SearchScreen — 替換硬編碼尺寸**

At the top of composable:
```kotlin
val dims = LocalDimensions.current
val isTV = LocalIsTelevision.current
```

Key replacements:

| Line | Old | New |
|------|-----|-----|
| 65 | `padding(horizontal = 48.dp, vertical = 20.dp)` | `padding(horizontal = dims.screenHorizontalPadding, vertical = dims.screenVerticalPadding)` |
| 98 | `.height(52.dp)` | `.height(if (isTV) 52.dp else 48.dp)` |
| 158 | `GridCells.Adaptive(166.dp)` | `GridCells.Adaptive(dims.gridMinCellWidth)` |

Also: on mobile, the TextField should use standard Material3 `OutlinedTextField` for proper soft keyboard support. Wrap with `if (isTV)` check to use TV TextField on TV and standard on mobile.

- [ ] **Step 2: BrowseScreen — 替換硬編碼尺寸**

At the top of composable:
```kotlin
val dims = LocalDimensions.current
```

Key replacements:

| Line | Old | New |
|------|-----|-----|
| 49 | `padding(horizontal = 48.dp, vertical = 20.dp)` | `padding(horizontal = dims.screenHorizontalPadding, vertical = dims.screenVerticalPadding)` |
| 95 | `GridCells.Adaptive(166.dp)` | `GridCells.Adaptive(dims.gridMinCellWidth)` |
| 97 | `PaddingValues(horizontal = 48.dp, vertical = 8.dp)` | `PaddingValues(horizontal = dims.screenHorizontalPadding, vertical = 8.dp)` |
| 109 | `width(154.dp).height(248.dp)` | `width(dims.cardWidth).height(dims.cardHeight)` |

- [ ] **Step 3: Build 驗證**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/gimy/tv/ui/search/SearchScreen.kt \
       app/src/main/java/com/gimy/tv/ui/browse/BrowseScreen.kt
git commit -m "SearchScreen + BrowseScreen 適配：響應式尺寸與輸入框調整"
```

---

## Task 9: FavoritesScreen + HistoryScreen 適配

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/ui/favorites/FavoritesScreen.kt`
- Modify: `app/src/main/java/com/gimy/tv/ui/history/HistoryScreen.kt`

- [ ] **Step 1: FavoritesScreen — 替換硬編碼尺寸**

```kotlin
val dims = LocalDimensions.current
```

| Line | Old | New |
|------|-----|-----|
| 44 | `GridCells.Adaptive(154.dp)` | `GridCells.Adaptive(dims.cardWidth)` |
| 44 | `PaddingValues(horizontal = 48.dp, vertical = 16.dp)` | `PaddingValues(horizontal = dims.screenHorizontalPadding, vertical = 16.dp)` |
| 45-46 | `spacedBy(14.dp)` | `spacedBy(dims.cardSpacing)` |
| 53 (PageHeader) | `padding(horizontal = 48.dp, vertical = 20.dp)` | `padding(horizontal = dims.screenHorizontalPadding, vertical = dims.screenVerticalPadding)` |

- [ ] **Step 2: HistoryScreen — 替換硬編碼尺寸**

```kotlin
val dims = LocalDimensions.current
```

| Line | Old | New |
|------|-----|-----|
| 50 | `padding(horizontal = 48.dp, vertical = 20.dp)` | `padding(horizontal = dims.screenHorizontalPadding, vertical = dims.screenVerticalPadding)` |
| 63 | `GridCells.Adaptive(154.dp)` | `GridCells.Adaptive(dims.cardWidth)` |
| 63 | `PaddingValues(horizontal = 48.dp, vertical = 8.dp)` | `PaddingValues(horizontal = dims.screenHorizontalPadding, vertical = 8.dp)` |
| 64-65 | `spacedBy(14.dp)` | `spacedBy(dims.cardSpacing)` |

Also: the `AlertDialog` on HistoryScreen (line 75-100) uses TV Material3 `AlertDialog`. On mobile, use standard Material3 `AlertDialog`:

```kotlin
if (LocalIsTelevision.current) {
    // existing TV AlertDialog
} else {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { pendingDelete = null },
        title = { Text("刪除記錄") },
        text = { Text("確定要刪除「${it.title}」的觀看記錄嗎？") },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { viewModel.delete(it); pendingDelete = null }) {
                Text("刪除", color = CinemaRed)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) {
                Text("取消")
            }
        },
    )
}
```

- [ ] **Step 3: Build 驗證**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/gimy/tv/ui/favorites/FavoritesScreen.kt \
       app/src/main/java/com/gimy/tv/ui/history/HistoryScreen.kt
git commit -m "FavoritesScreen + HistoryScreen 適配：響應式 Grid 與對話框"
```

---

## Task 10: PlayerViewModel 擴充

**Files:**
- Modify: `app/src/main/java/com/gimy/tv/ui/player/PlayerViewModel.kt`

- [ ] **Step 1: 新增全螢幕狀態**

Add to `PlayerUiState` data class (around line 15):
```kotlin
data class PlayerUiState(
    // ... existing fields unchanged ...
    val isFullscreen: Boolean = false,  // NEW
)
```

Add to `PlayerViewModel` class body:
```kotlin
fun toggleFullscreen() {
    _uiState.update { it.copy(isFullscreen = !it.isFullscreen) }
}

fun setFullscreen(fullscreen: Boolean) {
    _uiState.update { it.copy(isFullscreen = fullscreen) }
}
```

- [ ] **Step 2: Build 驗證**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/gimy/tv/ui/player/PlayerViewModel.kt
git commit -m "PlayerViewModel 擴充：新增全螢幕狀態管理"
```

---

## Task 11: PlayerScreen 嵌入模式與觸控手勢

**Files:**
- Create: `app/src/main/java/com/gimy/tv/ui/player/EmbeddedPlayerView.kt`
- Modify: `app/src/main/java/com/gimy/tv/ui/player/PlayerScreen.kt`
- Modify: `app/src/main/java/com/gimy/tv/ui/detail/DetailScreen.kt` (embed player)

- [ ] **Step 1: 建立 EmbeddedPlayerView.kt — 觸控手勢 + 控制列**

```kotlin
package com.gimy.tv.ui.player

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.gimy.tv.ui.theme.CinemaRed
import com.gimy.tv.ui.theme.CinemaTextPrimary
import kotlinx.coroutines.delay
import kotlin.math.absoluteValue

@Composable
fun EmbeddedPlayerView(
    player: ExoPlayer?,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    hasPrevEpisode: Boolean,
    hasNextEpisode: Boolean,
    modifier: Modifier = Modifier,
) {
    var showControls by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    // Auto-hide controls after 3 seconds
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(3000)
            showControls = false
        }
    }

    // Update position periodically
    LaunchedEffect(player) {
        while (true) {
            player?.let {
                currentPosition = it.currentPosition
                duration = it.duration.coerceAtLeast(0)
            }
            delay(500)
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        // Player view
        if (player != null) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        this.player = player
                        useController = false
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Touch gesture overlay
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showControls = !showControls },
                        onDoubleTap = { offset ->
                            val isLeftSide = offset.x < size.width / 2
                            val seekMs = if (isLeftSide) -10_000L else 10_000L
                            player?.let { onSeek(it.currentPosition + seekMs) }
                        },
                    )
                }
        )

        // Controls overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
            ) {
                // Center controls
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Previous episode
                    IconButton(
                        onClick = onPrevEpisode,
                        enabled = hasPrevEpisode,
                    ) {
                        Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    // Rewind 10s
                    IconButton(onClick = { player?.let { onSeek(it.currentPosition - 10_000) } }) {
                        Icon(Icons.Default.Replay10, "Rewind", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    // Play/Pause
                    IconButton(
                        onClick = {
                            player?.let {
                                if (it.isPlaying) it.pause() else it.play()
                            }
                        },
                    ) {
                        val isPlaying = player?.isPlaying == true
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            "Play/Pause",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                    // Forward 10s
                    IconButton(onClick = { player?.let { onSeek(it.currentPosition + 10_000) } }) {
                        Icon(Icons.Default.Forward10, "Forward", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    // Next episode
                    IconButton(
                        onClick = onNextEpisode,
                        enabled = hasNextEpisode,
                    ) {
                        Icon(Icons.Default.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }

                // Bottom bar: progress + fullscreen
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    // Progress slider
                    Slider(
                        value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                        onValueChange = { fraction ->
                            onSeek((fraction * duration).toLong())
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = CinemaRed,
                            activeTrackColor = CinemaRed,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Time + fullscreen
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${formatTime(currentPosition)} / ${formatTime(duration)}",
                            color = Color.White,
                            fontSize = 12.sp,
                        )
                        IconButton(onClick = onToggleFullscreen) {
                            Icon(
                                if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                "Fullscreen",
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
```

- [ ] **Step 2: 修改 PlayerScreen — 手機使用嵌入式播放器**

In `PlayerScreen.kt`, at the top of the composable:
```kotlin
val isTV = LocalIsTelevision.current
```

The existing PlayerScreen is fullscreen with `AndroidView(PlayerView)`. For TV, keep as-is. For mobile fullscreen mode, use the same layout but with touch controls:

```kotlin
// Inside PlayerScreen composable, wrap the existing AndroidView section:
if (isTV) {
    // Existing TV player with remote control handling — unchanged
    AndroidView(
        factory = { ... },
        modifier = Modifier.fillMaxSize()
    )
    // existing info bar, loading overlay, etc.
} else {
    // Mobile fullscreen: use EmbeddedPlayerView
    EmbeddedPlayerView(
        player = exoPlayer,
        isFullscreen = true,
        onToggleFullscreen = { navController.popBackStack() }, // exit fullscreen = go back
        onSeek = { pos -> exoPlayer?.seekTo(pos) },
        onPrevEpisode = { /* navigate to prev */ },
        onNextEpisode = { viewModel.nextEpisode() },
        hasPrevEpisode = uiState.episodeNum > 1,
        hasNextEpisode = uiState.episodeNum < uiState.totalEpisodes,
        modifier = Modifier.fillMaxSize(),
    )
}
```

- [ ] **Step 3: 修改 DetailScreen — 手機版嵌入播放器**

On mobile, when user taps play, instead of navigating to PlayerScreen, embed the player at the top of DetailScreen:

Add state to DetailScreen:
```kotlin
var isPlaying by remember { mutableStateOf(false) }
var playingSourceId by remember { mutableIntStateOf(0) }
var playingEpisodeNum by remember { mutableIntStateOf(0) }
```

At the top of the LazyColumn, before the cover image, add:
```kotlin
if (!isTV && isPlaying) {
    item {
        // Embedded player - 16:9 aspect ratio
        EmbeddedPlayerView(
            player = ...,  // Need to create/obtain ExoPlayer instance
            isFullscreen = false,
            onToggleFullscreen = {
                // Navigate to fullscreen PlayerScreen
                onPlayClick(sourceType, vodId, playingSourceId, playingEpisodeNum)
            },
            onSeek = { pos -> /* seek */ },
            onPrevEpisode = { /* prev */ },
            onNextEpisode = { /* next */ },
            hasPrevEpisode = playingEpisodeNum > 1,
            hasNextEpisode = ...,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        )
    }
}
```

**IMPORTANT ARCHITECTURAL NOTE:** The embedded player in DetailScreen requires an ExoPlayer instance managed at the DetailScreen level (not PlayerScreen). This means DetailScreen needs its own player lifecycle management. Options:

**Option A (Simpler):** On mobile, skip embedded mode for v1. Tapping play always opens fullscreen PlayerScreen with touch controls. This avoids the complexity of managing ExoPlayer in two places.

**Option B (Full spec):** Create a shared `PlayerManager` (Hilt singleton) that owns the ExoPlayer instance, usable by both DetailScreen (embedded) and PlayerScreen (fullscreen).

**Recommendation:** Implement Option A for v1 to avoid crashes. The mobile PlayerScreen already gets touch controls from Step 2. Embedded mode can be added in a follow-up iteration once the player architecture is refactored.

If going with Option A, skip the DetailScreen embedded player changes in this step and just ensure the play button navigates to PlayerScreen on mobile (same as TV behavior).

- [ ] **Step 4: Build 驗證**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/gimy/tv/ui/player/EmbeddedPlayerView.kt \
       app/src/main/java/com/gimy/tv/ui/player/PlayerScreen.kt \
       app/src/main/java/com/gimy/tv/ui/detail/DetailScreen.kt
git commit -m "PlayerScreen 手機適配：觸控手勢控制列與嵌入式播放器基礎"
```

---

## Task 12: 全面驗證與手動測試

**Files:** None (testing only)

- [ ] **Step 1: 完整 Build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL with no warnings related to our changes

- [ ] **Step 2: 手機模擬器測試清單**

Launch on a phone emulator (Pixel 7, API 34):

| 測試項目 | 預期結果 |
|---------|---------|
| App 出現在 launcher | ✓ |
| 首頁載入、輪播顯示 | Banner 高度 200dp，卡片 110×170 |
| 底部導航列切換 | 5 個 tab 正常切換，無閃退 |
| 搜尋功能 | 軟鍵盤彈出，搜尋結果 3 列 Grid |
| 分類瀏覽 | Grid 自適應，回退按鈕正常 |
| 影片詳情 | 直向佈局（上圖下資訊），劇集 Grid 6 列 |
| 播放器 | 全螢幕，觸控控制列，雙擊快轉 |
| 收藏/紀錄 | Grid 正常，刪除對話框正常 |
| 淺色主題 | 跟隨系統設定切換 |
| 旋轉畫面 | 不閃退，佈局正確重新排列 |

- [ ] **Step 3: 平板模擬器測試清單**

Launch on a tablet emulator (Pixel Tablet, API 34):

| 測試項目 | 預期結果 |
|---------|---------|
| NavigationRail 顯示 | 左側窄欄，5 個圖標 |
| 卡片尺寸 | 130×200dp |
| 詳情頁 | 橫向佈局（左圖右資訊） |
| 劇集 Grid | 10 列 |

- [ ] **Step 4: TV 回歸測試**

Launch on TV emulator (Android TV, API 34):

| 測試項目 | 預期結果 |
|---------|---------|
| 所有現有功能 | 與改動前完全一致 |
| 遙控器導航 | D-pad 正常，焦點動畫正常 |
| 側邊選單 | 正常顯示和切換 |

- [ ] **Step 5: 修復所有發現的問題**

Fix any issues found during testing. Build and re-test.

- [ ] **Step 6: 最終 Commit**

```bash
git add -A
git commit -m "手機平板適配完成：全面測試修復"
```
