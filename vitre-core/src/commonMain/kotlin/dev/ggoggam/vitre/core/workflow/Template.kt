package dev.ggoggam.vitre.core.workflow

/**
 * A string a step uses, which may be assembled from variables earlier steps extracted.
 *
 * Every string-valued step field was a literal until now, and that was survivable only while a
 * workflow was a straight line: the URLs it visited were all known when it was built. A step that
 * fans out is the case that breaks it — *"go to each of these twenty product pages"* has no literal
 * to write, because the twenty URLs came out of the search results the previous step extracted.
 *
 * **A bare `String` is still a literal, everywhere, always.** That is the whole reason this is a
 * type rather than a rule about `$`. Making plain strings interpolate would have reinterpreted every
 * workflow already written — a `$` in a price selector, a `{` in a JSON blob — and the ones it broke
 * would have broken quietly. Interpolation happens only where a caller wrote [template].
 *
 * Resolution happens in `WorkflowEngine`, against the same variable map [Condition] reads, so a
 * template can only ever name something an earlier step actually set. A name that was not set fails
 * the step and says which names were available, rather than substituting an empty string and
 * navigating somewhere plausible-looking and wrong.
 */
sealed interface Template {
    /** Text as written. What a plain `String` on a step becomes. */
    data class Literal(
        val value: String,
    ) : Template

    /** The value of the variable [name], as it stood when the step ran. */
    data class Variable(
        val name: String,
    ) : Template

    /**
     * Pieces joined end to end, with nothing between them.
     *
     * Named for what it holds rather than for the operation, and [of] matches [Condition.AllOf] and
     * [Condition.Not] so the sealed hierarchies in this package read the same way.
     */
    data class Parts(
        val of: List<Template>,
    ) : Template
}

/**
 * `template("https://www.amazon.com{product.path}")` — text with `{name}` replaced by variables.
 *
 * The braces are the only syntax, and they are deliberately not `${…}`: a template is very often a
 * URL or a selector, and `$` appears in enough of those to make the sigil a hazard. `{` in a URL is
 * already illegal unencoded, which is what makes it safe to claim.
 *
 * Write `{{` for a literal `{` and `}}` for a literal `}`.
 *
 * ```
 * navigate(template("https://shop.test/p/{sku}?ref={campaign}"))
 * input("#q", template("{brand} {model}"))
 * ```
 *
 * A name may hold letters, digits, `_`, `-` and `.` — dotted so a fan-out can bind an item's fields
 * as `product.path` without needing a second syntax for it.
 *
 * @throws IllegalArgumentException if a brace is unmatched or a name is empty or malformed. This is
 *   raised while the workflow is being *built*, which is the point: a mistyped template is a typo
 *   in a program, and finding it at build time costs a line number rather than a failed run.
 */
fun template(pattern: String): Template {
    val parts = mutableListOf<Template>()
    val literal = StringBuilder()

    fun flushLiteral() {
        if (literal.isNotEmpty()) {
            parts += Template.Literal(literal.toString())
            literal.clear()
        }
    }

    var index = 0
    while (index < pattern.length) {
        when (val char = pattern[index]) {
            '{', '}' -> {
                // Doubling is the escape, so it is checked before either brace is given its meaning
                // — otherwise `{{` would open a placeholder named `{`.
                if (index + 1 < pattern.length && pattern[index + 1] == char) {
                    literal.append(char)
                    index += 2
                } else if (char == '}') {
                    throw IllegalArgumentException("Unmatched `}` at $index in template `$pattern`. Write `}}` for a literal `}`.")
                } else {
                    val end = pattern.indexOf('}', startIndex = index + 1)
                    require(end != -1) { "Unclosed `{` at $index in template `$pattern`. Write `{{` for a literal `{`." }
                    val name = pattern.substring(index + 1, end)
                    require(name.isNotEmpty()) { "Empty `{}` at $index in template `$pattern` — it names no variable." }
                    require(name.all { it.isLetterOrDigit() || it in NAME_PUNCTUATION }) {
                        "`{$name}` in template `$pattern` is not a variable name. Use letters, digits, `_`, `-` or `.`."
                    }
                    flushLiteral()
                    parts += Template.Variable(name)
                    index = end + 1
                }
            }

            else -> {
                literal.append(char)
                index++
            }
        }
    }
    flushLiteral()

    // Collapsed rather than always wrapped, so that `template("no placeholders")` is `==` to the
    // Literal a plain String produces. Steps are compared by value in tests and deduplicated by
    // callers, and two spellings of the same constant text should not be two different workflows.
    return when (parts.size) {
        0 -> Template.Literal("")
        1 -> parts.single()
        else -> Template.Parts(parts)
    }
}

/**
 * The pattern this template was written as — `{name}` restored, braces re-escaped.
 *
 * For the sample's step list and for failure messages, the same job [Locator.describe] does. It
 * round-trips: `template(t.describe()) == t` for any `t` built by [template].
 */
fun Template.describe(): String =
    when (this) {
        is Template.Literal -> value.replace("{", "{{").replace("}", "}}")
        is Template.Variable -> "{$name}"
        is Template.Parts -> of.joinToString("") { it.describe() }
    }

/** Every variable this template reads, in the order they appear. */
fun Template.variableNames(): List<String> =
    when (this) {
        is Template.Literal -> emptyList()
        is Template.Variable -> listOf(name)
        is Template.Parts -> of.flatMap { it.variableNames() }
    }

private val NAME_PUNCTUATION = setOf('_', '-', '.')
