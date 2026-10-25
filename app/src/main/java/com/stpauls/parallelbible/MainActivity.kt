package com.stpauls.parallelbible

import android.annotation.SuppressLint
import android.app.Activity
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.text.TextUtils
import android.util.LruCache
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

data class VersionDbInfo(
    val versionKey: String,
    val schemaAlias: String,
    val dbFileName: String,
    val assetZipName: String
)

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private val masterDbFileName = "eng-kjv2006_vpl.db"
    private val masterAssetZipName = "eng-kjv2006_vpl.db.zip"
    private val initLock = Any()

    @Volatile
    private var sqliteDb: SQLiteDatabase? = null
    private var cachedBooks: List<String> = emptyList()
    private var cachedBookChapters: Map<String, List<Int>> = emptyMap()

    private val versionDbsMap = mapOf(
        "kjv"  to VersionDbInfo("kjv",  "kjv_db",  "eng-kjv2006_vpl.db", "eng-kjv2006_vpl.db.zip"),
        "nas"  to VersionDbInfo("nas",  "nas_db",  "nasb_eng.db",         "nasb_eng.db.zip"),
        "orig" to VersionDbInfo("orig", "orig_db", "hbo_grctr_vpl.db",   "hbo_grctr_vpl.db.zip"),
        "abp"  to VersionDbInfo("abp",  "abp_db",  "abp_eng.db",          "abp_eng.db.zip"),
        "abgr" to VersionDbInfo("abgr", "abgr_db", "abp_grk.db",          "abp_grk.db.zip"),
        "t4t"  to VersionDbInfo("t4t",  "t4t_db",  "eng-t4t_vpl.db",      "eng-t4t_vpl.db.zip"),
        "lxx"  to VersionDbInfo("lxx",  "lxx_db",  "thomson_eng.db",      "thomson_eng.db.zip")
    )

    private val activeAttachedSchemas = mutableSetOf<String>()

    // In-memory LRU cache for visited and adjacent chapters
    private val chapterJsonCache = object : LruCache<String, String>(32) {}

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WebView.setWebContentsDebuggingEnabled(true)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return true // Intercept and block internal webview url redirects
            }
        }

        webView.addJavascriptInterface(BibleBridge(), "AndroidBridge")

        thread {
            try {
                ensureMasterDatabaseReady()
                val initialHtml = buildInitialPageHtml()
                runOnUiThread {
                    webView.loadDataWithBaseURL(
                        "file:///android_asset/",
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
                    <p>Make sure <code>eng-kjv2006_vpl.db.zip</code> is placed inside <code>app/src/main/assets/</code>.</p>
                    <pre style="white-space:pre-wrap;font-size:12px;">${esc(e.stackTraceToString())}</pre>
                    </body></html>
                """.trimIndent()
                runOnUiThread {
                    webView.loadDataWithBaseURL("file:///android_asset/", errorReport, "text/html", "UTF-8", null)
                }
            }
        }
    }

    inner class BibleBridge {
        @JavascriptInterface
        fun getChapterData(book: String, chapter: Int, leftVer: String = "kjv", rightVer: String = "abp"): String {
            ensureMasterDatabaseReady()
            return getOrBuildChapterJson(book, chapter, leftVer, rightVer, prefetchAdjacent = true)
        }

        @JavascriptInterface
        fun searchVerses(
            query: String,
            leftVer: String,
            rightVer: String,
            page: Int,
            testamentFilter: String = "all",
            verFilter: String = "both",
            bookFilter: String = "all"
        ): String {
            return try {
                ensureMasterDatabaseReady()
                executeVerseSearch(query, leftVer, rightVer, page, testamentFilter, verFilter, bookFilter).toString()
            } catch (e: Throwable) {
                android.util.Log.e("BibleBridge", "searchVerses error", e)
                JSONObject().apply {
                    put("query", query)
                    put("total", 0)
                    put("page", 1)
                    put("totalPages", 1)
                    put("pageSize", 12)
                    put("counts", JSONObject().apply {
                        put("leftOt", 0); put("leftNt", 0); put("leftAll", 0)
                        put("rightOt", 0); put("rightNt", 0); put("rightAll", 0)
                        put("totalOt", 0); put("totalNt", 0); put("totalAll", 0)
                    })
                    put("results", JSONArray())
                    put("error", e.message ?: "Unknown search error")
                }.toString()
            }
        }

        @JavascriptInterface
        fun quickSearchVerses(query: String, leftVer: String, rightVer: String, limit: Int = 5): String {
            ensureMasterDatabaseReady()
            return executeQuickVerseSearch(query, leftVer, rightVer, limit).toString()
        }

        @JavascriptInterface
        fun getBookCounts(
            query: String,
            leftVer: String,
            rightVer: String,
            testamentFilter: String = "all",
            verFilter: String = "both"
        ): String {
            ensureMasterDatabaseReady()
            return executeBookCountsQuery(query, leftVer, rightVer, testamentFilter, verFilter).toString()
        }

        @JavascriptInterface
        fun syncAttachedDatabases(enabledCodesJson: String) {
            ensureMasterDatabaseReady()
            try {
                val array = JSONArray(enabledCodesJson)
                val enabledCodes = mutableSetOf<String>()
                for (i in 0 until array.length()) {
                    enabledCodes.add(array.getString(i))
                }
                syncVersionDatabases(enabledCodes)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun syncVersionDatabases(enabledCodes: Set<String>) {
        val db = sqliteDb ?: return
        synchronized(initLock) {
            for ((code, info) in versionDbsMap) {
                if (enabledCodes.contains(code)) {
                    ensureVersionDbAttached(db, info)
                }
            }
        }
    }

    private fun ensureVersionDbAttached(db: SQLiteDatabase, info: VersionDbInfo) {
        if (activeAttachedSchemas.contains(info.schemaAlias)) return

        val targetFile = getDatabasePath(info.dbFileName)
        targetFile.parentFile?.mkdirs()

        if (!targetFile.exists() || targetFile.length() == 0L) {
            try {
                assets.open(info.assetZipName).use { rawInput ->
                    ZipInputStream(rawInput).use { zipInput ->
                        val entry = zipInput.nextEntry
                        if (entry != null) {
                            FileOutputStream(targetFile).use { output ->
                                zipInput.copyTo(output, 65536)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return
            }
        }

        if (targetFile.exists() && targetFile.length() > 0L) {
            try {
                val sql = "ATTACH DATABASE '${targetFile.absolutePath.replace("'", "''")}' AS ${info.schemaAlias}"
                db.execSQL(sql)
            } catch (_: Exception) {
            }
            activeAttachedSchemas.add(info.schemaAlias)
            chapterJsonCache.evictAll()
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

    private fun ensureMasterDatabaseReady() {
        if (sqliteDb != null) return
        synchronized(initLock) {
            if (sqliteDb != null) return

            val dbFile: File = getDatabasePath(masterDbFileName)
            dbFile.parentFile?.mkdirs()

            val prefs = getSharedPreferences("bible_db_prefs", MODE_PRIVATE)
            @Suppress("DEPRECATION")
            val pkgUpdateTime = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
            val savedUpdateTime = prefs.getLong("apk_update_time", -1L)

            if (!dbFile.exists() || dbFile.length() == 0L || pkgUpdateTime != savedUpdateTime) {
                assets.open(masterAssetZipName).use { rawInput ->
                    ZipInputStream(rawInput).use { zipInput ->
                        val entry = zipInput.nextEntry
                        if (entry != null) {
                            FileOutputStream(dbFile).use { output ->
                                zipInput.copyTo(output, 65536)
                            }
                        }
                    }
                }
                prefs.edit().putLong("apk_update_time", pkgUpdateTime).apply()
            }

            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
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

            // Attach default versions (KJV & ABe)
            versionDbsMap["kjv"]?.let { ensureVersionDbAttached(db, it) }
            versionDbsMap["abp"]?.let { ensureVersionDbAttached(db, it) }
        }
    }

    private fun executeBookCountsQuery(
        rawQuery: String,
        leftVer: String,
        rightVer: String,
        testamentFilter: String,
        verFilter: String
    ): JSONArray {
        val db = sqliteDb ?: return JSONArray()
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) return JSONArray()

        val leftInfo = versionDbsMap[leftVer] ?: versionDbsMap["kjv"]!!
        val rightInfo = versionDbsMap[rightVer] ?: versionDbsMap["abp"]!!

        ensureVersionDbAttached(db, leftInfo)
        ensureVersionDbAttached(db, rightInfo)

        val leftExpr = "${leftInfo.schemaAlias}.verses.verse_text"
        val rightExpr = "${rightInfo.schemaAlias}.verses.verse_text"

        val baseSearchExpr = when (verFilter) {
            "left" -> "$leftExpr LIKE ? ESCAPE '\\'"
            "right" -> "$rightExpr LIKE ? ESCAPE '\\'"
            else -> "($leftExpr LIKE ? ESCAPE '\\' OR $rightExpr LIKE ? ESCAPE '\\')"
        }

        val testamentCond = when (testamentFilter) {
            "ot" -> "b.book_order BETWEEN 1 AND 39"
            "nt" -> "b.book_order BETWEEN 40 AND 66"
            else -> null
        }

        val fullWhereClause = if (testamentCond != null) "$testamentCond AND ($baseSearchExpr)" else baseSearchExpr

        val escapedLike = trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val likeArg = "%$escapedLike%"
        val queryParams = if (verFilter == "both") arrayOf(likeArg, likeArg) else arrayOf(likeArg)

        val sql = "SELECT v.book, COUNT(*) FROM ${leftInfo.schemaAlias}.verses v " +
                " JOIN books b ON v.book = b.book " +
                " LEFT JOIN ${rightInfo.schemaAlias}.verses r_tbl ON v.book = r_tbl.book AND v.chapter = r_tbl.chapter AND v.verse_num = r_tbl.verse_num " +
                " WHERE " + fullWhereClause + " GROUP BY v.book ORDER BY b.book_order ASC"

        val resultArray = JSONArray()
        db.rawQuery(sql, queryParams).use { c ->
            while (c.moveToNext()) {
                val obj = JSONObject()
                obj.put("book", c.getString(0))
                obj.put("count", c.getInt(1))
                resultArray.put(obj)
            }
        }
        return resultArray
    }

    private fun executeQuickVerseSearch(
        rawQuery: String,
        leftVer: String,
        rightVer: String,
        limit: Int = 5
    ): JSONArray {
        val db = sqliteDb ?: return JSONArray()
        val trimmed = rawQuery.trim()
        if (trimmed.length < 2) return JSONArray()

        val leftInfo = versionDbsMap[leftVer] ?: versionDbsMap["kjv"]!!
        val rightInfo = versionDbsMap[rightVer] ?: versionDbsMap["abp"]!!

        ensureVersionDbAttached(db, leftInfo)
        ensureVersionDbAttached(db, rightInfo)

        val leftExpr = "${leftInfo.schemaAlias}.verses.verse_text"
        val rightExpr = "${rightInfo.schemaAlias}.verses.verse_text"

        val escapedLike = trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val likeArg = "%$escapedLike%"

        val sql = "SELECT v.book, v.chapter, v.verse_num, left_tbl.verse_text, right_tbl.verse_text " +
                " FROM books b JOIN ${leftInfo.schemaAlias}.verses v ON b.book = v.book " +
                " LEFT JOIN ${leftInfo.schemaAlias}.verses left_tbl ON v.book = left_tbl.book AND v.chapter = left_tbl.chapter AND v.verse_num = left_tbl.verse_num " +
                " LEFT JOIN ${rightInfo.schemaAlias}.verses right_tbl ON v.book = right_tbl.book AND v.chapter = right_tbl.chapter AND v.verse_num = right_tbl.verse_num " +
                " WHERE ($leftExpr LIKE ? ESCAPE '\\' OR $rightExpr LIKE ? ESCAPE '\\') " +
                " ORDER BY b.book_order ASC, v.chapter ASC, v.verse_num ASC LIMIT ?"

        val resultsArray = JSONArray()
        try {
            db.rawQuery(sql, arrayOf(likeArg, likeArg, limit.toString())).use { c ->
                while (c.moveToNext()) {
                    val obj = JSONObject()
                    obj.put("book", c.getString(0))
                    obj.put("chapter", c.getInt(1))
                    obj.put("v", c.getInt(2))
                    obj.put("leftText", if (!c.isNull(3)) c.getString(3) else "")
                    obj.put("rightText", if (!c.isNull(4)) c.getString(4) else "")
                    resultsArray.put(obj)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return resultsArray
    }

    private fun executeVerseSearch(
        rawQuery: String,
        leftVer: String,
        rightVer: String,
        requestedPage: Int,
        testamentFilter: String = "all",
        verFilter: String = "both",
        bookFilter: String = "all"
    ): JSONObject {
        val db = sqliteDb ?: throw IllegalStateException("Database not initialized")
        val trimmed = rawQuery.trim()
        val pageSize = 12

        fun emptyCounts() = JSONObject().apply {
            put("leftOt", 0); put("leftNt", 0); put("leftAll", 0)
            put("rightOt", 0); put("rightNt", 0); put("rightAll", 0)
            put("totalOt", 0); put("totalNt", 0); put("totalAll", 0)
        }

        if (trimmed.isEmpty()) {
            return JSONObject().apply {
                put("query", "")
                put("total", 0)
                put("page", 1)
                put("pageSize", pageSize)
                put("counts", emptyCounts())
                put("results", JSONArray())
            }
        }

        val leftInfo = versionDbsMap[leftVer] ?: versionDbsMap["kjv"]!!
        val rightInfo = versionDbsMap[rightVer] ?: versionDbsMap["abp"]!!

        ensureVersionDbAttached(db, leftInfo)
        ensureVersionDbAttached(db, rightInfo)

        val leftExpr = "${leftInfo.schemaAlias}.verses.verse_text"
        val rightExpr = "${rightInfo.schemaAlias}.verses.verse_text"

        val escapedLike = trimmed
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        val likeArg = "%$escapedLike%"

        val countsSql = """
            SELECT 
                COUNT(CASE WHEN b.book_order BETWEEN 1 AND 39 AND $leftExpr LIKE ? ESCAPE '\' THEN 1 END) AS left_ot,
                COUNT(CASE WHEN b.book_order BETWEEN 40 AND 66 AND $leftExpr LIKE ? ESCAPE '\' THEN 1 END) AS left_nt,
                COUNT(CASE WHEN $leftExpr LIKE ? ESCAPE '\' THEN 1 END) AS left_all,

                COUNT(CASE WHEN b.book_order BETWEEN 1 AND 39 AND $rightExpr LIKE ? ESCAPE '\' THEN 1 END) AS right_ot,
                COUNT(CASE WHEN b.book_order BETWEEN 40 AND 66 AND $rightExpr LIKE ? ESCAPE '\' THEN 1 END) AS right_nt,
                COUNT(CASE WHEN $rightExpr LIKE ? ESCAPE '\' THEN 1 END) AS right_all,

                COUNT(CASE WHEN b.book_order BETWEEN 1 AND 39 THEN 1 END) AS total_ot,
                COUNT(CASE WHEN b.book_order BETWEEN 40 AND 66 THEN 1 END) AS total_nt,
                COUNT(*) AS total_all
            FROM books b JOIN ${leftInfo.schemaAlias}.verses v ON b.book = v.book
            LEFT JOIN ${rightInfo.schemaAlias}.verses r_tbl ON v.book = r_tbl.book AND v.chapter = r_tbl.chapter AND v.verse_num = r_tbl.verse_num
            WHERE ($leftExpr LIKE ? ESCAPE '\' OR $rightExpr LIKE ? ESCAPE '\')
        """.trimIndent()

        // Exactly 8 parameters: 3 for leftExpr in CASE, 3 for rightExpr in CASE, 2 for WHERE clause
        val countsParams = arrayOf(
            likeArg, likeArg, likeArg,
            likeArg, likeArg, likeArg,
            likeArg, likeArg
        )

        val countsObj = JSONObject()
        db.rawQuery(countsSql, countsParams).use { c ->
            if (c.moveToFirst()) {
                countsObj.put("leftOt", c.getInt(0))
                countsObj.put("leftNt", c.getInt(1))
                countsObj.put("leftAll", c.getInt(2))
                countsObj.put("rightOt", c.getInt(3))
                countsObj.put("rightNt", c.getInt(4))
                countsObj.put("rightAll", c.getInt(5))
                countsObj.put("totalOt", c.getInt(6))
                countsObj.put("totalNt", c.getInt(7))
                countsObj.put("totalAll", c.getInt(8))
            } else {
                countsObj.put("leftOt", 0); countsObj.put("leftNt", 0); countsObj.put("leftAll", 0)
                countsObj.put("rightOt", 0); countsObj.put("rightNt", 0); countsObj.put("rightAll", 0)
                countsObj.put("totalOt", 0); countsObj.put("totalNt", 0); countsObj.put("totalAll", 0)
            }
        }

        val baseSearchExpr = when (verFilter) {
            "left" -> "$leftExpr LIKE ? ESCAPE '\\'"
            "right" -> "$rightExpr LIKE ? ESCAPE '\\'"
            else -> "($leftExpr LIKE ? ESCAPE '\\' OR $rightExpr LIKE ? ESCAPE '\\')"
        }

        val testamentCond = when (testamentFilter) {
            "ot" -> "b.book_order BETWEEN 1 AND 39"
            "nt" -> "b.book_order BETWEEN 40 AND 66"
            else -> null
        }

        val bookCond = if (bookFilter != "all" && bookFilter.isNotBlank()) "v.book = '${bookFilter.replace("'", "''")}'" else null

        val fullWhereClause = listOfNotNull(testamentCond, bookCond, baseSearchExpr).joinToString(" AND ") { "($it)" }
        val searchParams = if (verFilter == "both") arrayOf(likeArg, likeArg) else arrayOf(likeArg)

        var totalMatches = 0
        val totalCountSql = "SELECT COUNT(*) FROM books b JOIN ${leftInfo.schemaAlias}.verses v ON b.book = v.book " +
                " LEFT JOIN ${rightInfo.schemaAlias}.verses r_tbl ON v.book = r_tbl.book AND v.chapter = r_tbl.chapter AND v.verse_num = r_tbl.verse_num " +
                " WHERE " + fullWhereClause
        db.rawQuery(totalCountSql, searchParams).use { c ->
            if (c.moveToFirst()) {
                totalMatches = c.getInt(0)
            }
        }

        val totalPages = if (totalMatches == 0) 1 else ((totalMatches + pageSize - 1) / pageSize)
        val safePage = requestedPage.coerceIn(1, totalPages)
        val offset = (safePage - 1) * pageSize

        val selectSql = "SELECT v.book, v.chapter, v.verse_num, " +
                " left_tbl.verse_text, right_tbl.verse_text " +
                " FROM books b JOIN ${leftInfo.schemaAlias}.verses v ON b.book = v.book " +
                " LEFT JOIN ${leftInfo.schemaAlias}.verses left_tbl ON v.book = left_tbl.book AND v.chapter = left_tbl.chapter AND v.verse_num = left_tbl.verse_num " +
                " LEFT JOIN ${rightInfo.schemaAlias}.verses right_tbl ON v.book = right_tbl.book AND v.chapter = right_tbl.chapter AND v.verse_num = right_tbl.verse_num " +
                " WHERE " + fullWhereClause +
                " ORDER BY b.book_order ASC, v.chapter ASC, v.verse_num ASC LIMIT ? OFFSET ?"

        val queryParams = searchParams + arrayOf(pageSize.toString(), offset.toString())

        val resultsArray = JSONArray()
        if (totalMatches > 0) {
            db.rawQuery(selectSql, queryParams).use { c ->
                while (c.moveToNext()) {
                    val obj = JSONObject()
                    obj.put("book", c.getString(0))
                    obj.put("chapter", c.getInt(1))
                    obj.put("v", c.getInt(2))
                    obj.put(leftVer, if (!c.isNull(3)) c.getString(3) else "")
                    obj.put(rightVer, if (!c.isNull(4)) c.getString(4) else "")
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
            put("counts", countsObj)
            put("results", resultsArray)
        }
    }

    private fun getOrBuildChapterJson(
        requestedBook: String?,
        requestedChapter: Int?,
        leftVer: String,
        rightVer: String,
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

        val cacheKey = "$selectedBook:$selectedChapter:$leftVer:$rightVer"
        chapterJsonCache.get(cacheKey)?.let { cached ->
            if (prefetchAdjacent) triggerAdjacentPrefetch(selectedBook, selectedChapter, leftVer, rightVer)
            return cached
        }

        val built = queryChapterJsonObject(selectedBook, selectedChapter, leftVer, rightVer).toString()
        chapterJsonCache.put(cacheKey, built)

        if (prefetchAdjacent) {
            triggerAdjacentPrefetch(selectedBook, selectedChapter, leftVer, rightVer)
        }
        return built
    }

    private fun triggerAdjacentPrefetch(book: String, chapter: Int, leftVer: String, rightVer: String) {
        val (prevPair, nextPair) = getAdjacentPassages(book, chapter)
        thread {
            if (prevPair != null) {
                val k = "${prevPair.first}:${prevPair.second}:$leftVer:$rightVer"
                if (chapterJsonCache.get(k) == null) {
                    chapterJsonCache.put(k, queryChapterJsonObject(prevPair.first, prevPair.second, leftVer, rightVer).toString())
                }
            }
            if (nextPair != null) {
                val k = "${nextPair.first}:${nextPair.second}:$leftVer:$rightVer"
                if (chapterJsonCache.get(k) == null) {
                    chapterJsonCache.put(k, queryChapterJsonObject(nextPair.first, nextPair.second, leftVer, rightVer).toString())
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

    private fun queryChapterJsonObject(
        selectedBook: String,
        selectedChapter: Int,
        leftVer: String,
        rightVer: String
    ): JSONObject {
        val db = sqliteDb ?: throw IllegalStateException("Database not initialized")
        val chapters = cachedBookChapters[selectedBook] ?: listOf(1)
        val (prev, next) = getAdjacentPassages(selectedBook, selectedChapter)

        val leftInfo = versionDbsMap[leftVer] ?: versionDbsMap["kjv"]!!
        val rightInfo = versionDbsMap[rightVer] ?: versionDbsMap["abp"]!!

        ensureVersionDbAttached(db, leftInfo)
        ensureVersionDbAttached(db, rightInfo)

        val sql = "SELECT v.verse_num, left_tbl.verse_text, right_tbl.verse_text " +
                " FROM ${leftInfo.schemaAlias}.verses v " +
                " LEFT JOIN ${leftInfo.schemaAlias}.verses left_tbl ON v.book = left_tbl.book AND v.chapter = left_tbl.chapter AND v.verse_num = left_tbl.verse_num " +
                " LEFT JOIN ${rightInfo.schemaAlias}.verses right_tbl ON v.book = right_tbl.book AND v.chapter = right_tbl.chapter AND v.verse_num = right_tbl.verse_num " +
                " WHERE v.book = ? AND v.chapter = ? ORDER BY v.verse_num ASC"

        val versesArray = JSONArray()
        db.rawQuery(sql, arrayOf(selectedBook, selectedChapter.toString())).use { c ->
            while (c.moveToNext()) {
                val obj = JSONObject()
                obj.put("v", c.getInt(0))
                obj.put(leftVer, if (!c.isNull(1)) c.getString(1) else "")
                obj.put(rightVer, if (!c.isNull(2)) c.getString(2) else "")
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
        val initialDataJson = getOrBuildChapterJson(firstBook, firstChapter, "kjv", "abp", prefetchAdjacent = true)

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