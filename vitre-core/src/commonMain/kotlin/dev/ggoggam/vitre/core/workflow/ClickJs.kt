package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.webview.ScriptOutcomeUnknownException
import kotlinx.serialization.json.JsonPrimitive

/** One synchronous resolution, validation, and DOM click; true confirms dispatch completed. */
internal object ClickJs {
    fun click(locator: Locator): String =
        """
        (function(){
          var matches;
          try { matches = ${LocatorJs.all(locator)}; }
          catch (error) { return 'The locator is invalid: ' + String(error.message || error); }
          if (matches.length === 0) return 'No element matched. Take a new snapshot and choose an existing target.';
          if (matches.length !== 1) return 'The locator matched ' + matches.length + ' elements. Use a unique locator or snapshot handle.';
          var el = matches[0];
          if (el.nodeType !== 1 || typeof el.click !== 'function') return 'The target is not an element supporting a DOM click.';
          if (!el.isConnected) return 'The target was removed. Take a new snapshot.';
          if (el.matches(':disabled')) return 'The target is disabled. Wait for it to become enabled.';
          var visibility = getComputedStyle(el).visibility;
          if (visibility === 'hidden' || visibility === 'collapse') return 'The target is hidden. Wait for it to become visible.';
          for (var node = el; node; node = node.parentElement) {
            if (node.inert || node.hasAttribute('inert')) return 'The target is in an inert region.';
            if (node.getAttribute('aria-disabled') === 'true') return 'The target or its container is aria-disabled.';
            var style = getComputedStyle(node);
            if (node.hidden || style.display === 'none' || Number(style.opacity) === 0) return 'The target is hidden. Wait for it to become visible.';
          }
          if (!Array.from(el.getClientRects()).some(function(rect) { return rect.width > 0 && rect.height > 0; })) {
            return 'The target has no visible layout box. Wait for it to become visible.';
          }
          el.click();
          return true;
        })()
        """.trimIndent()

    fun checkResult(
        locator: Locator,
        raw: String,
    ) {
        if (raw == "true") return
        val result = runCatching { WorkflowJson.parseToJsonElement(raw) }.getOrNull()
        if (result == JsonPrimitive(true)) return
        if (result is JsonPrimitive && result.isString) {
            throw ActionRejectedException("Cannot click ${locator.describe()}: ${result.content}")
        }
        // Android can report null for a synchronous JavaScript exception. Only the explicit
        // success marker proves dispatch completed; missing/malformed acknowledgements are unsafe
        // to replay because the click may have run before the answer was lost.
        throw ScriptOutcomeUnknownException(
            "The click on ${locator.describe()} returned no valid acknowledgement; it may already have taken effect. " +
                "Inspect the page before retrying.",
        )
    }
}

/** The action's preconditions failed before dispatch. */
internal class ActionRejectedException(
    message: String,
) : IllegalStateException(message)
