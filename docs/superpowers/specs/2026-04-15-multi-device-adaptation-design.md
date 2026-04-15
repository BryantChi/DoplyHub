# GimyTV Multi-Device Adaptation Design

> Date: 2026-04-15
> Status: Approved
> Scope: Adapt GimyTV from TV-only to support phones, tablets, and TV in a single APK

## Decisions

| Decision | Choice |
|----------|--------|
| Distribution | Single APK, auto-detect device type |
| Feature scope | Identical to TV version |
| Phone navigation | Bottom Navigation Bar (5 tabs) |
| Tablet navigation | Navigation Rail |
| Player (phone) | Embedded in detail page + fullscreen toggle |
| Visual theme | Dark (default) + Light theme option |
| minSdk | 28 (Android 9.0) |
| Strategy | WindowSizeClass inline branching (no module split) |

## 1. Device Classification

Using `WindowSizeClass` + Leanback detection:

| Class | Width | Device | Layout |
|-------|-------|--------|--------|
| Compact | < 600dp | Phone | Portrait, BottomNavigation, single column |
| Medium | 600-839dp | Small tablet | Portrait/Landscape, NavigationRail, optional two-column |
| Expanded | ≥ 840dp + Leanback | TV / Large tablet | Landscape, existing TV layout |

Detection in `MainActivity.onCreate()`:
- `calculateWindowSizeClass()` — Compose Material3 API
- `packageManager.hasSystemFeature(FEATURE_LEANBACK)` — distinguish TV from large tablet
- Both values provided via `CompositionLocal` to all Composables

### CompositionLocals

```kotlin
val LocalWindowSizeClass   // Compact / Medium / Expanded
val LocalIsTelevision      // Boolean
val LocalDimensions        // Device-appropriate padding, card sizes, etc.
```

## 2. Navigation Structure

| Device | Navigation |
|--------|-----------|
| TV (existing) | Side menu → pages |
| Phone (Compact) | BottomNavigation → Home / Search / Browse / Favorites / History |
| Tablet (Medium) | NavigationRail → same 5 destinations |

- DetailScreen and PlayerScreen are full-screen navigation targets (not inside tabs)
- One `AdaptiveNavigation` Composable wraps the device-specific navigation component
- This is the ONLY "Adaptive" wrapper — all other screens use inline `when(widthSizeClass)` branching

## 3. Screen Adaptations

### HomeScreen

| | TV (existing) | Phone (Compact) | Tablet (Medium) |
|---|---|---|---|
| Hero Banner | Large | Reduced, ~200dp height | Medium |
| Category rows | LazyRow | LazyRow, smaller cards | LazyRow, medium cards |
| VodCard size | 154×248dp | ~110×170dp | ~130×200dp |
| Horizontal padding | 48dp | 16dp | 24dp |

### SearchScreen

| | TV | Phone | Tablet |
|---|---|---|---|
| Input | Top, remote input | Top, soft keyboard | Same as phone |
| Results | LazyVerticalGrid fixed cols | 3-column Grid | 4-5 column Grid |

### BrowseScreen

| | TV | Phone | Tablet |
|---|---|---|---|
| Category filter | Top horizontal tabs | ScrollableTabRow | Same as phone |
| Results Grid | Fixed columns | 3 columns | 4-5 columns |

### DetailScreen

| | TV | Phone | Tablet |
|---|---|---|---|
| Layout | Horizontal: poster left, info right | Vertical: poster top, info below (single column) | Horizontal: poster left, info right (like TV) |
| Episode list | Grid below | Scrollable Grid below | Grid right side or below |

### FavoritesScreen / HistoryScreen

Simple structure — only Grid column count and card sizes change per `WindowSizeClass`.

## 4. Player Adaptation

### Two Playback Modes (Phone/Tablet only)

**Embedded mode (in DetailScreen):**
```
┌─────────────────────┐
│  ┌─────────────────┐ │
│  │   PlayerView    │ │  ← 16:9 aspect ratio, embedded at top
│  │   (embedded)    │ │
│  └─────────────────┘ │
│  Title                │
│  Synopsis...          │
│  ┌──┬──┬──┬──┬──┐   │
│  │01│02│03│04│05│   │  ← Episode selection
│  └──┴──┴──┴──┴──┘   │
└─────────────────────┘
```

