package dev.ggoggam.vitre.core.webview

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.cef.browser.CefBrowser
import org.cef.handler.CefRenderHandler
import org.cef.handler.CefRenderHandlerAdapter
import java.awt.Component
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.nio.ByteBuffer
import javax.swing.JPanel

/**
 * A lane's pixels, delivered to whoever wants to draw them.
 *
 * This is the desktop's answer to a problem the other two platforms do not have. On Android and iOS
 * a WebView *is* a platform view, and hosting it is a matter of putting it in the hierarchy. CEF
 * will do that too — its windowed mode hands back an AWT component — and that turns out to be the
 * wrong trade inside Compose, for two reasons that show up together:
 *
 *  - **A heavyweight AWT component always paints above Compose.** So anything the host draws over
 *    the page — a bottom sheet reporting what a workflow is doing, a dialog, a toolbar — is behind
 *    it and invisible. The sample's single-page runner is exactly that shape, and the page winning
 *    that fight is not a compromise, it is the run detail disappearing.
 *  - **It has to be in a window before it paints at all.** A CEF browser realises its native window
 *    when its component is added to a hierarchy, so one created ahead of that shows a blank region
 *    that still occupies the space — which reads as a broken lane rather than a lane that has
 *    nowhere to draw yet.
 *
 * Offscreen rendering sidesteps both: CEF paints into a buffer, [frames] carries it, and the host
 * draws it as ordinary content it fully controls. Compose z-order, clipping, scrolling and
 * animation then work on a lane the way they work on an `Image`.
 *
 * This class deliberately knows nothing about Compose — it is raw BGRA and AWT input events, so the
 * core module keeps no UI-toolkit dependency. `vitre-compose` has the composable that draws it.
 */
class CefSurface internal constructor() {
    /**
     * One painted frame, in the layout CEF produces: BGRA, premultiplied, [width] × [height]
     * **device** pixels, row stride `width * 4`.
     *
     * A fresh array per paint rather than a reused buffer. The alternative — one buffer the reader
     * borrows — needs a lock held across whatever the reader does with it, and the reader here is a
     * draw pass. CEF only paints when something changed, and [CefWebViewController] caps the
     * windowless frame rate, so the garbage is bounded by how much the page actually animates.
     */
    class Frame internal constructor(
        val pixels: ByteArray,
        val width: Int,
        val height: Int,
    )

    private val _frames = MutableStateFlow<Frame?>(null)

    /** The most recent frame, or null before the first paint. Conflated: only the latest matters. */
    val frames: StateFlow<Frame?> get() = _frames.asStateFlow()

    private val _popup = MutableStateFlow<Popup?>(null)

    /**
     * The page's own popup — a `<select>` dropdown, an autofill list — which CEF renders as a
     * *separate* surface with its own paint calls rather than into the page.
     *
     * Drawn by the host on top of [frames] at [Popup.bounds]. Without this a dropdown opens,
     * swallows clicks, and is invisible.
     */
    val popup: StateFlow<Popup?> get() = _popup.asStateFlow()

    class Popup internal constructor(
        val frame: Frame,
        /** Where to draw it, in logical (unscaled) coordinates within the lane. */
        val bounds: Rectangle,
    )

    /**
     * The AWT component CEF is given as the nominal source of events and the drag target.
     *
     * Never displayed and never added to anything — offscreen rendering means there is no component
     * to show. It exists because JCEF's input and drag paths want a non-null `Component` to hang
     * events off, and because a synthesised `MouseEvent` needs a source.
     */
    internal val eventSource: Component = JPanel()

    @Volatile private var viewRect = Rectangle(0, 0, 1, 1)

    @Volatile private var deviceScale = 1.0

    @Volatile private var screenOrigin = Point(0, 0)

    @Volatile private var popupBounds: Rectangle? = null

    @Volatile private var browser: CefBrowser? = null

    internal fun attach(browser: CefBrowser) {
        this.browser = browser
    }

    /**
     * Tells CEF how big the lane is now, in logical pixels, and at what display scale.
     *
     * Both are the host's to report because only the host knows: the size comes from layout and the
     * scale from the display the window is on. A lane whose size is never reported renders at the
     * 1×1 default and looks like a page that failed to load.
     */
    fun resize(
        width: Int,
        height: Int,
        deviceScaleFactor: Double = this.deviceScale,
    ) {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val scale = deviceScaleFactor.coerceAtLeast(0.1)
        if (viewRect.width == w && viewRect.height == h && deviceScale == scale) return
        viewRect = Rectangle(0, 0, w, h)
        deviceScale = scale
        browser?.wasResized(w, h)
    }

    /**
     * Where the lane's top-left corner sits on the desktop, in screen coordinates.
     *
     * Only used to place the page's own popups. Reporting it wrong puts a `<select>` dropdown
     * somewhere else on screen; not reporting it puts it at the top-left of the display.
     */
    fun setScreenOrigin(
        x: Int,
        y: Int,
    ) {
        screenOrigin = Point(x, y)
    }

