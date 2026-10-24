# Apostolic Parallel Bible - Android

An offline, dual-pane parallel Bible study application built for Android. **Apostolic Parallel Bible** enables users to read and compare multiple Bible translations side-by-side with fast full-text search, book-level drill-down filtering, smart passage navigation, customizable themes, and responsive gesture typography.

---

## 🌟 Comprehensive Feature List

### 📖 Dual-Pane Parallel Verse Comparison
- **Synchronized Grid Layout**: Verses are aligned side-by-side in synchronized grid rows for direct, verse-by-verse comparison.
- **Left Column Translations**:
  - **KJV**: King James Version
  - **NAS**: New American Standard Bible (1995/2020)
  - **H/G**: Original Hebrew Masoretic & Greek Textus Receptus
  - **LXX**: Charles Thomson Septuagint English
- **Right Column Translations**:
  - **ABe**: Apostolic Bible Polyglot (English)
  - **ABg**: Apostolic Bible Polyglot (Greek)
  - **T4T**: Translation for Translators
- **Persistent Preferences**: Selected versions, themes, font sizes, and last reading position are saved automatically.

---

### 📚 Accordion Book & Chapter Selector
- **Old Testament (39) & New Testament (27) Tabs**: Books are neatly organized into OT and NT tabs for quick navigation.
- **Clean English Book Names**: Displays 100% pure, full English book names (`Genesis`, `Exodus`, `Matthew`, `Revelation`).
- **Inline Chapter Expansion**: Tapping any book name expands its chapter grid directly inline on the same page.
- **Instant Chapter Jump**: Tapping any chapter pill closes the modal and navigates directly to that chapter in the main reader.

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

### 🧭 Ergonomic Floating Bottom Dock Bar
- **Permanent Floating Bar**:
  - Sits fixed at `bottom: 36px` above the system navigation bar for comfortable 1-thumb access.
  - Houses the Passage Selector (`GEN 1 ▾`), Search Bar (`Ref or word...`), Theme Selector (`T`), and About Page (`i`).

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

- **22.6 MB Optimized Release Build**:
  - Raw `.txt` source files are excluded from APK/AAB builds, ensuring a compact 22.6 MB Release APK.
- **Individual Database Architecture**:
  - Each translation is stored in its own individual `.db.zip` package (`eng-kjv2006_vpl.db.zip`, `nasb_eng.db.zip`, `hbo_grctr_vpl.db.zip`, `abp_eng.db.zip`, `abp_grk.db.zip`, `eng-t4t_vpl.db.zip`, `thomson_eng.db.zip`).
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
│           │   ├── abp_eng.db.zip          # Apostolic Bible Polyglot English
│           │   ├── abp_grk.db.zip          # Apostolic Bible Polyglot Greek
│           │   ├── eng-kjv2006_vpl.db.zip  # King James Version 2006
│           │   ├── eng-t4t_vpl.db.zip      # Translation for Translators
│           │   ├── hbo_grctr_vpl.db.zip    # Original Hebrew & Greek
│           │   ├── nasb_eng.db.zip         # New American Standard
│           │   ├── thomson_eng.db.zip      # Thomson Septuagint English
│           │   └── template.html           # Main HTML layout, CSS themes, and JS logic
│           └── java/com/stpauls/parallelbible/
│               └── MainActivity.kt         # SQLite engine, ATTACH DATABASE joins, LRU cache & JS Bridge
├── LICENSE                    # CC BY-NC-SA 4.0 International License
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
   ./gradlew :app:assembleRelease
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

Official License Details: https://creativecommons.org/licenses/by-nc-sa/4.0/

### Summary of License Terms:
- **Share**: Copy and redistribute the material in any medium or format.
- **Adapt**: Remix, transform, and build upon the material.
- **Attribution**: You must give appropriate credit, provide a link to the license, and indicate if changes were made.
- **NonCommercial**: You may **NOT** use the material for commercial purposes or monetization.
- **ShareAlike**: If you remix, transform, or build upon the material, you must distribute your contributions under the exact same CC BY-NC-SA 4.0 license.

### Content & Copyright Notice:
- **Software Source Code & Engine**: Licensed under CC BY-NC-SA 4.0 (Non-Commercial).
- **Bible Text Databases & Resources**:
  - **NASB (New American Standard Bible)**: Copyright © The Lockman Foundation. All rights reserved. Used strictly for non-commercial study purposes.
  - **ABP (Apostolic Bible Polyglot)**: Copyright © Charles Van der Pool. All rights reserved. Restricted to non-commercial personal study.
  - **KJV & Public Domain Translations**: Public Domain.
