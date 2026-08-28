package dev.ggoggam.vitre.core.workflow

/**
 * A yes/no question a [WorkflowStep.If] asks before choosing a branch.
 *
 * Structured rather than "a string of JS that must evaluate truthy", and the reason is the same one
 * [Locator] gives for not being a string: a condition the library can read is one it can *explain*.
 * `Failed(2, "condition `variable \`total\` is not set` — the workflow set: cart, title")` points at
 * the mistake; a JS predicate that quietly returns `undefined` points at nothing, and silently takes
 * the else branch. [JsTruthy] is still there for the questions the structured forms cannot ask, on
 * the same terms [WorkflowStep.EvaluateJs] is there.
 *
 * A condition is a value, like everything else in a workflow. Nothing here runs at build time and
 * nothing captures a Kotlin lambda — which is what lets a condition be written down, sent over MCP,
 * or held in a variable, the same as the steps around it.
 */
sealed interface Condition {
    /**
     * True when [locator] matches at least one element.
     *
     * The one place a missing element is an *answer* rather than a failure — which is exactly why a
     * [Locator.Handle] behaves differently here than anywhere else. Every other step vets its
     * handles up front and fails loudly on a stale one, because a click on nothing looks like a
     * click that worked. Here a stale handle is the honest `false`: the element it named is gone.
     */
    data class Exists(
        val locator: Locator,
    ) : Condition

    /**
     * True when the variable [name] holds exactly [value].
     *
     * Fails the step if no such variable was ever set. A typo silently taking the else branch is the
     * failure this whole type exists to rule out, and "no variable `totl`" costs one line to say.
     */
    data class VariableEquals(
        val name: String,
        val value: String,
        val ignoreCase: Boolean = false,
    ) : Condition

    /**
     * True when the variable [name] contains a match for [regex] — *contains*, not *equals*, so
     * `VariableMatches("price", "^\\$")` is the way to anchor.
     *
     * Fails the step on an unset variable, and on a [regex] that does not compile. The second is
     * worth a failure rather than a `false`: a pattern that cannot be parsed is a bug in the
     * workflow, and there is no reading of it under which the else branch is the right answer.
     */
    data class VariableMatches(
        val name: String,
        val regex: String,
    ) : Condition

    /**
     * True when [script] evaluates to a JS truthy value.
     *
     * The escape hatch, with [WorkflowStep.EvaluateJs]'s rules: an expression, not a statement list,
     * and a promise is awaited rather than returned. Truthiness is decided by the page — the script
     * is wrapped in `!!(…)` so what comes back is a real boolean rather than something this side has
     * to guess the truthiness of.
     */
    data class JsTruthy(
        val script: String,
    ) : Condition

    data class Not(
        val of: Condition,
    ) : Condition

    /** True when every condition holds. Empty is true, and short-circuits on the first false. */
    data class AllOf(
        val of: List<Condition>,
    ) : Condition

    /** True when any condition holds. Empty is false, and short-circuits on the first true. */
    data class AnyOf(
        val of: List<Condition>,
    ) : Condition
}

/** For failure messages, and for the sample's step list. Parenthesised only where it must be. */
fun Condition.describe(): String =
    when (this) {
        is Condition.Exists -> locator.describe()
        is Condition.VariableEquals -> "`$name` == \"$value\"${if (ignoreCase) " (ignoring case)" else ""}"
        is Condition.VariableMatches -> "`$name` matches /$regex/"
        is Condition.JsTruthy -> "js `$script`"
        is Condition.Not -> "not ${of.describe().parenthesizedIfCompound()}"
        is Condition.AllOf -> of.joinToString(" and ") { it.describe().parenthesizedIfCompound() }.ifEmpty { "always" }
        is Condition.AnyOf -> of.joinToString(" or ") { it.describe().parenthesizedIfCompound() }.ifEmpty { "never" }
    }

private fun String.parenthesizedIfCompound(): String = if (contains(" and ") || contains(" or ")) "($this)" else this

/** `exists(css("#results .item"))`. */
fun exists(locator: Locator): Condition.Exists = Condition.Exists(locator)

/** `exists("#results .item")` — a bare string means CSS, as everywhere else. */
fun exists(selector: String): Condition.Exists = Condition.Exists(css(selector))

/** `variableEquals("status", "ok")`. */
fun variableEquals(
    name: String,
    value: String,
    ignoreCase: Boolean = false,
): Condition.VariableEquals = Condition.VariableEquals(name, value, ignoreCase)

/** `variableMatches("price", "^\\$\\d")` — a containment test unless you anchor it. */
fun variableMatches(
    name: String,
    regex: String,
): Condition.VariableMatches = Condition.VariableMatches(name, regex)

/** `jsTruthy("document.cookie.includes('session')")`. */
fun jsTruthy(script: String): Condition.JsTruthy = Condition.JsTruthy(script)

/** `exists("#banner").not()`. */
fun Condition.not(): Condition = if (this is Condition.Not) of else Condition.Not(this)

/** `exists("#a") and exists("#b")`, flattened so a chain of three reads as one `AllOf` of three. */
infix fun Condition.and(other: Condition): Condition = Condition.AllOf((this.flatten<Condition.AllOf>()) + other.flatten<Condition.AllOf>())

/** `exists("#a") or exists("#b")`. */
infix fun Condition.or(other: Condition): Condition = Condition.AnyOf((this.flatten<Condition.AnyOf>()) + other.flatten<Condition.AnyOf>())

/**
 * The operands this condition contributes to an enclosing [T], so `a and b and c` builds one
 * three-way `AllOf` rather than a left-leaning tree of pairs. Purely cosmetic — the two evaluate
 * identically — but the flat one is what [describe] renders readably and what an equality assertion
 * in a test can be written by hand.
 */
private inline fun <reified T : Condition> Condition.flatten(): List<Condition> =
    when {
        this !is T -> listOf(this)
        this is Condition.AllOf -> of
        this is Condition.AnyOf -> of
        else -> listOf(this)
    }
