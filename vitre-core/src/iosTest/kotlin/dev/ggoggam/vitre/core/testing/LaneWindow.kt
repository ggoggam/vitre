package dev.ggoggam.vitre.core.testing

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow

/**
 * A window to hang lanes off, because a `WKWebView` outside one does not finish navigations.
 *
 * `IosWebViewPool` says this in prose — *"a `WKWebView` that is not in a window throttles timers and
 * skips layout, which presents as a lane that loads and then never finishes anything"* — and a test
 * that skips it does not get a wrong answer, it gets a 30-second `PageLoadException` from
 * `pool.open()` before the first assertion runs.
 *
 * The size matters as much as the window does: the pool builds its WebViews at `CGRectZero` because
 * the *caller* is expected to lay them out, so mounting one without giving it a frame reproduces
 * the same hang from the other direction.
 */
@OptIn(ExperimentalForeignApi::class)
class LaneWindow {
    private val root = UIViewController()
    private val window =
        UIWindow(frame = CGRectMake(0.0, 0.0, WIDTH, HEIGHT)).apply {
            rootViewController = root
            // `makeKeyAndVisible()` and not `setHidden(false)` is the obvious call and it traps:
            // becoming *key* routes through `UIWindowScene` and `BKSHIDEventDeliveryManager`, and a
            // Kotlin/Native test binary is not a `UIApplication`, so there is no scene to become key
            // in and UIKit takes a `SIGTRAP` rather than an exception. Nothing here needs key —
            // WebKit's requirement is that the view be *in a window*, which unhiding satisfies.
            setHidden(false)
        }

    /** Puts every lane in the window at a phone-ish size. Lanes overlap; nothing here looks at pixels. */
    fun mount(views: List<UIView>) {
        views.forEach { view ->
            view.setFrame(CGRectMake(0.0, 0.0, WIDTH, HEIGHT))
            root.view.addSubview(view)
        }
    }

    fun dismiss() {
        root.view.subviews
            .filterIsInstance<UIView>()
            .forEach { it.removeFromSuperview() }
        window.setHidden(true)
    }

    private companion object {
        const val WIDTH = 390.0
        const val HEIGHT = 844.0
    }
}
