# Apostolic Parallel Bible - Android

An offline, dual-pane parallel Bible study application built for Android. **Apostolic Parallel Bible** enables users to read and compare multiple Bible translations side-by-side with fast full-text search, book-level drill-down filtering, modular regional language support, smart passage navigation, customizable themes, and responsive gesture typography.

---

## 🌟 Comprehensive Feature List

### 📖 Dual-Pane Parallel Verse Comparison
- **Synchronized Grid Layout**: Verses are aligned side-by-side in synchronized grid rows for direct, verse-by-verse comparison.
- **Left Column Translations**:
  - **KJV**: King James Version
  - **NAS**: New American Standard Bible (1995/2020)
  - **H/G**: Original Hebrew Masoretic & Greek Textus Receptus
- **Right Column Translations**:
  - **ABe**: Apostolic Bible Polyglot (English)
  - **ABg**: Apostolic Bible Polyglot (Greek)
  - **T4T**: Translation for Translators
  - **OTH**: Regional & Other Languages (Configurable 4th Button)
- **Persistent Preferences**: Selected versions, themes, font sizes, and regional language configurations are saved automatically.

---

### 🌐 Modular Regional Databases (`ATTACH DATABASE`)
- **Dynamic SQLite Attachment**: Regional translations are stored in separate compressed `.db.zip` asset packages and dynamically attached on demand using SQLite `ATTACH DATABASE`:
  - **KAN**: Kannada IRV (`kannada.db.zip`)
  - **TLG**: Telugu 2017 (`telugu.db.zip`)
  - **MAL**: Malayalam VPL (`malayalam.db.zip`)
  - **TAM**: Tamil 2017 (`tamil.db.zip`)
  - **HIN**: Hindi 2017 (`hindi.db.zip`)
  - **SPA**: Spanish Reina Valera 1909 (`spanish.db.zip`)
  - **LXX**: Thomson Septuagint English (`thomson.db.zip`)
- **Active Memory Retention**: The 4th top bar button retains the last chosen regional language (e.g. `KAN ▾`) even when switching back to `ABe` or `T4T`.

---

### ✍️ Native Right-to-Left (RTL) Hebrew Support
- **RTL Text Rendering**: Hebrew text in the Original/Masoretic column is automatically formatted right-to-left (`direction: rtl`).
- **Tailored Typography**: Integrated Hebrew font family cascade (`SBL Hebrew`, `Ezra SIL`, `David`) with +15% font scaling and adjusted line height for high legibility.

---

### 🔍 Smart Full-Text Search & Drill-Down Engine
- **Per-Version & Testament Breakdown Links**:
  - Displays match counts per translation: `KJV: [O-250] [ + ]  [N-30] [ + ]  [T-280]`.
  - Clickable count badges filter results instantly by **Old Testament (`O`)**, **New Testament (`N`)**, or **Total (`T`)**.
  - Grayed-out non-clickable links for zero matches (`[O-0]`).
- **Book-Level Drill-Down (`[ + ]`)**:
  - Tapping **`[ + ]`** opens a popup modal listing only the specific books containing search matches along with their verse counts.
  - Tapping a book filters search results directly to that book.
- **Search Retention & Return Bar**:
  - Tapping a verse reference opens the full chapter context without losing search results.
  - A floating return bar (`🔍 Return to "query" (Pg 1)`) + Android Back button integration allows 1-tap return to active search results.
- **12 Verses Per Page & 60fps Swipe Navigation**:
  - Background search page prefetching (`searchPageCache`) for zero-latency, 60fps hardware-accelerated horizontal swipe page transitions.

---

### 🧭 Custom Touch Pickers & Navigation
- **Custom Book Picker Modal**:
  - Displays dual-translation book names matching active left and right translations (e.g. `GEN Genesis / ಆದಿಕಾಂಡ` or `GEN Genesis`).
- **Custom Chapter Grid Modal**:
  - 5-column touch grid for instant chapter selection.
- **Uniform Bottom Dock**:
  - 32px height and 6px corner radius synchronized across Book Picker, Chapter Picker, Search Input, Search Submit, Theme Selector (`C`), and About Modal (`i`) buttons.

---

### 🎨 4 Reading Themes & Pinch-to-Zoom Gesture
- **4 Built-in Color Themes**:
  1. 🍷 **Alpha/Omega Crimson** (`crimson`) — Signature default theme.
  2. 📜 **Warm Sepia** (`warm`) — Paper-like comfort reading.
  3. 🌊 **Modern Teal** (`teal`) — High-contrast daylight mode.
  4. 🌙 **Night Dark** (`dark`) — Deep dark theme optimized for OLED screens.
- **Silky-Smooth 60fps Pinch-to-Zoom**: Sub-pixel floating-point font scaling driven by `requestAnimationFrame`.

---

### ⚡ Build Optimization & Performance

- **34.7 MB Optimized Release Bundle**:
  - Raw `.txt` source files are excluded from APK/AAB builds (`ignoreAssetsPattern = "*.txt"`), saving 20.5 MB of build size.
- **Multi-Level In-Memory Caching**:
  - `LruCache` (32 Chapters) for instant chapter switching.
  - Background worker threads to prefetch adjacent chapters.
- **Native Android WebView Guard**:
  - `WebViewClient` URL override guard and `event.preventDefault()` prevent accidental form submissions or `ERR_FILE_NOT_FOUND` errors.

---

## 🛠️ Tech Stack

| Layer | Technology / Library |
| :--- | :--- |
| **Language** | Kotlin 1.9+ & Modern ES6 JavaScript / HTML5 / CSS3 |
| **UI Container** | Custom Android `WebView` with `Material3` DayNight theme |
| **Database Engine** | SQLite (`android.database.sqlite.SQLiteDatabase`) with `ATTACH DATABASE` |
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
│   ├── release.jks            # RSA 2048-bit Release Keystore
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── assets/
│           │   ├── bible.db.zip       # Core 6-version SQLite database (~11 MB)
│           │   ├── kannada.db.zip     # Regional Kannada database
│           │   ├── telugu.db.zip      # Regional Telugu database
│           │   ├── malayalam.db.zip   # Regional Malayalam database
│           │   ├── tamil.db.zip       # Regional Tamil database
│           │   ├── hindi.db.zip       # Regional Hindi database
│           │   ├── spanish.db.zip     # Regional Spanish database
│           │   ├── thomson.db.zip     # Regional Thomson LXX database
│           │   ├── template.html      # Main HTML layout, CSS themes, and JS logic
│           │   ├── bible_config.js    # App version labels and fallback configurations
│           │   └── bible_data.js      # Multilingual book dictionaries and aliases
│           └── java/com/stpauls/parallelbible/
│               └── MainActivity.kt    # SQLite engine, ATTACH DATABASE joins, LRU cache & JS Bridge
├── PRIVACY_POLICY.md
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

## 🚀 Building & Releasing

### Prerequisites

- **Android Studio** (2024.1+ / Ladybug or newer)
- **JDK 17** or higher
- **Android SDK**: Minimum API 24 (Android 7.0)

### Building Release Artifacts

1. **Build Signed Release APK**:
   ```bash
   ./gradlew assembleRelease
   ```
   *Output*: `app/build/outputs/apk/release/app-release.apk`

2. **Build Signed Release Bundle (`.aab`) for Google Play**:
   ```bash
   ./gradlew :app:bundleRelease
   ```
   *Output*: `app/build/outputs/bundle/release/app-release.aab`

---

## 📄 License

Private repository maintained for **Apostolic Parallel Bible** development.
