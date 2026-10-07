# My Parallel Bible - Apostolic for Android

An offline, dual-pane parallel Bible study application built for Android. **My Parallel Bible - Apostolic** enables users to read and compare multiple Bible translations side-by-side with fast full-text search, smart passage navigation, customizable themes, and responsive typography.

---

## 🌟 Comprehensive Feature List

### 📖 Dual-Pane Parallel Verse Comparison
- **Synchronized Row Layout**: Verses are aligned side-by-side in synchronized grid rows for direct, verse-by-verse comparison.
- **Left Column Translations**:
  - **KJV**: King James Version
  - **MT**: Masoretic / Hebrew & Greek Original Text
  - **TR**: Textus Receptus
- **Right Column Translations**:
  - **ABEn**: Apostolic Bible Polyglot (English)
  - **ABGr**: Apostolic Bible Polyglot (Greek)
  - **T4T**: Translation for Translators
  - **KAN**: Kannada Bible
- **Independent Version Switchers**: Quick-toggle buttons located in the top header for each column.
- **Persistent Preferences**: Selected versions are saved automatically to `localStorage`.

---

### ✍️ Native Right-to-Left (RTL) Hebrew Support
- **RTL Text Rendering**: Hebrew text in the Original/Masoretic column is automatically formatted right-to-left (`direction: rtl`).
- **Tailored Typography**: Integrated Hebrew font family cascade (`SBL Hebrew`, `Ezra SIL`, `David`, `Noto Serif Hebrew`) with +15% font scaling and adjusted line height for high legibility.

---

### 🧭 Navigation & Passage Selection
- **Canonical Book Dropdown**: Select any book from the Old or New Testament ordered by canonical book sequence.
- **Dynamic Chapter Selector**: Automatically populates chapter dropdowns based on the chosen book.
- **Chapter Quick-Nav**: Instant Previous (`‹`) and Next (`›`) chapter navigation buttons.
- **Verse-Level Deep Linking**:
  - Direct navigation via query parameters (e.g., `?book=GEN&chapter=1&verse=10`) or hash fragments (`#v10`).
  - Smooth scrolling animation directly to the requested verse with visual target highlight.

---

### 🔍 Smart Passage Search & Full-Text Engine
- **Passage Search**: Type passage references directly into the search bar (e.g., `GEN 23:10` or `Jn 3:16`) for instant jump-to-verse navigation.
- **Auto-Complete Results**: Displays matching book/chapter suggestions in real time as you type.
- **Full-Text SQLite Search**:
  - Perform SQL `LIKE` queries across translations.
  - Paginated search results (24 results per page) displaying book, chapter, verse number, and matching text snippets.
  - Safe character escaping for special characters (`\`, `%`, `_`).

---

### 🎨 Distraction-Free Reading Mode & Themes
- **Auto-Hiding Interface**: Top headers and bottom navigation dock automatically slide out of view when scrolling down for an immersive reading experience, and reappear when scrolling up or returning to the top.
- **3 Built-in Color Themes**:
  1. **Warm Parchment & Mahogany** (`warm`) — Classic paper feel with dark brown accents (Default).
  2. **Teal & Crisp Light** (`teal`) — High-contrast daylight reading mode.
  3. **Midnight Dark** (`dark`) — Deep dark theme optimized for night reading and OLED displays.
- **Dynamic Font Size Control**: `A-` and `A+` buttons allow users to adjust verse text size incrementally from 12px to 28px with automatic device-width responsive defaults.

---

### ⚡ Offline Performance & Architecture

- **Compressed Database Packaging (`bible.db.zip`)**:
  - The full ~110 MB SQLite database is compressed into a ~15 MB asset (`bible.db.zip`).
  - On app launch or update, `MainActivity` transparently decompresses `bible.db` directly into the app's internal database folder via `ZipInputStream`.
- **Multi-Level In-Memory Caching**:
  - **`LruCache` (32 Chapters)**: Retains generated JSON representations of visited chapters for instantaneous chapter switches.
  - **Background Prefetching**: Spawns background worker threads to automatically prefetch and cache adjacent chapters (previous and next chapters).
- **Responsive WebView Bridge**:
  - Single Activity (`MainActivity`) hosting a WebView runtime paired with Kotlin `@JavascriptInterface` (`AndroidBridge`).
  - Custom back-button handling (`window.handleAndroidBack`) for web navigation history.

---

## 🛠️ Tech Stack

| Layer | Technology / Library |
| :--- | :--- |
| **Language** | Kotlin 1.9+ & Modern ES6 JavaScript / HTML5 / CSS3 |
| **UI Container** | Custom Android `WebView` with `Material3` DayNight theme |
| **Database** | SQLite (`android.database.sqlite.SQLiteDatabase`) |
| **Decompression** | `java.util.zip.ZipInputStream` |
| **Caching** | `android.util.LruCache` & Kotlin Coroutines / Background Threads |
| **Min SDK / Target SDK** | Android 7.0 (API 24) / Android 14+ (API 37) |
| **Build Tool** | Gradle with Kotlin DSL (`build.gradle.kts`) |

---

## 📁 Project Structure

```text
ParallelBible/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── assets/
│           │   ├── bible.db.zip       # Compressed SQLite Bible database (~15 MB)
│           │   └── template.html      # Frontend HTML, CSS themes, and JS application logic
│           └── java/com/stpauls/parallelbible/
│               └── MainActivity.kt    # SQLite engine, Zip unpacker, LRU cache & JS Bridge
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

## 🚀 Getting Started

### Prerequisites

- **Android Studio** (Ladybug / 2024.1 or newer recommended)
- **JDK 11** or higher
- **Android SDK**: Minimum API 24 (Android 7.0)

### Building & Running

1. **Clone the Repository**:
   ```bash
   git clone https://github.com/stpaulssmg-git/parallel-bible-android.git
   cd parallel-bible-android
   ```

2. **Open in Android Studio**:
   - Open Android Studio -> **Open** -> Select `ParallelBible`.
   - Wait for the Gradle project sync to finish.

3. **Build APK / Run**:
   ```bash
   ./gradlew assembleDebug
   ```

---

## 📄 License

Private repository maintained for Parallel Bible development.
