# MangaWorld App

A modern Arabic manga reader app built with Kotlin and Jetpack Compose.

## Features

### Reading
- Multiple reading modes (vertical scroll, horizontal RTL/LTR, webtoon)
- Image filters (grayscale, sepia, high contrast, warm/cool tint, OLED black)
- Customizable tap zones and gesture controls
- Reading timer with Pomodoro mode
- Incognito mode
- Smart prefetching

### Content
- 5 manga sources (Olympus, Azora, Starz, MangaSid, Meshmanga)
- Smart content recommendations
- Advanced search with filters and history
- Manga collections and custom lists
- Source comparison

### Community
- Comments and discussions
- Reading reactions
- User profiles and lists
- Moderation dashboard

### Offline
- Download chapters for offline reading
- Auto-download next chapters on WiFi
- Reading position sync across devices

### Widgets
- Library widget
- Latest updates widget
- Continue reading widget
- Daily recommendations widget
- Reading stats widget

### Settings
- Reading goals and achievements
- Parental controls
- Notification preferences
- Theme customization

## Architecture

- **UI**: Jetpack Compose with Material Design 3
- **Architecture**: MVVM with Clean Architecture
- **DI**: Hilt
- **Database**: Room
- **Networking**: OkHttp + Jsoup
- **Image Loading**: Coil
- **Background**: WorkManager

## Building

```bash
./gradlew assembleDebug
```

## Testing

```bash
./gradlew testDebugUnitTest
```

## License

Private - All rights reserved.
