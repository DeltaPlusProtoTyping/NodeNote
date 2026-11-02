package com.nodenote.storage

import com.nodenote.local.DeviceStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Browser implementations: preferences in localStorage, picking through a
 * hidden `<input type=file>`, saving as a download. File contents cross the
 * JS boundary as Base64 strings — the simplest type both sides agree on.
 */
@OptIn(ExperimentalEncodingApi::class)
actual object ProjectStorage {

    private const val PREFIX = "nodenote."

    actual fun readPrefs(fileName: String): String? = localStorageGet(PREFIX + fileName)

    actual fun writePrefs(fileName: String, content: String): Boolean {
        localStorageSet(PREFIX + fileName, content)
        return true
    }

    actual fun pickFile(accept: String, onResult: (PickedFile?) -> Unit) {
        pickFileJs(
            accept,
            onPicked = { name, base64 -> onResult(runCatching { PickedFile(name, Base64.decode(base64)) }.getOrNull()) },
            onCancel = { onResult(null) },
        )
    }

    actual fun saveFile(suggestedFileName: String, bytes: ByteArray, onResult: (String?) -> Unit) {
        downloadJs(suggestedFileName, Base64.encode(bytes))
        onResult("Downloads/$suggestedFileName")
    }
}

/**
 * The server that served this page, including any path it is mounted under
 * (e.g. https://host:10000/nodenote when published through Tailscale Funnel),
 * unless `?server=` overrides it for development.
 */
actual fun defaultServerUrl(): String = queryParam("server") ?: pageBaseUrl()

actual val isWebPlatform: Boolean = true

private fun localStorageGet(key: String): String? = js("window.localStorage.getItem(key)")

private fun localStorageSet(key: String, value: String): Unit = js("window.localStorage.setItem(key, value)")

/** Origin + the page's directory, without a trailing slash: "/nodenote/" → ".../nodenote", "/" → "". */
private fun pageBaseUrl(): String = js("""window.location.origin + window.location.pathname.replace(/\/[^\/]*$/, "")""")

private fun queryParam(name: String): String? = js("new URLSearchParams(window.location.search).get(name)")

private fun pickFileJs(accept: String, onPicked: (String, String) -> Unit, onCancel: () -> Unit): Unit = js(
    """{
    const input = document.createElement('input');
    input.type = 'file';
    if (accept) input.accept = accept;
    input.addEventListener('cancel', () => onCancel());
    input.onchange = () => {
        const f = input.files && input.files[0];
        if (!f) { onCancel(); return; }
        const reader = new FileReader();
        reader.onload = () => { const s = reader.result; onPicked(f.name, s.substring(s.indexOf(',') + 1)); };
        reader.onerror = () => onCancel();
        reader.readAsDataURL(f);
    };
    input.click();
}""",
)

private fun downloadJs(name: String, base64: String): Unit = js(
    """{
    const bin = atob(base64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    // A real type lets phones open a PDF or picture straight away instead of "unknown file".
    const ext = name.split('.').pop().toLowerCase();
    const types = { pdf: 'application/pdf', svg: 'image/svg+xml', png: 'image/png', json: 'application/json' };
    const url = URL.createObjectURL(new Blob([bytes], { type: types[ext] || 'application/octet-stream' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
}""",
)

actual fun readClipboardText(onResult: (String?) -> Unit) {
    readClipboardJs(onText = { onResult(it) }, onFail = { onResult(null) })
}

private fun readClipboardJs(onText: (String) -> Unit, onFail: () -> Unit): Unit = js(
    """{
    if (!navigator.clipboard || !navigator.clipboard.readText) { onFail(); return; }
    navigator.clipboard.readText().then((t) => onText(t || ''), () => onFail());
}""",
)

actual fun setPageTitle(title: String) = setTitleJs(title)

private fun setTitleJs(title: String): Unit = js("document.title = title")

actual fun reloadPage(): Boolean {
    reloadJs()
    return true
}

actual fun reloadWhenHidden(canReload: () -> Boolean) = reloadWhenHiddenJs(canReload)

private fun reloadJs(): Unit = js("window.location.reload()")

