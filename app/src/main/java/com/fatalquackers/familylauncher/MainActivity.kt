package com.fatalquackers.familylauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.usage.UsageStatsManager
import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.wifi.WifiManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Family Home: a full-screen WebView that shows assets/index.html.
 * The page talks to Android through the "Android" bridge below for
 * installed apps, launching, recent apps, photos, settings and network.
 */
class MainActivity : Activity() {

    companion object {
        const val PREFS = "family_home"
        val STOCK = listOf(
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher"
        )
    }

    private lateinit var web: WebView
    private val io = Executors.newFixedThreadPool(4)
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    @Volatile private var appsCache: String? = null
    @Volatile private var appsCacheAt = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        takeConfig(intent)
        getExternalFilesDir("photos") // make sure the photos folder exists for the TV Tool

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
        web.requestFocus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (takeConfig(intent)) js("window.onConfig&&onConfig()")
        js("window.onHome&&onHome()")
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.requestFocus()
        js("window.onResume&&onResume()")
    }

    // The TV's browser engine only reports OK when it's released, so "hold OK"
    // is detected here and passed to the page.
    private var okLong = false
    private val longOk = Runnable { okLong = true; js("window.onLongOk&&onLongOk()") }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER ||
            k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    okLong = false
                    ui.removeCallbacks(longOk)
                    ui.postDelayed(longOk, 550)
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                ui.removeCallbacks(longOk)
                if (!okLong) js("window.onOk&&onOk()")
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // A home screen never closes; the page decides what Back does.
        js("window.onBack&&onBack()")
    }

    private fun js(code: String) {
        if (::web.isInitialized) web.evaluateJavascript(code, null)
    }

    /** The TV Tool sends settings as: am start ... --es config_b64 <base64 json> */
    private fun takeConfig(i: Intent?): Boolean {
        val b64 = i?.getStringExtra("config_b64") ?: return false
        return try {
            val json = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            JSONObject(json)
            prefs.edit().putString("config", json).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    inner class Bridge {
        @JavascriptInterface fun model(): String = Build.MODEL ?: ""

        @JavascriptInterface fun version(): String = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) { "" }

        @JavascriptInterface fun config(): String = prefs.getString("config", "{}") ?: "{}"

        @JavascriptInterface fun apps(): String = appsJson()

        @JavascriptInterface fun launch(pkg: String): Boolean = launchPkg(pkg)

        @JavascriptInterface fun recent(max: Int): String = recentJson(max)

        @JavascriptInterface fun photos(): String = photosJson()

        @JavascriptInterface fun get(id: String, url: String, headers: String) = request(id, "GET", url, headers, "")

        @JavascriptInterface fun req(id: String, method: String, url: String, headers: String, body: String) =
            request(id, method, url, headers, body)

        @JavascriptInterface fun discover(id: String) = discoverJellyfin(id)

        @JavascriptInterface fun setConfig(json: String): Boolean = try {
            JSONObject(json); prefs.edit().putString("config", json).apply(); true
        } catch (e: Exception) { false }

        @JavascriptInterface fun versionCode(): Int = try {
            packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
        } catch (e: Exception) { 0 }

        @JavascriptInterface fun homeStatus(): Boolean {
            val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val full = "$packageName/${HomeWatcher::class.java.name}"
            return s.split(':').any { it.equals(full, true) || it.equals("$packageName/.HomeWatcher", true) }
        }

        @JavascriptInterface fun openAccessibility() {
            ui.post { tryStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }

        @JavascriptInterface fun installUpdate(url: String) = downloadAndInstall(url)

        /** Android TV's shared "Watch next" list (other apps' continue watching). */
        @JavascriptInterface fun watchNext(): String = watchNextJson()

        @JavascriptInterface fun openIntent(uri: String, pkg: String): Boolean {
            val i = try { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) } catch (e: Exception) { null }
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                rememberRecent(pkg)
                ui.post { if (!tryStart(i)) launchPkg(pkg) }
                return true
            }
            return launchPkg(pkg)
        }

        @JavascriptInterface fun deviceInfo(): String {
            val o = JSONObject()
            try {
                val st = android.os.StatFs(android.os.Environment.getDataDirectory().path)
                o.put("free", st.availableBytes)
                o.put("total", st.totalBytes)
            } catch (_: Exception) { }
            try {
                val ip = java.net.NetworkInterface.getNetworkInterfaces().toList()
                    .flatMap { it.inetAddresses.toList() }
                    .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                o.put("ip", ip?.hostAddress ?: "")
            } catch (_: Exception) { }
            o.put("model", Build.MODEL ?: "")
            o.put("android", Build.VERSION.RELEASE ?: "")
            return o.toString()
        }

        @JavascriptInterface fun settings() {
            ui.post { tryStart(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }

        @JavascriptInterface fun googleHome() {
            ui.post { openStockHome() }
        }

        @JavascriptInterface fun openItem(pkg: String, itemId: String): Boolean =
            openJellyfin(pkg, itemId, "view", 0L, "", "")

        /** Opens a show or movie straight in Wholphin (or the official Jellyfin app). */
        @JavascriptInterface fun openJellyfin(pkg: String, itemId: String, action: String, positionMs: Long,
                                              userId: String, serverId: String): Boolean {
            if (itemId.isEmpty()) return launchPkg(pkg)
            val id = uuid(itemId)
            val i = if (pkg == "com.github.damontecres.wholphin") {
                val play = action == "play"
                val uri = Uri.Builder().scheme("wholphin").authority(if (play) "play" else "view")
                    .appendQueryParameter("itemId", id).apply {
                        if (play && positionMs > 0) appendQueryParameter("position", positionMs.toString())
                    }.build()
                Intent(if (play) "com.github.damontecres.wholphin.PLAYBACK" else Intent.ACTION_VIEW, uri).apply {
                    setPackage(pkg)
                    putExtra("itemId", id)
                    if (play) putExtra("position", maxOf(0L, positionMs))
                    if (userId.isNotEmpty() && serverId.isNotEmpty()) {
                        putExtra("userId", uuid(userId))
                        putExtra("serverId", uuid(serverId))
                    }
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(itemId)).setPackage(pkg)
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            rememberRecent(pkg)
            ui.post { if (!tryStart(i)) launchPkg(pkg) }
            return true
        }

        @JavascriptInterface fun appInfo(pkg: String) {
            ui.post {
                tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        @JavascriptInterface fun uninstall(pkg: String) {
            ui.post {
                appsCache = null
                tryStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        @JavascriptInterface fun toast(msg: String) {
            ui.post { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    /** Jellyfin ids come without dashes; Wholphin expects normal UUIDs. */
    private fun uuid(s: String): String =
        if (s.length == 32 && s.all { it.isLetterOrDigit() })
            "${s.substring(0, 8)}-${s.substring(8, 12)}-${s.substring(12, 16)}-${s.substring(16, 20)}-${s.substring(20)}"
        else s

    // ---------- Apps ----------
    private fun appsJson(): String {
        val cached = appsCache
        if (cached != null && System.currentTimeMillis() - appsCacheAt < 5 * 60_000L) return cached
        val pm = packageManager
        val seen = LinkedHashMap<String, ResolveInfo>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val p = ri.activityInfo.packageName
                if (p != packageName && !seen.containsKey(p)) seen[p] = ri
            }
        }
        val arr = JSONArray()
        for ((p, ri) in seen) {
            try {
                val o = JSONObject()
                o.put("pkg", p)
                o.put("name", ri.loadLabel(pm).toString())
                val banner = ri.activityInfo.loadBanner(pm) ?: ri.activityInfo.applicationInfo.loadBanner(pm)
                if (banner != null) o.put("banner", toDataUrl(banner, 320, 180))
                else o.put("icon", toDataUrl(ri.loadIcon(pm), 144, 144))
                arr.put(o)
            } catch (e: Exception) { /* skip broken app */ }
        }
        val s = arr.toString()
        appsCache = s
        appsCacheAt = System.currentTimeMillis()
        return s
    }

    private fun toDataUrl(d: Drawable, w: Int, h: Int): String {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(c)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun launchPkg(pkg: String): Boolean {
        val pm = packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        rememberRecent(pkg)
        ui.post { tryStart(i) }
        return true
    }

    private fun tryStart(i: Intent): Boolean = try {
        startActivity(i); true
    } catch (e: Exception) { false }

    private fun rememberRecent(pkg: String) {
        val list = mutableListOf(pkg)
        try {
            val arr = JSONArray(prefs.getString("recent", "[]"))
            for (k in 0 until arr.length()) {
                val p = arr.getString(k)
                if (p != pkg) list.add(p)
            }
        } catch (e: Exception) { }
        prefs.edit().putString("recent", JSONArray(list.take(12)).toString()).apply()
    }

    /** Recently used apps from the system (needs GET_USAGE_STATS, granted by the TV Tool). */
    private fun recentJson(max: Int): String {
        val skip = STOCK.toSet() + setOf(packageName, "com.android.tv.settings", "com.android.systemui",
            "com.google.android.katniss", "com.google.android.tungsten.setupwraith", "android")
        val out = mutableListOf<String>()
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 7L * 86_400_000L, now)
            if (stats != null) {
                for (s in stats.filter { it.totalTimeInForeground > 0 && it.packageName !in skip }
                    .sortedByDescending { it.lastTimeUsed }) {
                    if (s.packageName !in out) out.add(s.packageName)
                }
            }
        } catch (e: Exception) { }
        if (out.isEmpty()) {
            try {
                val arr = JSONArray(prefs.getString("recent", "[]"))
                for (k in 0 until arr.length()) out.add(arr.getString(k))
            } catch (e: Exception) { }
        }
        return JSONArray(out.take(maxOf(1, max) * 3)).toString()
    }

    // ---------- Watch next (other apps) ----------
    private fun watchNextJson(): String {
        val arr = JSONArray()
        try {
            contentResolver.query(Uri.parse("content://android.media.tv/watch_next_program"),
                null, null, null, "last_engagement_time_utc_millis DESC")?.use { c ->
                fun str(col: String): String { val i = c.getColumnIndex(col); return if (i >= 0) (c.getString(i) ?: "") else "" }
                fun num(col: String): Long { val i = c.getColumnIndex(col); return if (i >= 0) c.getLong(i) else 0L }
                while (c.moveToNext() && arr.length() < 15) {
                    val pkg = str("package_name")
                    if (pkg == packageName) continue
                    if (str("browsable") == "0") continue
                    val o = JSONObject()
                    o.put("pkg", pkg)
                    o.put("title", str("title"))
                    o.put("episode", str("episode_title"))
                    o.put("season", str("season_display_number"))
                    o.put("ep", str("episode_display_number"))
                    o.put("desc", str("short_description"))
                    o.put("intent", str("intent_uri"))
                    o.put("dur", num("duration_millis"))
                    o.put("pos", num("last_playback_position_millis"))
                    o.put("type", num("watch_next_type"))
                    o.put("art", artUrl(str("poster_art_uri").ifEmpty { str("thumbnail_uri") }))
                    arr.put(o)
                }
            }
        } catch (e: Exception) {
            return JSONObject().put("error", e.javaClass.simpleName + ": " + (e.message ?: "")).toString()
        }
        return arr.toString()
    }

    /** Web pictures pass straight through; pictures stored on the TV become data URLs. */
    private fun artUrl(u: String): String {
        if (u.isEmpty() || u.startsWith("http")) return u
        return try {
            contentResolver.openInputStream(Uri.parse(u))?.use { inp ->
                val bytes = inp.readBytes()
                if (bytes.size > 400_000) "" else "data:image/*;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            } ?: ""
        } catch (e: Exception) { "" }
    }

    // ---------- Photos ----------
    private fun photosJson(): String {
        val arr = JSONArray()
        val dir = getExternalFilesDir("photos") ?: return "[]"
        val exts = setOf("jpg", "jpeg", "png", "webp")
        dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in exts }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { arr.put(Uri.fromFile(it).toString()) }
        return arr.toString()
    }

    // ---------- Network (Jellyfin, weather) ----------
    private fun callback(id: String, code: Int, body: String) {
        val script = "window.__net&&__net(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(body) + ")"
        ui.post { js(script) }
    }

    private fun request(id: String, method: String, url: String, headers: String, body: String) {
        io.execute {
            var code = 0
            var text = ""
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 15000
                c.requestMethod = method.uppercase()
                c.setRequestProperty("Accept", "application/json")
                val h = JSONObject(if (headers.isBlank()) "{}" else headers)
                val keys = h.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    c.setRequestProperty(k, h.getString(k))
                }
                if (method.uppercase() == "POST") {
                    c.doOutput = true
                    if (h.optString("Content-Type").isEmpty()) c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect()
            } catch (e: Exception) {
                text = e.message ?: "error"
            }
            callback(id, code, text)
        }
    }

    /** Jellyfin answers "who is JellyfinServer?" on UDP port 7359. */
    private fun discoverJellyfin(id: String) {
        io.execute {
            val found = JSONArray()
            val seen = HashSet<String>()
            var lock: WifiManager.MulticastLock? = null
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                lock = wm.createMulticastLock("familyhome").apply { setReferenceCounted(false); acquire() }
            } catch (_: Exception) { }
            try {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    s.soTimeout = 700
                    val msg = "who is JellyfinServer?".toByteArray()
                    val to = InetAddress.getByName("255.255.255.255")
                    repeat(2) { s.send(DatagramPacket(msg, msg.size, to, 7359)) }
                    val buf = ByteArray(4096)
                    val end = System.currentTimeMillis() + 3000
                    while (System.currentTimeMillis() < end) {
                        try {
                            val p = DatagramPacket(buf, buf.size)
                            s.receive(p)
                            val o = JSONObject(String(p.data, 0, p.length, Charsets.UTF_8))
                            val key = o.optString("Id", o.optString("Address"))
                            if (seen.add(key)) found.put(o)
                        } catch (_: SocketTimeoutException) {
                        } catch (_: Exception) { }
                    }
                }
            } catch (_: Exception) { }
            try { lock?.release() } catch (_: Exception) { }
            callback(id, 200, found.toString())
        }
    }

    // ---------- In-app updates ----------
    private fun updateStatus(msg: String) {
        ui.post { js("window.onUpdate&&onUpdate(" + JSONObject.quote(msg) + ")") }
    }

    private fun downloadAndInstall(url: String) {
        io.execute {
            try {
                updateStatus("Downloading the update…")
                val f = File(cacheDir, "update.apk")
                var u = URL(url)
                var hops = 0
                while (true) {
                    val c = u.openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false
                    c.connectTimeout = 10000
                    c.readTimeout = 30000
                    val code = c.responseCode
                    if (code in 300..399) {
                        u = URL(u, c.getHeaderField("Location"))
                        c.disconnect()
                        if (++hops > 6) throw Exception("too many redirects")
                        continue
                    }
                    if (code != 200) throw Exception("HTTP $code")
                    c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                    c.disconnect()
                    break
                }
                updateStatus("Installing…")
                val pi = packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(packageName)
                val sid = pi.createSession(params)
                pi.openSession(sid).use { s ->
                    s.openWrite("family-home", 0, f.length()).use { out ->
                        f.inputStream().use { it.copyTo(out) }
                        s.fsync(out)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val p = PendingIntent.getBroadcast(this, sid, Intent(this, InstallReceiver::class.java), flags)
                    s.commit(p.intentSender)
                }
            } catch (e: Exception) {
                updateStatus("Update failed: " + (e.message ?: "unknown error"))
            }
        }
    }

    // ---------- Google TV home ----------
    private fun openStockHome() {
        // Let the Home-button watcher leave Google's home alone for a while.
        prefs.edit().putLong("allowStockUntil", System.currentTimeMillis() + 15 * 60_000L).apply()
        for (p in STOCK) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(p).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStart(i)) return
        }
        prefs.edit().putLong("allowStockUntil", 0L).apply()
        Toast.makeText(this, "Google TV home isn't available on this TV", Toast.LENGTH_SHORT).show()
    }
}
