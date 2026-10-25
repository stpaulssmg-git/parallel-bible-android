# Apostolic Parallel Bible - Android

An offline, dual-pane parallel Bible study application built for Android. **Apostolic Parallel Bible** enables users to read and compare multiple Bible translations side-by-side with fast full-text search, book-level drill-down filtering, modular regional language support, smart passage navigation, customizable themes, customizable verse fonts, and responsive gesture typography.

---

## 🌟 Comprehensive Feature List

### 📖 Dual-Pane Parallel Verse Comparison
- **Synchronized Grid Layout**: Verses are aligned side-by-side in synchronized grid rows for direct, verse-by-verse comparison.
- **Left Column Translations**:
  - **KJV**: King James Version
  - **NAS**: New American Standard Bible (1995/2020)
  - **H/G**: Original Hebrew Masoretic & Greek Textus Receptus
  - **LXX**: Thomson Septuagint English (1808) — Permanently built into Left Pane
- **Right Column Translations**:
  - **ABe**: Apostolic Bible Polyglot (English)
  - **ABg**: Apostolic Bible Polyglot (Greek)
  - **T4T**: Translation for Translators
  - **OTH ▾**: Regional & Other Languages (Configurable 4th Button)
- **Persistent Preferences**: Selected versions, themes, verse font family, font size, and regional language selections are automatically saved.

---

### 🏛️ Native 7-Translation Core Database (`bible.db.zip`)
- **Built-in Core Database**: Contains 7 core translations pre-compiled into a single SQLite database (`bible.db.zip`):
  1. `KJV` (King James Version)
  2. `NAS` (New American Standard)
  3. `H/G` (Hebrew Masoretic / Greek TR)
  4. `LXX` (Thomson Septuagint English — 30,974 verses)
  5. `ABe` (Apostolic Bible English)
  6. `ABg` (Apostolic Bible Greek)
  7. `T4T` (Translation for Translators)

---

### 🌐 Modular Regional Databases (`ATTACH DATABASE`)
- **Dynamic SQLite Attachment**: Additional regional translations are stored in separate compressed `.db.zip` packages and attached dynamically on demand using SQLite `ATTACH DATABASE`:
  - **KAN**: Kannada IRV (`kannada.db.zip`)
  - **TLG**: Telugu 2017 (`telugu.db.zip`)
  - **MAL**: Malayalam VPL (`malayalam.db.zip`)
  - **TAM**: Tamil 2017 (`tamil.db.zip`)
  - **HIN**: Hindi 2017 (`hindi.db.zip`)
  - **SPA**: Spanish Reina Valera 1909 (`spanish.db.zip`)
- **Configuration Modal**: Regional languages are enabled/disabled via the **OTH** section inside the About Modal (`i`).

---

### 📚 Redesigned Book Picker & Inline Chapter Selector
- **Testament Filter Tabs**:
  - **OT (39 Books)**: Filters the book list to Old Testament books (Genesis–Malachi).
  - **NT (27 Books)**: Filters the book list to New Testament books (Matthew–Revelation).
- **Inline Expandable Chapter Grid**:
  - Tapping a book name expands a spacious 5-column grid of chapter buttons inline directly under that book for 1-tap passage navigation.
- **Spacious & Comfortable Touch Targets**:
  - 44px minimum height buttons with explicit spacing for easy, non-congested tapping on all device sizes.

---

### 🔤 Verse Font Family Selection
- **4 Selectable Typefaces** (configured in About Modal):
  1. **Sans-Serif**: Modern / Clean (`Segoe UI`, system-ui, Arial) — Default
  2. **Serif**: Georgia / Literary Book Style (`Georgia`, `Times New Roman`)
  3. **Monospace**: Fixed Pitch (`Consolas`, `Courier New`)
  4. **Rounded**: Casual / Trebuchet (`Trebuchet MS`, `Ubuntu`)
- **Focused Typography Application**: Font changes apply **strictly to verse text** in the reader and search results, preserving clean system typography for all UI buttons, headers, modals, and dock controls.

---

