package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.bridge.jsString

/**
 * Turns [Locator]s and [WorkflowStep.Extract.Source]s into JavaScript expressions.
 *
 * Every expression the engine sends is built here so the two query languages cannot drift apart:
 * a step should behave identically whether it was given CSS or XPath, and the only way to be sure
 * of that is for one function to decide.
 */
internal object LocatorJs {
    private const val FIRST_ORDERED_NODE_TYPE = 9
    private const val ORDERED_NODE_SNAPSHOT_TYPE = 7

    /** An expression for the first match, or `null`. [scope] is the JS expression to search under. */
    fun first(
        locator: Locator,
        scope: String = "document",
    ): String =
        when (locator) {
            is Locator.Css -> {
                "$scope.querySelector(${jsString(locator.selector)})"
            }

            // `document.evaluate` is called on the document even when resolving relative to a node —
            // the context node is the second argument, which is what makes `.//` inside a row work.
            is Locator.XPath -> {
                "document.evaluate(${jsString(locator.expression)},$scope,null," +
                    "$FIRST_ORDERED_NODE_TYPE,null).singleNodeValue"
            }

            // [scope] is ignored, and has to be: a handle names one element outright rather than
            // describing a search, so there is nothing for a scope to narrow.
            is Locator.Handle -> {
                SnapshotJs.resolve(locator.ref)
            }
        }

    /** An expression for an array of every match, in document order. */
    fun all(
        locator: Locator,
        scope: String = "document",
    ): String =
        when (locator) {
            is Locator.Css -> {
                "Array.from($scope.querySelectorAll(${jsString(locator.selector)}))"
            }

            // A snapshot rather than an iterator: an iterator is invalidated if the document
            // mutates while it is being read, and a page that is still settling does mutate.
            is Locator.XPath -> {
                "(function(){var s=document.evaluate(${jsString(locator.expression)},$scope,null," +
                    "$ORDERED_NODE_SNAPSHOT_TYPE,null);var o=[];" +
                    "for(var i=0;i<s.snapshotLength;i++){o.push(s.snapshotItem(i));}return o;})()"
            }

            // A handle is one element, so the set it describes has either one member or none.
            is Locator.Handle -> {
                "[${SnapshotJs.resolve(locator.ref)}].filter(Boolean)"
            }
        }

    /** An expression reading a value out of the node [node] evaluates to. */
    fun read(
        node: String,
        from: WorkflowStep.Extract.Source,
    ): String =
        when (from) {
            WorkflowStep.Extract.Source.Text -> {
                "(($node)?.textContent??'').trim()"
            }

            // Optional *call*, not just optional access: an XPath expression can select an
            // attribute node directly (`.//h2/@aria-label`), and an Attr has no getAttribute. This
            // way such a locator yields '' instead of throwing — its value is read with Text.
            is WorkflowStep.Extract.Source.Attribute -> {
                "(($node)?.getAttribute?.(${jsString(from.name)}))??''"
            }

            is WorkflowStep.Extract.Source.Property -> {
                "String(($node)?.[${jsString(from.name)}]??'')"
            }
        }
}
