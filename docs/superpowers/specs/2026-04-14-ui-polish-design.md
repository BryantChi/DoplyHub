# UI Polish: Loading Animation, Splash, Hero Banner

## Overview

Three visual improvements to enhance brand consistency and user experience on Android TV.

## 1. GimyLoadingIndicator — Branded Loading Animation

### Goal
Replace all 7 `CircularProgressIndicator` instances with a branded pulsing play-triangle icon.

### Design
- New shared Composable: `GimyLoadingIndicator(size: Dp)`
- Icon source: `R.drawable.ic_launcher_foreground` (red play triangle)
- Animation: `infiniteRepeatable` with `scale 0.8↔1.2` + `alpha 0.4↔1.0`, 1s period, `EaseInOut`
- Accepts `size` parameter for different contexts

### Replacement Targets

| File | Line | Current Size | New Size | Context |
|------|------|-------------|----------|---------|
| HomeScreen.kt | 282 | 36dp | 48dp | Full-screen home load |
| DetailScreen.kt | 41 | 36dp | 48dp | Full-screen detail load |
| PlayerScreen.kt | 185 | 44dp | 48dp | Player buffering overlay |
| BrowseScreen.kt | 74 | 32dp | 48dp | Full-screen browse load |
| BrowseScreen.kt | 111 | 28dp | 28dp | Inline load-more |
| SearchScreen.kt | 123 | 32dp | 48dp | Full-screen search load |
| SearchScreen.kt | 167 | 28dp | 28dp | Inline load-more |

### Constraints
- No behavioral changes — only visual replacement
- Existing layout structure (Box, alignment, spacing) preserved
- PlayerScreen's `AnimatedVisibility` wrapper unchanged

## 2. Splash Brand Animation

### Goal
Show a branded animation on app launch that doubles as a data-loading screen.

### Design
- Implemented in Compose within `MainActivity`, no new Activity
- Controlled by a `showSplash` state in `GimyApp` (or top-level composable wrapping NavHost)
- Splash dismisses when: HomeViewModel data loaded AND minimum 1.5s elapsed

### Animation Sequence
1. Black screen (`CinemaBlack`)
2. Red play triangle scales in from 0→1 + fades in (0→1), duration ~600ms, `EaseOut`
3. "GIMY TV" text fades in below, duration ~400ms, delayed 400ms
4. Hold for remaining time until dismiss condition met
5. Entire splash fades out (300ms), revealing home screen beneath

### Structure
```
GimyApp {
    // NavHost always composed (HomeViewModel starts loading immediately)
    NavHost(...) { ... }

    // Splash overlay on top
    AnimatedVisibility(visible = showSplash, exit = fadeOut(300ms)) {
        SplashOverlay()
    }
}
```

### Constraints
- NavHost composes underneath splash so data loading begins immediately
- No new dependencies required
- Minimum display time prevents flash on fast loads
- No impact on navigation or back-stack behavior

## 3. Hero Banner — Netflix Style

### Goal
Fix blurry, cropped hero banner by using a blur-background + clear-foreground layout.

### Current Problem
- `coverUrl` is a small portrait poster (~200x280px)
- Stretched to `fillMaxWidth() x 340dp` horizontal area
- `ContentScale.Crop` causes severe cropping and blur

### New Layout
```
┌─────────────────────────────────────────────────┐
│  [blurred coverUrl background, fillMaxSize]     │
│  [gradient overlay: transparent → black bottom] │
│                                                 │
│  ┌──────────┐                                   │
│  │          │  Title (20sp, Bold)               │
│  │  Cover   │  Year · Genre                     │
│  │  Image   │                                   │
│  │ 140x200  │  [觀看詳情] button                │
│  │          │                                   │
│  └──────────┘                                   │
│         ● ○ ○ ○ ○ ○  (page dots)               │
└─────────────────────────────────────────────────┘
```

### Implementation Details
- **Background**: `AsyncImage` with `ContentScale.Crop` + `Modifier.blur(25.dp)` + reduced alpha (0.6)
- **Gradient overlay**: `Brush.verticalGradient(transparent → CinemaBlack)` over bottom 60%
- **Foreground cover**: `AsyncImage` with `ContentScale.Fit`, fixed `140.dp x 200.dp`, `RoundedCornerShape(8.dp)`
- **Text info**: Title, year/genre tags from `vod` data, aligned to the right of the cover
- **Button**: Existing "觀看詳情" `FocusableChip`, same click behavior

### Constraints
- Auto-rotation (7s) logic preserved
- D-pad left/right navigation preserved
- Page indicator dots preserved
- `onVodClick` callback unchanged
- Same data source (`heroItems` from first row, take 6)

## Non-Goals
- No new network requests or API changes
- No changes to data layer, navigation, or ViewModel logic (except splash coordination)
- No new library dependencies
