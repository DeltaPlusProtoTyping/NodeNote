package com.nodenote.storage

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