**Fullscreen mode:**
- Tap fullscreen button → force landscape, hide system UI
- Auto-rotate to landscape → auto enter fullscreen
- Rotate back to portrait or press back → return to embedded mode

### Touch Gestures (Phone/Tablet only)

| Gesture | Action |
|---------|--------|
| Single tap | Show/hide controls overlay |
| Horizontal swipe | Seek forward/backward (10s) |
| Vertical swipe (left side) | Adjust brightness |
| Vertical swipe (right side) | Adjust volume |
| Double tap left/right | Seek backward/forward 10s |

### Control Bar

- TV: Remote-triggered, large buttons for distance viewing (unchanged)
- Phone/Tablet: Semi-transparent bottom overlay, draggable progress bar
  - Buttons: `[Prev] [Rewind] [Play/Pause] [Forward] [Next] [Fullscreen]`

### Implementation

- Same ExoPlayer + PlayerView instance shared between embedded and fullscreen
- No player rebuild on mode switch — only container changes
- Gestures via `Modifier.pointerInput`, disabled entirely on TV
- Existing remote control logic unchanged

## 5. Component Adaptation

### VodCard

- Size: determined by `LocalDimensions` per device class
- TV: retain `focusedScale` + `focusedBorder` animation
- Phone/Tablet: remove focus animation, use `clickable` + ripple effect
- Content (image, title, label) unchanged, only layout spacing adjusted

### TV vs Standard Material3 Components

```kotlin
if (isTelevision) {
    // Use androidx.tv.material3.Card, Surface, etc.
} else {
    // Use androidx.compose.material3.Card, Surface, etc.
}
```

TV and standard Material3 components have similar but incompatible APIs — must select correct variant per device.

## 6. Theme System

```
GimyTheme(darkTheme, isTelevision)
├── Colors:
│   ├── Dark: existing OLED black + #E11D48 (shared across all devices)
│   └── Light: new light scheme (white background + #E11D48 accent)
├── Typography:
│   ├── TV: existing larger type scale (distance reading)
│   └── Phone/Tablet: standard Material3 Typography
├── Dimensions:
│   └── LocalDimensions CompositionLocal with device-specific spacing values
└── Dark theme default:
    ├── TV → always dark
    └── Phone/Tablet → follow system setting (isSystemInDarkTheme)
```

### Theme Preference

```kotlin
// DataStore new preference
val THEME_MODE = stringPreferencesKey("theme_mode")
// Values: "system" | "dark" | "light"
// Default: TV → "dark", Phone/Tablet → "system"
```

## 7. Manifest & Build Changes

### AndroidManifest.xml

```xml
<!-- Leanback no longer required -->
<uses-feature android:name="android.software.leanback" android:required="false" />

<!-- Touchscreen remains not required (for TV) -->
<uses-feature android:name="android.hardware.touchscreen" android:required="false" />

<!-- Add LAUNCHER category for phone/tablet -->
<activity android:name=".ui.MainActivity">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

### build.gradle.kts

```kotlin
minSdk = 28  // raised from 23
```

### New Dependencies (libs.versions.toml)

```toml
material3-window-size = "androidx.compose.material3:material3-window-size-class:x.x.x"
material3 = "androidx.compose.material3:material3:x.x.x"  # standard (non-TV) Material3
```

Existing `tv-foundation` and `tv-material` remain for TV mode.

## 8. Data Layer Impact

### No Changes Required

- Repository: scraping logic is device-independent
- Room Database: favorites, history schema unchanged
- DataStore: no schema change (one new preference key only)
- ViewModels: HomeViewModel, DetailViewModel, SearchViewModel, BrowseViewModel unchanged

### PlayerViewModel Minor Extension

```kotlin
// New additions
val isFullscreen: StateFlow<Boolean>
fun toggleFullscreen()

// All existing state (playerState, currentEpisode, etc.) unchanged
```

## 9. Change Summary

| Layer | Impact |
|-------|--------|
| Data / Domain | None |
| ViewModels | PlayerViewModel +2 members, others unchanged |
| Theme | New light color scheme, typography variants, LocalDimensions |
| Navigation | New AdaptiveNavigation composable |
| Screens (7) | Each gets WindowSizeClass branching for layout |
| Components | VodCard responsive sizing, TV/mobile interaction swap |
| Manifest | leanback required=false, add LAUNCHER category |
| Build config | minSdk 28, add material3 + window-size-class deps |
