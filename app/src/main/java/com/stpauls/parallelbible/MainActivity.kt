package com.stpauls.parallelbible

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.text.TextUtils
import android.util.LruCache
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private val dbFileName = "bible.db"
    private val initLock = Any()

    @Volatile
    private var sqliteDb: SQLiteDatabase? = null
    private var cachedBooks: List<String> = emptyList()
    private var cachedBookChapters: Map<String, List<Int>> = emptyMap()

    // In-memory LRU cache for visited and adjacent chapters
    private val chapterJsonCache = object : LruCache<String, String>(32) {}

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
        }

        webView.addJavascriptInterface(BibleBridge(), "AndroidBridge")

        thread {
            try {
                ensureDatabaseReady()
                val initialHtml = buildInitialPageHtml()
                runOnUiThread {
                    webView.loadDataWithBaseURL(
                        "https://bible.local/",
                        initialHtml,
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            } catch (e: Throwable) {
                val errorReport = """
                    <html><body style="font-family:sans-serif;padding:20px;">
                    <h2>Database Error</h2>
                    <p>Make sure <code>bible.db.zip</code> or <code>bible.db</code> is placed inside <code>app/src/main/assets/</code>.</p>
                    <pre style="white-space:pre-wrap;font-size:12px;">${esc(e.stackTraceToString())}</pre>
                    </body></html>
                """.trimIndent()
                runOnUiThread {
                    webView.loadDataWithBaseURL("https://bible.local/", errorReport, "text/html", "UTF-8", null)
                }
            }
        }
    }

    inner class BibleBridge {
        @JavascriptInterface
        fun getChapterData(book: String, chapter: Int): String {
            ensureDatabaseReady()
            return getOrBuildChapterJson(book, chapter, prefetchAdjacent = true)
        }

        @JavascriptInterface
        fun searchVerses(query: String, leftVer: String, rightVer: String, page: Int): String {
            ensureDatabaseReady()
            return executeVerseSearch(query, leftVer, rightVer, page).toString()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (this::webView.isInitialized) {
            webView.evaluateJavascript("window.handleAndroidBack ? window.handleAndroidBack() : false") { result ->
                if (result != "true") {
                    super.onBackPressed()
                }
            }
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sqliteDb?.close()
        sqliteDb = null
    }

    private fun resolveAssetDbPath(): String {
        for (candidate in listOf("bible.db.zip", "assets/bible.db.zip", dbFileName, "assets/$dbFileName")) {
            try {
                assets.open(candidate).close()
                return candidate
            } catch (_: Exception) {
            }
        }
        throw IllegalStateException("Neither bible.db.zip nor bible.db found in app/src/main/assets/")
    }

    private fun hasUpdatedSchema(dbFile: File): Boolean {
        if (!dbFile.exists() || dbFile.length() == 0L) return false
        return try {
            var hasNasb = false
            var hasOrig = false
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("PRAGMA table_info(verses)", null).use { c ->
                    while (c.moveToNext()) {
                        when (c.getString(1)) {
                            "nasb_text" -> hasNasb = true
                            "orig_text" -> hasOrig = true
                        }
                    }
                }
            }
            hasNasb && hasOrig
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureDatabaseReady() {
        if (sqliteDb != null) return
        synchronized(initLock) {
            if (sqliteDb != null) return

            val dbFile: File = getDatabasePath(dbFileName)
            dbFile.parentFile?.mkdirs()

            val prefs = getSharedPreferences("bible_db_prefs", Context.MODE_PRIVATE)
            @Suppress("DEPRECATION")
            val pkgUpdateTime = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
            val savedUpdateTime = prefs.getLong("apk_update_time", -1L)

            if (!dbFile.exists() || dbFile.length() == 0L || pkgUpdateTime != savedUpdateTime || !hasUpdatedSchema(dbFile)) {
                val assetPath = resolveAssetDbPath()
                if (assetPath.endsWith(".zip")) {
                    assets.open(assetPath).use { rawInput ->
                        ZipInputStream(rawInput).use { zipInput ->
                            val entry = zipInput.nextEntry
                            if (entry != null) {
                                FileOutputStream(dbFile).use { output ->
                                    zipInput.copyTo(output, 65536)
                                }
                            } else {
                                throw IllegalStateException("Empty zip file in assets: $assetPath")
                            }
                        }
                    }
                } else {
                    assets.open(assetPath).use { input ->
                        FileOutputStream(dbFile).use { output ->
                            input.copyTo(output, 65536)
                        }
                    }
                }
                prefs.edit().putLong("apk_update_time", pkgUpdateTime).apply()
            }

            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )

            val books = mutableListOf<String>()
            val bookChapters = mutableMapOf<String, List<Int>>()

            db.rawQuery("SELECT book, chapters_json FROM books ORDER BY book_order ASC", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val book = cursor.getString(0)
                    val chapsJson = JSONArray(cursor.getString(1))
                    val chapsList = ArrayList<Int>(chapsJson.length())
                    for (i in 0 until chapsJson.length()) {
                        chapsList.add(chapsJson.getInt(i))
                    }
                    books.add(book)
                    bookChapters[book] = chapsList
                }
            }

            cachedBooks = books
            cachedBookChapters = bookChapters
            sqliteDb = db
        }
    }

    private fun versionKeyToColumn(ver: String): String = when (ver) {
        "kjv"  -> "kjv_text"
        "nas"  -> "nasb_text"
        "orig" -> "orig_text"
        "abp"  -> "aben_text"
        "abgr" -> "abgr_text"
        "t4t"  -> "t4t_text"
        "kan"  -> "kan_text"
        else   -> "kjv_text"
    }

    private fun executeVerseSearch(
        rawQuery: String,
        leftVer: String,
        rightVer: String,
        requestedPage: Int
    ): JSONObject {
        val db = sqliteDb ?: throw IllegalStateException("Database not initialized")
        val trimmed = rawQuery.trim()
        val pageSize = 24

        if (trimmed.isEmpty()) {
            return JSONObject().apply {
                put("query", "")
                put("total", 0)
                put("page", 1)
                put("pageSize", pageSize)
                put("results", JSONArray())
            }
        }

        val leftCol = versionKeyToColumn(leftVer)
        val rightCol = versionKeyToColumn(rightVer)
        val escapedLike = trimmed
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        val likeArg = "%$escapedLike%"

        var totalMatches = 0
        db.rawQuery(
            """
            SELECT COUNT(*)
            FROM verses v
            WHERE v.$leftCol LIKE ? ESCAPE '\' OR v.$rightCol LIKE ? ESCAPE '\'
            """.trimIndent(),
            arrayOf(likeArg, likeArg)
        ).use { c ->
            if (c.moveToFirst()) {
                totalMatches = c.getInt(0)
            }
        }

        val totalPages = if (totalMatches == 0) 1 else ((totalMatches + pageSize - 1) / pageSize)
        val safePage = requestedPage.coerceIn(1, totalPages)
        val offset = (safePage - 1) * pageSize

        val resultsArray = JSONArray()
        if (totalMatches > 0) {
            db.rawQuery(
                """
                SELECT v.book, v.chapter, v.verse_num, v.t4t_label,
                       v.kjv_text, v.nasb_text, v.orig_text,
                       v.aben_text, v.abgr_text, v.t4t_text, v.kan_text
                FROM verses v
                JOIN books b ON v.book = b.book
                WHERE v.$leftCol LIKE ? ESCAPE '\' OR v.$rightCol LIKE ? ESCAPE '\'
                ORDER BY b.book_order ASC, v.chapter ASC, v.verse_num ASC
                LIMIT ? OFFSET ?
                """.trimIndent(),
                arrayOf(likeArg, likeArg, pageSize.toString(), offset.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val obj = JSONObject()
                    obj.put("book", c.getString(0))
                    obj.put("chapter", c.getInt(1))
                    obj.put("v", c.getInt(2))
                    obj.put("t4tL", c.getString(3))
                    if (!c.isNull(4)) obj.put("kjv", c.getString(4))
                    if (!c.isNull(5)) obj.put("nas", c.getString(5))
                    if (!c.isNull(6)) obj.put("orig", c.getString(6))
                    if (!c.isNull(7)) obj.put("abp", c.getString(7))
                    if (!c.isNull(8)) obj.put("abgr", c.getString(8))
                    if (!c.isNull(9)) obj.put("t4t", c.getString(9))
                    if (!c.isNull(10)) obj.put("kan", c.getString(10))
                    resultsArray.put(obj)
                }
            }
        }

        return JSONObject().apply {
            put("query", trimmed)
            put("total", totalMatches)
            put("page", safePage)
            put("totalPages", totalPages)
            put("pageSize", pageSize)
            put("results", resultsArray)
        }
    }

    private fun getOrBuildChapterJson(
        requestedBook: String?,
        requestedChapter: Int?,
        prefetchAdjacent: Boolean
    ): String {
        val selectedBook = if (requestedBook != null && cachedBooks.contains(requestedBook)) {
            requestedBook
        } else {
            cachedBooks.firstOrNull() ?: "GEN"
        }

        val chapters = cachedBookChapters[selectedBook] ?: listOf(1)
        val selectedChapter = if (requestedChapter != null && chapters.contains(requestedChapter)) {
            requestedChapter
        } else {
            chapters.firstOrNull() ?: 1
        }

        val cacheKey = "$selectedBook:$selectedChapter"
        chapterJsonCache.get(cacheKey)?.let { cached ->
            if (prefetchAdjacent) triggerAdjacentPrefetch(selectedBook, selectedChapter)
            return cached
        }

        val built = queryChapterJsonObject(selectedBook, selectedChapter).toString()
        chapterJsonCache.put(cacheKey, built)

        if (prefetchAdjacent) {
            triggerAdjacentPrefetch(selectedBook, selectedChapter)
        }
        return built
    }

    private fun triggerAdjacentPrefetch(book: String, chapter: Int) {
        val (prevPair, nextPair) = getAdjacentPassages(book, chapter)
        thread {
            if (prevPair != null) {
                val k = "${prevPair.first}:${prevPair.second}"
                if (chapterJsonCache.get(k) == null) {
                    chapterJsonCache.put(k, queryChapterJsonObject(prevPair.first, prevPair.second).toString())
                }
            }
            if (nextPair != null) {
                val k = "${nextPair.first}:${nextPair.second}"
                if (chapterJsonCache.get(k) == null) {
                    chapterJsonCache.put(k, queryChapterJsonObject(nextPair.first, nextPair.second).toString())
                }
            }
        }
    }

    private fun getAdjacentPassages(
        selectedBook: String,
        selectedChapter: Int
    ): Pair<Pair<String, Int>?, Pair<String, Int>?> {
        val chapters = cachedBookChapters[selectedBook] ?: listOf(1)
        val bookIndex = cachedBooks.indexOf(selectedBook)
        val chapIndex = chapters.indexOf(selectedChapter)

        var prev: Pair<String, Int>? = null
        if (chapIndex > 0) {
            prev = Pair(selectedBook, chapters[chapIndex - 1])
        } else if (bookIndex > 0) {
            val prevBook = cachedBooks[bookIndex - 1]
            val prevChaps = cachedBookChapters[prevBook] ?: listOf(1)
            prev = Pair(prevBook, prevChaps.last())
        }

        var next: Pair<String, Int>? = null
        if (chapIndex != -1 && chapIndex < chapters.size - 1) {
            next = Pair(selectedBook, chapters[chapIndex + 1])
        } else if (bookIndex != -1 && bookIndex < cachedBooks.size - 1) {
            val nextBook = cachedBooks[bookIndex + 1]
            val nextChaps = cachedBookChapters[nextBook] ?: listOf(1)
            next = Pair(nextBook, nextChaps.first())
        }

        return Pair(prev, next)
    }

    private fun queryChapterJsonObject(selectedBook: String, selectedChapter: Int): JSONObject {
        val db = sqliteDb ?: throw IllegalStateException("Database not initialized")
        val chapters = cachedBookChapters[selectedBook] ?: listOf(1)
        val (prev, next) = getAdjacentPassages(selectedBook, selectedChapter)

        val versesArray = JSONArray()
        db.rawQuery(
            """
            SELECT verse_num, t4t_label, kjv_text, nasb_text, orig_text, aben_text, abgr_text, t4t_text, kan_text
            FROM verses
            WHERE book = ? AND chapter = ?
            ORDER BY verse_num ASC
            """.trimIndent(),
            arrayOf(selectedBook, selectedChapter.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val obj = JSONObject()
                obj.put("v", c.getInt(0))
                obj.put("t4tL", c.getString(1))
                if (!c.isNull(2)) obj.put("kjv", c.getString(2))
                if (!c.isNull(3)) obj.put("nas", c.getString(3))
                if (!c.isNull(4)) obj.put("orig", c.getString(4))
                if (!c.isNull(5)) obj.put("abp", c.getString(5))
                if (!c.isNull(6)) obj.put("abgr", c.getString(6))
                if (!c.isNull(7)) obj.put("t4t", c.getString(7))
                if (!c.isNull(8)) obj.put("kan", c.getString(8))
                versesArray.put(obj)
            }
        }

        return JSONObject().apply {
            put("book", selectedBook)
            put("chapter", selectedChapter)
            put("chapters", JSONArray(chapters))
            put("prevBook", prev?.first ?: JSONObject.NULL)
            put("prevChapter", prev?.second ?: JSONObject.NULL)
            put("nextBook", next?.first ?: JSONObject.NULL)
            put("nextChapter", next?.second ?: JSONObject.NULL)
            put("verses", versesArray)
        }
    }

    private fun buildInitialPageHtml(): String {
        val firstBook = cachedBooks.firstOrNull() ?: "GEN"
        val firstChapter = cachedBookChapters[firstBook]?.firstOrNull() ?: 1
        val initialDataJson = getOrBuildChapterJson(firstBook, firstChapter, prefetchAdjacent = true)

        val availableChaptersJson = JSONObject()
        for ((b, chaps) in cachedBookChapters) {
            availableChaptersJson.put(b, JSONArray(chaps))
        }

        val bookOptions = StringBuilder()
        for (b in cachedBooks) {
            val sel = if (b == firstBook) " selected" else ""
            bookOptions.append("""<option value="${esc(b)}"$sel>${esc(b)}</option>""")
        }

        val templateHtml = assets.open("template.html").bufferedReader().use { it.readText() }

        return templateHtml
            .replace("{{BOOK_OPTIONS}}", bookOptions.toString())
            .replace("{{AVAILABLE_BOOKS_JSON}}", JSONArray(cachedBooks).toString())
            .replace("{{AVAILABLE_CHAPTERS_JSON}}", availableChaptersJson.toString())
            .replace("{{INITIAL_CHAPTER_DATA_JSON}}", initialDataJson)
    }

    private fun esc(text: String): String = TextUtils.htmlEncode(text)
}