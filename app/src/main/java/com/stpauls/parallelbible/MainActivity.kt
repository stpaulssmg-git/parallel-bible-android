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

data class RegionalDbInfo(
    val versionKey: String,
    val schemaAlias: String,
    val dbFileName: String,
    val assetZipName: String,
    val columnName: String
)

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private val dbFileName = "bible.db"
    private val initLock = Any()

    @Volatile
    private var sqliteDb: SQLiteDatabase? = null
    private var cachedBooks: List<String> = emptyList()
    private var cachedBookChapters: Map<String, List<Int>> = emptyMap()

    private val regionalDbsMap = mapOf(
        "kan" to RegionalDbInfo("kan", "kan_db", "kannada.db", "kannada.db.zip", "kan_text"),
        "tlg" to RegionalDbInfo("tlg", "tlg_db", "telugu.db", "telugu.db.zip", "tlg_text"),
        "mal" to RegionalDbInfo("mal", "mal_db", "malayalam.db", "malayalam.db.zip", "mal_text"),
        "tam" to RegionalDbInfo("tam", "tam_db", "tamil.db", "tamil.db.zip", "tam_text"),
        "hin" to RegionalDbInfo("hin", "hin_db", "hindi.db", "hindi.db.zip", "hin_text"),
        "spa" to RegionalDbInfo("spa", "spa_db", "spanish.db", "spanish.db.zip", "spa_text"),
        "lxx" to RegionalDbInfo("lxx", "lxx_db", "thomson.db", "thomson.db.zip", "lxx_text")
    )

    private val activeAttachedSchemas = mutableSetOf<String>()

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
                ensureDatabaseReady()
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
                    <p>Make sure <code>bible.db.zip</code> or <code>bible.db</code> is placed inside <code>app/src/main/assets/</code>.</p>
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
        fun getChapterData(book: String, chapter: Int): String {
            ensureDatabaseReady()
            return getOrBuildChapterJson(book, chapter, prefetchAdjacent = true)
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
            ensureDatabaseReady()
            return executeVerseSearch(query, leftVer, rightVer, page, testamentFilter, verFilter, bookFilter).toString()
        }

        @JavascriptInterface
        fun getBookCounts(
            query: String,
            leftVer: String,
            rightVer: String,
            testamentFilter: String = "all",
            verFilter: String = "both"
        ): String {
            ensureDatabaseReady()
            return executeBookCountsQuery(query, leftVer, rightVer, testamentFilter, verFilter).toString()
        }

        @JavascriptInterface
        fun syncAttachedDatabases(enabledCodesJson: String) {
            ensureDatabaseReady()
            try {
                val array = JSONArray(enabledCodesJson)
                val enabledCodes = mutableSetOf<String>()
                for (i in 0 until array.length()) {
                    enabledCodes.add(array.getString(i))
                }
                updateAttachedDatabases(enabledCodes)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updateAttachedDatabases(enabledCodes: Set<String>) {
        val db = sqliteDb ?: return
        synchronized(initLock) {
            for ((code, info) in regionalDbsMap) {
                if (enabledCodes.contains(code)) {
                    if (!activeAttachedSchemas.contains(info.schemaAlias)) {
                        attachRegionalDb(db, info)
                    }
                } else {
                    if (activeAttachedSchemas.contains(info.schemaAlias)) {
                        detachRegionalDb(db, info)
                    }
                }
            }
        }
    }

    private fun attachRegionalDb(db: SQLiteDatabase, info: RegionalDbInfo) {
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
            } catch (_: Exception) {
                return
            }
        }

        if (targetFile.exists() && targetFile.length() > 0L) {
            try {
                val sql = "ATTACH DATABASE '${targetFile.absolutePath.replace("'", "''")}' AS ${info.schemaAlias}"
                db.execSQL(sql)
                activeAttachedSchemas.add(info.schemaAlias)
                chapterJsonCache.evictAll()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun detachRegionalDb(db: SQLiteDatabase, info: RegionalDbInfo) {
        try {
            val sql = "DETACH DATABASE ${info.schemaAlias}"
            db.execSQL(sql)
            activeAttachedSchemas.remove(info.schemaAlias)
            chapterJsonCache.evictAll()
        } catch (e: Exception) {
            e.printStackTrace()
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

    private fun ensureDatabaseReady() {
        if (sqliteDb != null) return
        synchronized(initLock) {
            if (sqliteDb != null) return

            val dbFile: File = getDatabasePath(dbFileName)
            dbFile.parentFile?.mkdirs()

            val prefs = getSharedPreferences("bible_db_prefs", MODE_PRIVATE)
            @Suppress("DEPRECATION")
            val pkgUpdateTime = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
            val savedUpdateTime = prefs.getLong("apk_update_time", -1L)

            if (!dbFile.exists() || dbFile.length() == 0L || pkgUpdateTime != savedUpdateTime) {
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

        fun getColExpression(verKey: String): String? {
            return when (verKey) {
                "kjv"  -> "v.kjv_text"
                "nas"  -> "v.nasb_text"
                "orig" -> "v.orig_text"
                "abp"  -> "v.aben_text"
                "abgr" -> "v.abgr_text"
                "t4t"  -> "v.t4t_text"
                else -> {
                    val info = regionalDbsMap[verKey]
                    if (info != null && activeAttachedSchemas.contains(info.schemaAlias)) {
                        "${info.schemaAlias}_tbl.${info.columnName}"
                    } else null
                }
            }
        }

        val leftExpr = getColExpression(leftVer) ?: "'__NONE__'"
        val rightExpr = getColExpression(rightVer) ?: "'__NONE__'"

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

        val joinClauses = StringBuilder()
        for ((_, info) in regionalDbsMap) {
            if (activeAttachedSchemas.contains(info.schemaAlias)) {
                joinClauses.append(" LEFT JOIN ${info.schemaAlias}.verses ${info.schemaAlias}_tbl ON v.book = ${info.schemaAlias}_tbl.book AND v.chapter = ${info.schemaAlias}_tbl.chapter AND v.verse_num = ${info.schemaAlias}_tbl.verse_num")
            }
        }

        val escapedLike = trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val likeArg = "%$escapedLike%"
        val queryParams = if (verFilter == "both" && leftExpr != "'__NONE__'" && rightExpr != "'__NONE__'") arrayOf(likeArg, likeArg) else arrayOf(likeArg)

        val sql = "SELECT v.book, COUNT(*) FROM verses v JOIN books b ON v.book = b.book" + joinClauses.toString() + " WHERE " + fullWhereClause + " GROUP BY v.book ORDER BY b.book_order ASC"

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

        fun getColExpression(verKey: String): String? {
            return when (verKey) {
                "kjv"  -> "v.kjv_text"
                "nas"  -> "v.nasb_text"
                "orig" -> "v.orig_text"
                "abp"  -> "v.aben_text"
                "abgr" -> "v.abgr_text"
                "t4t"  -> "v.t4t_text"
                else -> {
                    val info = regionalDbsMap[verKey]
                    if (info != null && activeAttachedSchemas.contains(info.schemaAlias)) {
                        "${info.schemaAlias}_tbl.${info.columnName}"
                    } else null
                }
            }
        }

        val leftExpr = getColExpression(leftVer)
        val rightExpr = getColExpression(rightVer)

        if (leftExpr == null && rightExpr == null) {
            return JSONObject().apply {
                put("query", trimmed)
                put("total", 0)
                put("page", 1)
                put("totalPages", 1)
                put("pageSize", pageSize)
                put("counts", emptyCounts())
                put("results", JSONArray())
            }
        }

        val escapedLike = trimmed
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        val likeArg = "%$escapedLike%"

        val joinClauses = StringBuilder()
        for ((_, info) in regionalDbsMap) {
            if (activeAttachedSchemas.contains(info.schemaAlias)) {
                joinClauses.append(" LEFT JOIN ${info.schemaAlias}.verses ${info.schemaAlias}_tbl ON v.book = ${info.schemaAlias}_tbl.book AND v.chapter = ${info.schemaAlias}_tbl.chapter AND v.verse_num = ${info.schemaAlias}_tbl.verse_num")
            }
        }

        val safeLeftExpr = leftExpr ?: "'__NONE__'"
        val safeRightExpr = rightExpr ?: "'__NONE__'"

        val countsSql = """
            SELECT 
                COUNT(CASE WHEN b.book_order BETWEEN 1 AND 39 AND $safeLeftExpr LIKE ? ESCAPE '\' THEN 1 END) AS left_ot,
                COUNT(CASE WHEN b.book_order BETWEEN 40 AND 66 AND $safeLeftExpr LIKE ? ESCAPE '\' THEN 1 END) AS left_nt,
                COUNT(CASE WHEN $safeLeftExpr LIKE ? ESCAPE '\' THEN 1 END) AS left_all,

                COUNT(CASE WHEN b.book_order BETWEEN 1 AND 39 AND $safeRightExpr LIKE ? ESCAPE '\' THEN 1 END) AS right_ot,
                COUNT(CASE WHEN b.book_order BETWEEN 40 AND 66 AND $safeRightExpr LIKE ? ESCAPE '\' THEN 1 END) AS right_nt,
                COUNT(CASE WHEN $safeRightExpr LIKE ? ESCAPE '\' THEN 1 END) AS right_all,

                COUNT(CASE WHEN b.book_order BETWEEN 1 AND 39 THEN 1 END) AS total_ot,
                COUNT(CASE WHEN b.book_order BETWEEN 40 AND 66 THEN 1 END) AS total_nt,
                COUNT(*) AS total_all
            FROM verses v JOIN books b ON v.book = b.book
            $joinClauses
            WHERE ($safeLeftExpr LIKE ? ESCAPE '\' OR $safeRightExpr LIKE ? ESCAPE '\')
        """.trimIndent()

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
            "left" -> "$safeLeftExpr LIKE ? ESCAPE '\\'"
            "right" -> "$safeRightExpr LIKE ? ESCAPE '\\'"
            else -> "($safeLeftExpr LIKE ? ESCAPE '\\' OR $safeRightExpr LIKE ? ESCAPE '\\')"
        }

        val testamentCond = when (testamentFilter) {
            "ot" -> "b.book_order BETWEEN 1 AND 39"
            "nt" -> "b.book_order BETWEEN 40 AND 66"
            else -> null
        }

        val bookCond = if (bookFilter != "all" && bookFilter.isNotBlank()) "v.book = '${bookFilter.replace("'", "''")}'" else null

        val fullWhereClause = listOfNotNull(testamentCond, bookCond, baseSearchExpr).joinToString(" AND ") { "($it)" }
        val searchParams = if (verFilter == "both" && safeLeftExpr != "'__NONE__'" && safeRightExpr != "'__NONE__'") arrayOf(likeArg, likeArg) else arrayOf(likeArg)

        var totalMatches = 0
        val totalCountSql = "SELECT COUNT(*) FROM verses v JOIN books b ON v.book = b.book" + joinClauses.toString() + " WHERE " + fullWhereClause
        db.rawQuery(totalCountSql, searchParams).use { c ->
            if (c.moveToFirst()) {
                totalMatches = c.getInt(0)
            }
        }

        val totalPages = if (totalMatches == 0) 1 else ((totalMatches + pageSize - 1) / pageSize)
        val safePage = requestedPage.coerceIn(1, totalPages)
        val offset = (safePage - 1) * pageSize

        val selectCols = mutableListOf(
            "v.book", "v.chapter", "v.verse_num", "v.t4t_label",
            "v.kjv_text", "v.nasb_text", "v.orig_text", "v.aben_text", "v.abgr_text", "v.t4t_text"
        )
        val regKeys = mutableListOf<Pair<String, String>>()
        for ((_, info) in regionalDbsMap) {
            if (activeAttachedSchemas.contains(info.schemaAlias)) {
                selectCols.add("${info.schemaAlias}_tbl.${info.columnName}")
                regKeys.add(Pair(info.columnName, info.versionKey))
            }
        }

        val selectSql = "SELECT " + selectCols.joinToString(", ") +
                " FROM verses v JOIN books b ON v.book = b.book" + joinClauses.toString() +
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
                    obj.put("t4tL", c.getString(3))
                    if (!c.isNull(4)) obj.put("kjv", c.getString(4))
                    if (!c.isNull(5)) obj.put("nas", c.getString(5))
                    if (!c.isNull(6)) obj.put("orig", c.getString(6))
                    if (!c.isNull(7)) obj.put("abp", c.getString(7))
                    if (!c.isNull(8)) obj.put("abgr", c.getString(8))
                    if (!c.isNull(9)) obj.put("t4t", c.getString(9))

                    var idx = 10
                    for (pair in regKeys) {
                        if (!c.isNull(idx)) {
                            obj.put(pair.second, c.getString(idx))
                        }
                        idx++
                    }
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

        val selectCols = mutableListOf(
            "v.verse_num", "v.t4t_label", "v.kjv_text", "v.nasb_text",
            "v.orig_text", "v.aben_text", "v.abgr_text", "v.t4t_text"
        )
        val joinClauses = StringBuilder()
        val regKeys = mutableListOf<Pair<String, String>>()

        for ((_, info) in regionalDbsMap) {
            if (activeAttachedSchemas.contains(info.schemaAlias)) {
                selectCols.add("${info.schemaAlias}_tbl.${info.columnName}")
                joinClauses.append(" LEFT JOIN ${info.schemaAlias}.verses ${info.schemaAlias}_tbl ON v.book = ${info.schemaAlias}_tbl.book AND v.chapter = ${info.schemaAlias}_tbl.chapter AND v.verse_num = ${info.schemaAlias}_tbl.verse_num")
                regKeys.add(Pair(info.columnName, info.versionKey))
            }
        }

        val sql = "SELECT " + selectCols.joinToString(", ") +
                " FROM verses v" + joinClauses.toString() +
                " WHERE v.book = ? AND v.chapter = ? ORDER BY v.verse_num ASC"

        val versesArray = JSONArray()
        db.rawQuery(sql, arrayOf(selectedBook, selectedChapter.toString())).use { c ->
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

                var idx = 8
                for (pair in regKeys) {
                    if (!c.isNull(idx)) {
                        obj.put(pair.second, c.getString(idx))
                    }
                    idx++
                }
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