### 🎨 4 Color Themes & Pinch-to-Zoom Gesture
- **4 Color Themes** (Cycled via `C` button):
  1. 🍷 **Crimson** (`crimson`) — Signature default crimson theme (**C**).
  2. 📜 **Warm** (`warm`) — Paper-like sepia comfort reading (**W**).
  3. 🌊 **Teal** (`teal`) — High-contrast daylight teal (**T**).
  4. 🌙 **Dark** (`dark`) — Deep dark theme with enhanced dock/bar borders and contrast for OLED screens (**D**).
- **Smooth Pinch-to-Zoom**: Sub-pixel font scaling (12px to 28px) with `requestAnimationFrame`.

---

### ✍️ Native Right-to-Left (RTL) Hebrew Support
- **RTL Text Rendering**: Hebrew text in the Original (`H/G`) column is formatted right-to-left (`direction: rtl`).
- **Hebrew Typography**: Integrated font stack (`SBL Hebrew`, `Ezra SIL`, `David`) with adjusted line height for high legibility.

---

### 🔍 Smart Full-Text Search & Drill-Down Engine
- **Per-Version & Testament Breakdown Links**:
  - Displays match counts per active translation: `KJV: [O-250] [ + ]  [N-30] [ + ]  [T-280]`.
  - Clickable count badges filter results instantly by **Old Testament (`O`)**, **New Testament (`N`)**, or **Total (`T`)**.
- **Book-Level Drill-Down (`[ + ]`)**:
  - Tapping **`[ + ]`** opens a spacious popup modal listing matching books and match counts.
- **Search Retention & Return Bar**:
  - Floating return bar (`🔍 Return to "query" (Pg 1)`) + Android Back button integration allows 1-tap return to active search results.
- **12 Verses Per Page & Swipe Page Transitions**:
  - Background search page prefetching for smooth horizontal swipe page transitions.

---

### ⚡ Compatibility & Performance

- **Android API 24 (Android 7.0) Compatibility**:
  - Tailored CSS overlays and hit-testing fallback handling for stock System WebViews (Chrome 51).
- **Multi-Level Caching**:
  - In-memory `LruCache` (32 Chapters) and background worker threads for adjacent chapter prefetching.

---

## 🛠️ Tech Stack

| Layer | Technology / Library |
| :--- | :--- |
| **Language** | Kotlin & ES6 JavaScript / HTML5 / CSS3 |
| **UI Container** | Android `WebView` with Material DayNight theme |
| **Database Engine** | SQLite (`android.database.sqlite.SQLiteDatabase`) + `ATTACH DATABASE` |
| **Caching** | `android.util.LruCache` & Background Threads |
| **Min SDK / Target SDK** | Android 7.0 (API 24) / Android 14+ (API 34/35) |
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
│           │   ├── bible.db.zip       # Core 7-version SQLite database (~13 MB)
│           │   ├── kannada.db.zip     # Regional Kannada database
│           │   ├── telugu.db.zip      # Regional Telugu database
│           │   ├── malayalam.db.zip   # Regional Malayalam database
│           │   ├── tamil.db.zip       # Regional Tamil database
│           │   ├── hindi.db.zip       # Regional Hindi database
│           │   ├── spanish.db.zip     # Regional Spanish database
│           │   └── template.html      # Main HTML layout, CSS themes, and JS logic
│           └── java/com/stpauls/parallelbible/
│               └── MainActivity.kt    # SQLite engine, JS Bridge & LRU cache
├── LICENSE                    # CC BY-NC-SA 4.0 International License
├── PRIVACY_POLICY.md
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

## 📄 License & Terms

Licensed under the **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License (CC BY-NC-SA 4.0)**.

### Copyrights & Permissions:
- **NASB (New American Standard Bible)**: Copyright © The Lockman Foundation.
- **ABP (Apostolic Bible Polyglot - ABe / ABg)**: Copyright © Charles Van der Pool.
- **T4T (Translation for Translators)**: Copyright © Ellis W. Deibler, Jr. / unfoldingWord (CC BY-SA 4.0).
- **IRV Bibles (KAN / TLG / TAM / HIN)**: Copyright © Bridge Connectivity Solutions / unfoldingWord (CC BY-SA 4.0).
- **Public Domain**: King James Version (KJV), Hebrew Masoretic & Greek TR (H/G), Thomson Septuagint (LXX), Malayalam VPL (MAL), Spanish Reina Valera 1909 (SPA).