    /**
     * Pointer input, in logical pixels relative to the lane's top-left corner.
     *
     * Named for the intent rather than taking an AWT event, so a caller never has to synthesise one
     * — which would mean knowing about the placeholder component these are sourced from, and about
     * CEF's own double-click detection. The host has already decided what a click is; these just
     * say what happened and where.
     */
    fun pointerMoved(
        x: Int,
        y: Int,
        pressed: Boolean = false,
    ) = dispatch(mouseEvent(if (pressed) MouseEvent.MOUSE_DRAGGED else MouseEvent.MOUSE_MOVED, x, y))

    fun pointerPressed(
        x: Int,
        y: Int,
    ) = dispatch(mouseEvent(MouseEvent.MOUSE_PRESSED, x, y))

    fun pointerReleased(
        x: Int,
        y: Int,
    ) = dispatch(mouseEvent(MouseEvent.MOUSE_RELEASED, x, y))

    /** [delta] is in scroll units, positive downwards, the way Compose reports it. */
    fun scrolled(
        x: Int,
        y: Int,
        delta: Float,
    ) {
        browser?.sendMouseWheelEvent(
            MouseWheelEvent(
                eventSource,
                MouseWheelEvent.MOUSE_WHEEL,
                System.currentTimeMillis(),
                0,
                x,
                y,
                0,
                false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL,
                SCROLL_UNITS_PER_NOTCH,
                delta.toInt(),
            ),
        )
    }

    /**
     * Forwards a key event as the toolkit delivered it.
     *
     * The AWT event is passed through rather than rebuilt because key location, modifier state and
     * composition information are exactly what a rebuilt event loses, and they are what make
     * shortcuts and non-Latin input work.
     */
    fun dispatch(event: KeyEvent) {
        browser?.sendKeyEvent(event)
    }

    private fun dispatch(event: MouseEvent) {
        browser?.sendMouseEvent(event)
    }

    /**
     * `when` is the current time and the click count is always 1: CEF uses the timestamp for its
     * own double-click detection, and the host has already decided what a click is, so passing its
     * count through would double-count.
     */
    private fun mouseEvent(
        id: Int,
        x: Int,
        y: Int,
    ): MouseEvent = MouseEvent(eventSource, id, System.currentTimeMillis(), 0, x, y, 1, false, MouseEvent.BUTTON1)

    /** Tells the page whether it has keyboard focus, which is what makes a caret blink and type. */
    fun setFocus(focused: Boolean) {
        browser?.setFocus(focused)
    }

    /** Handed to CEF at browser creation; see `CefWebViewController.create`. */
    internal val renderHandler: CefRenderHandler =
        object : CefRenderHandlerAdapter() {
            override fun getViewRect(browser: CefBrowser?): Rectangle = viewRect

            override fun getDeviceScaleFactor(browser: CefBrowser?): Double = deviceScale

            override fun getScreenPoint(
                browser: CefBrowser?,
                viewPoint: Point?,
            ): Point {
                val point = viewPoint ?: Point(0, 0)
                return Point(screenOrigin.x + point.x, screenOrigin.y + point.y)
            }

            override fun onPopupShow(
                browser: CefBrowser?,
                show: Boolean,
            ) {
                if (!show) {
                    popupBounds = null
                    _popup.value = null
                }
            }

            override fun onPopupSize(
                browser: CefBrowser?,
                size: Rectangle?,
            ) {
                popupBounds = size
            }

            /**
             * Called on a CEF thread with a direct buffer that is only valid for the duration of
             * the call, which is why the bytes are copied out rather than retained.
             */
            override fun onPaint(
                browser: CefBrowser?,
                popup: Boolean,
                dirtyRects: Array<out Rectangle>?,
                buffer: ByteBuffer?,
                width: Int,
                height: Int,
            ) {
                val source = buffer ?: return
                if (width <= 0 || height <= 0) return
                val pixels = ByteArray(width * height * BYTES_PER_PIXEL)
                // A duplicate so the position this read consumes is ours, not the buffer CEF will
                // go on using. `rewind` because a shared position is not something to assume.
                source.duplicate().rewind().get(pixels)
                val frame = Frame(pixels, width, height)
                if (popup) {
                    val bounds = popupBounds ?: return
                    _popup.value = Popup(frame, bounds)
                } else {
                    _frames.value = frame
                }
            }

            /**
             * True to say the cursor was handled. Offscreen there is no component whose cursor CEF
             * could set, so the host does it — see the composable, which maps this onto the pointer
             * icon for the region it drew.
             */
            override fun onCursorChange(
                browser: CefBrowser?,
                cursorType: Int,
            ): Boolean {
                cursor = cursorType
                return true
            }
        }

    /** The cursor the page last asked for, as a CEF cursor-type ordinal. */
    @Volatile
    var cursor: Int = 0
        private set

    private companion object {
        const val BYTES_PER_PIXEL = 4
        const val SCROLL_UNITS_PER_NOTCH = 3
    }
}