private fun reloadWhenHiddenJs(canReload: () -> Boolean): Unit = js(
    """{
    const check = () => { if (document.hidden && canReload()) window.location.reload(); };
    document.addEventListener('visibilitychange', check);
    check();
}""",
)

actual fun deviceStore(): DeviceStore = object : DeviceStore {
    override fun read(key: String): String? = localStorageGet("nodenote.device." + key)
    override fun write(key: String, value: String): Boolean = localStorageTrySet("nodenote.device." + key, value)
}.also { askToKeepStorage() }

/** Next to the app, put there by the build (see shared/build.gradle.kts). */
actual suspend fun bundledDictionary(): String? = runCatching {
    HttpClient().use { it.get(pageBaseUrl() + "/dict/en_US.txt").bodyAsText() }
}.getOrNull()?.takeIf { it.length > 1000 }

actual fun startServerOnThisComputer(serverUrl: String) = Unit

private fun localStorageTrySet(key: String, value: String): Boolean = js(
    """{
    try { window.localStorage.setItem(key, value); return true; } catch (e) { return false; }
}""",
)

/** Asks the browser not to clear this site's storage on its own (an installed app usually gets this). */
private fun askToKeepStorage(): Unit = js(
    """{
    if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {});
}""",
)

/** See manifest.webmanifest: share_target opens "./?share_title=...&share_text=...&share_url=...". */
actual fun takeSharedText(): String? = takeSharedTextJs()?.trim()?.takeIf { it.isNotEmpty() }

private fun takeSharedTextJs(): String? = js(
    """(() => {
    const params = new URLSearchParams(window.location.search);
    const keys = ['share_title', 'share_text', 'share_url'];
    if (!keys.some((k) => params.has(k))) return null;
    const title = (params.get('share_title') || '').trim();
    const text = (params.get('share_text') || '').trim();
    const url = (params.get('share_url') || '').trim();
    keys.forEach((k) => params.delete(k));
    const rest = params.toString();
    window.history.replaceState(null, '', window.location.pathname + (rest ? '?' + rest : '') + window.location.hash);
    // Apps fill these in differently (many put the link in the text): keep each part once.
    const parts = [];
    if (title && !text.startsWith(title)) parts.push(title);
    if (text) parts.push(text);
    if (url && !text.includes(url)) parts.push(url);
    return parts.join('\n');
})()""",
)

actual fun notificationPermission(): String = notificationPermissionJs()

private fun notificationPermissionJs(): String = js("""(typeof Notification === 'undefined') ? '' : Notification.permission""")

actual fun requestNotificationPermission(onResult: (String) -> Unit) = requestNotificationPermissionJs(onResult)

private fun requestNotificationPermissionJs(onResult: (String) -> Unit): Unit = js(
    """{
    if (typeof Notification === 'undefined') { onResult(''); return; }
    try {
        Notification.requestPermission().then((p) => onResult(p), () => onResult(Notification.permission));
    } catch (e) { onResult(Notification.permission); }
}""",
)

actual fun showNotification(title: String, body: String, tag: String, onClick: () -> Unit): Boolean =
    showNotificationJs(title, body, tag, onClick)

private fun showNotificationJs(title: String, body: String, tag: String, onClick: () -> Unit): Boolean = js(
    """(() => {
    if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return false;
    const options = { body: body, tag: tag, icon: 'icons/icon-192.png' };
    try {
        const n = new Notification(title, options);
        n.onclick = () => { window.focus(); n.close(); onClick(); };
        return true;
    } catch (e) {
        // Phones only allow them through the service worker; its notificationclick (sw.js)
        // focuses this page and posts the tag back here.
        if (!navigator.serviceWorker) return false;
        const clicks = window.nodenoteNotificationClicks || (window.nodenoteNotificationClicks = {});
        if (!window.nodenoteNotificationListener) {
            window.nodenoteNotificationListener = true;
            navigator.serviceWorker.addEventListener('message', (event) => {
                const data = event.data || {};
                if (data.type === 'nodenote-notification' && clicks[data.tag]) clicks[data.tag]();
            });
        }
        clicks[tag] = onClick;
        navigator.serviceWorker.getRegistration().then((r) => { if (r) r.showNotification(title, options); }).catch(() => {});
        return true;
    }
})()""",
)
