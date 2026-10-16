package dev.ggoggam.vitre.core.webview

import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.callback.CefSchemeHandlerFactory
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where `loadHtml` markup is served from: a generated `file:///vitre-html/<id>` URL that a scheme
 * handler answers with the caller's string.
 *
 * CEF has no `loadDataWithBaseURL`. A `data:` URL would do the job, but the document's URL is
 * something this library reports — on the bridge, in a snapshot, to an agent — and a page-sized
 * base64 blob is a poor thing to hand any of them. A short generated URL with the markup behind it
 * keeps the report readable.
 *
 * Documents are kept per browser, and only the last few: enough that going back to one still
 * finds it, without a lane that resets onto a placeholder before every run holding every
 * placeholder it ever showed.
 */
internal object CefHtmlDocuments {
    private val registered = AtomicBoolean(false)
    private val documents = ConcurrentHashMap<Int, MutableMap<String, ByteArray>>()

    /**
     * Installs the scheme handler, once per process. Called on browser creation rather than at
     * startup, so it works whether `CefRuntime` started CEF or the host did.
     */
    fun install() {
        if (!registered.compareAndSet(false, true)) return
        if (!CefApp.getInstance().registerSchemeHandlerFactory(SCHEME, "", Factory)) {
            registered.set(false)
            throw IllegalStateException("CEF refused the scheme handler loadHtml is served through")
        }
    }

    /** Stores [html] for [browser] and returns the URL to navigate it to. */
    fun put(
        browser: CefBrowser,
        html: String,
    ): String {
        val id = UUID.randomUUID().toString()
        documents
            .computeIfAbsent(browser.identifier) { recentDocuments() }
            .put(id, html.toByteArray(Charsets.UTF_8))
        return PREFIX + id
    }

    /** Drops everything stored for [browser]; called when its controller closes. */
    fun forget(browser: CefBrowser) {
        documents.remove(browser.identifier)
    }

    private fun recentDocuments(): MutableMap<String, ByteArray> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, ByteArray>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > KEPT_PER_BROWSER
            },
        )

    private object Factory : CefSchemeHandlerFactory {
        /** Null for every other `file://` URL, which leaves it to Chromium. */
        override fun create(
            browser: CefBrowser?,
            frame: CefFrame?,
            schemeName: String?,
            request: CefRequest?,
        ): CefResourceHandler? {
            val url = request?.url ?: return null
            if (!url.startsWith(PREFIX)) return null
            val id = url.removePrefix(PREFIX).substringBefore('#').substringBefore('?')
            val body = browser?.let { documents[it.identifier]?.get(id) } ?: return null
            return Document(body)
        }
    }

    private class Document(
        private val body: ByteArray,
    ) : CefResourceHandlerAdapter() {
        private var offset = 0

        override fun processRequest(
            request: CefRequest?,
            callback: CefCallback?,
        ): Boolean {
            callback?.Continue()
            return true
        }

        override fun getResponseHeaders(
            response: CefResponse?,
            responseLength: IntRef?,
            redirectUrl: StringRef?,
        ) {
            if (response == null) return
            response.status = 200
            response.mimeType = "text/html"
            // The charset does not travel on `mimeType`; without the header a UTF-8 document that
            // does not declare its own charset is decoded as Latin-1.
            response.setHeaderMap(hashMapOf("Content-Type" to "text/html; charset=utf-8"))
            responseLength?.set(body.size)
        }

        override fun readResponse(
            dataOut: ByteArray?,
            bytesToRead: Int,
            bytesRead: IntRef?,
            callback: CefCallback?,
        ): Boolean {
            if (dataOut == null || offset >= body.size) {
                bytesRead?.set(0)
                return false
            }
            val count = minOf(bytesToRead, dataOut.size, body.size - offset)
            System.arraycopy(body, offset, dataOut, 0, count)
            offset += count
            bytesRead?.set(count)
            return true
        }
    }

    private const val SCHEME = "file"
    private const val PREFIX = "file:///vitre-html/"
    private const val KEPT_PER_BROWSER = 8
}
