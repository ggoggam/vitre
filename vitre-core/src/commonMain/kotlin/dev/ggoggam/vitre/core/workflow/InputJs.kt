package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.bridge.jsString

/**
 * The page-side half of [WorkflowStep.Input] — and a record of why each of these scripts is not the
 * obvious one-liner it replaced.
 *
 * The obvious one-liner was
 * `el.value = text; el.dispatchEvent(new Event('input')); el.dispatchEvent(new Event('change'))`,
 * and measured against a real React 18 page in a real browser it was wrong for four of the five
 * kinds of control it accepted, **silently** in every case:
 *
 *  - a React-controlled text input took the DOM value and left the component's state empty, with
 *    `onChange` never firing — and a later `Extract` of `Source.Property("value")` read the value
 *    back, so the workflow confirmed a form the application never received;
 *  - a checkbox got its `value` *attribute* written while `checked` stayed false and no `change`
 *    fired;
 *  - a `<select>` given the label a user reads (which is what `PageSnapshot` prints) was set to
 *    `""`, because a `<select>` discards a value no option carries;
 *  - a contenteditable kept its text and gained a stray `value` property.
 *
 * Two techniques below look like something to simplify away, so both are spelled out where they are
 * used: the **native prototype setter** ([NATIVE_SET]) and **clicking a checkbox instead of
 * assigning `checked`**. Reverting either one restores a bug that no test on the page's DOM can
 * see.
 *
 * Every script **returns a status string** rather than throwing. Android reports a thrown exception
 * from `evaluateJavascript` as a bare `null` and nothing else, so a throw would reach the engine as
 * an indistinguishable blank on one platform and an error on the other — the same reason
 * [SnapshotJs.statusOf] exists. [explain] turns a status into the sentence the caller is told.
 */
internal object InputJs {
    /** The one script a [WorkflowStep.Input] runs. One round trip, one status back. */
    fun script(step: WorkflowStep.Input): String =
        when (step) {
            is WorkflowStep.Input.Fill -> fill(step)
            is WorkflowStep.Input.SetChecked -> setChecked(step)
            is WorkflowStep.Input.SelectOption -> selectOption(step)
            is WorkflowStep.Input.Press -> press(step)
        }

    /**
     * Turns a status back from [script] into what the caller should be told, or null if it worked.
     *
     * Written for whoever reads the failure and has to choose the next step, which increasingly is
     * a model: each message says what the element turned out to be and which step handles it.
     */
    fun explain(
        step: WorkflowStep.Input,
        status: String,
    ): String? {
        val where = step.locator.describe()
        return when {
            status == OK -> {
                null
            }

            status == "missing" -> {
                "Nothing matched $where, so the step had nothing to act on. Wait for the element " +
                    "first, or take a snapshot to see what the page is actually showing."
            }

            status == "disabled" -> {
                "The element at $where is disabled, so the page ignored the input."
            }

            status == "checkable" -> {
                "$where is a checkbox or radio button, and Input types a value into a field. Use " +
                    "SetChecked(locator, checked) instead — it clicks, which is what actually " +
                    "flips one."
            }

            status == "not-fillable" -> {
                "$where is not a text field, a <textarea>, a <select> or a contenteditable " +
                    "element, so there is nothing to type into. Assigning `value` to it would " +
                    "attach a stray JavaScript property and change nothing anyone can see."
            }

            status == "not-checkable" -> {
                "$where is not a checkbox or radio <input>. A control the page built out of " +
                    "role=\"checkbox\" has no `checked` property to set — Click it instead, which " +
                    "is what a user does."
            }

            status == "radio-uncheck" -> {
                "A radio button cannot be unchecked, and $where is one. Check another button in " +
                    "its group instead; that is the only way the browser lets one go off."
            }

            status == "unchanged" -> {
                "$where did not change state. The click reached it and the page cancelled it, or " +
                    "put it straight back."
            }

            status == "not-select" -> {
                "$where is not a <select>, so there is no option to choose. Use Input to type " +
                    "into a text field."
            }

            status.startsWith(NO_OPTION) -> {
                "No option of $where matches `${step.text}`. Options are matched on their value " +
                    "first and then on the label a user reads. This one offers: " +
                    status.removePrefix(NO_OPTION)
            }

            else -> {
                "$where could not be driven: the page reported `$status`."
            }
        }
    }

    // ── The scripts ────────────────────────────────────────────────────────────────────────────

    private fun fill(step: WorkflowStep.Input.Fill): String =
        "(function(){$OPTION_LABEL$PICK_OPTION$NATIVE_SET" +
            "var el=${LocatorJs.first(step.locator)};" +
            "if(!el)return 'missing';" +
            "if(el.disabled)return 'disabled';" +
            "var T=${jsString(step.text)};" +
            "var t=(el.tagName||'').toUpperCase();" +
            // A <select> is "filled" by picking an option — the same match SelectOption makes, so
            // the two spellings cannot drift into disagreeing about what identifies one.
            "if(t==='SELECT')return vpick(el,T);" +
            "if(t==='INPUT'||t==='TEXTAREA'){" +
            "var ty=(el.type||'').toLowerCase();" +
            "if(ty==='checkbox'||ty==='radio')return 'checkable';" +
            "if(el.focus)el.focus();" +
            "nset(el,T);" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));" +
            "el.dispatchEvent(new Event('change',{bubbles:true}));" +
            "return 'ok';}" +
            "if(el.isContentEditable){$CONTENT_EDITABLE return 'ok';}" +
            // Not a control at all. The old step assigned `value` here, which JavaScript happily
            // accepts on any object: the element gained a property nothing reads and the step
            // passed.
            "return 'not-fillable';})()"

    private fun setChecked(step: WorkflowStep.Input.SetChecked): String =
        "(function(){var el=${LocatorJs.first(step.locator)};" +
            "if(!el)return 'missing';" +
            "var ty=(el.type||'').toLowerCase();" +
            "if((el.tagName||'').toUpperCase()!=='INPUT'||(ty!=='checkbox'&&ty!=='radio'))" +
            "return 'not-checkable';" +
            "if(el.disabled)return 'disabled';" +
            "var W=${step.checked};" +
            // The browser gives no way to turn a radio off by acting on it; something else in the
            // group has to go on. Saying so beats a click that provably does nothing.
            "if(ty==='radio'&&!W)return 'radio-uncheck';" +
            // Click, do not assign. `el.checked=true` moves the property and fires nothing, so a
            // page listening for `change` never learns; click() runs the browser's own activation
            // behaviour, which flips it *and* fires input/change the way a finger would. Guarded on
            // the current state because an unconditional click toggles a box that was already
            // right — the likeliest way for re-running a workflow to undo it.
            "if(!!el.checked!==W)el.click();" +
            // Read back, because a page is entitled to cancel the click and this step would
            // otherwise report a tick that never happened.
            "return !!el.checked===W?'ok':'unchanged';})()"

    private fun selectOption(step: WorkflowStep.Input.SelectOption): String =
        "(function(){$OPTION_LABEL$PICK_OPTION" +
            "var el=${LocatorJs.first(step.locator)};" +
            "if(!el)return 'missing';" +
            "if((el.tagName||'').toUpperCase()!=='SELECT')return 'not-select';" +
            "if(el.disabled)return 'disabled';" +
            "return vpick(el,${jsString(step.option)});})()"

    private fun press(step: WorkflowStep.Input.Press): String {
        val key = keyOf(step.key) ?: error(unknownKey(step.key))
        val event =
            "{key:${jsString(key.key)},code:${jsString(key.code)}," +
                "keyCode:${key.keyCode},which:${key.keyCode},bubbles:true,cancelable:true}"
        return "(function(){var el=${LocatorJs.first(step.locator)};" +
            "if(!el)return 'missing';" +
            // A page binds its handler to the field, and a key event dispatched at an unfocused
            // element is one no real keystroke could have produced.
            "if(el.focus)el.focus();" +
            "var o=$event;" +
            "var live=el.dispatchEvent(new KeyboardEvent('keydown',o));" +
            // Browsers fire keypress only for keys that produce a character, and skip it entirely
            // when keydown was cancelled. Both are worth copying: a page that suppresses a key
            // expects the rest of the sequence to stop.
            (if (key.character) "if(live)el.dispatchEvent(new KeyboardEvent('keypress',o));" else "") +
            "el.dispatchEvent(new KeyboardEvent('keyup',o));" +
            "return 'ok';})()"
    }

    // ── Shared page-side helpers ───────────────────────────────────────────────────────────────

    private const val OK = "ok"

    /** Prefixed rather than bare, because the failure carries the options the control does offer. */
    private const val NO_OPTION = "no-option "

    /**
     * `nset(el, v)` — assigns `value` through the setter on the element's *prototype*.
     *
     * **Do not simplify this to `el.value = v`.** React (and every library that borrows its
     * approach) defines its own `value` property directly on the element, wrapping the native one
     * so it can tell a programmatic write from a user's typing. A plain assignment hits that
     * wrapper, the value-tracker records the new value as already-known, and React decides nothing
     * changed: `onChange` never fires and the component's state keeps whatever it had. Reaching
     * past the instance to the prototype's setter writes the value where the tracker is watching,
     * so the next event it sees looks exactly like a user typing. This is measured behaviour, not
     * folklore — the plain assignment left a React field's state empty while the DOM read back the
     * text that had just been written to it.
     *
     * The prototype has to match the element: `HTMLInputElement`'s setter throws on a `<textarea>`.
     * The `else` is for a document that has no such descriptor at all, where the naive write is at
     * least no worse than before.
     */
    private const val NATIVE_SET =
        "function nset(el,v){" +
            "var t=(el.tagName||'').toUpperCase();" +
            "var p=t==='TEXTAREA'?HTMLTextAreaElement.prototype:" +
            "(t==='SELECT'?HTMLSelectElement.prototype:HTMLInputElement.prototype);" +
            "var d=Object.getOwnPropertyDescriptor(p,'value');" +
            "if(d&&d.set){d.set.call(el,v);}else{el.value=v;}}"

    /** `vlabel(o)` — an option's visible label, whitespace-collapsed the way a snapshot prints it. */
    private const val OPTION_LABEL = "function vlabel(o){return (o.label||o.textContent||'').replace(/\\s+/g,' ').trim();}"

    /**
     * `vpick(el, want)` — selects the option whose `value` is `want`, or failing that whose label
     * is, and reports the labels it does have when neither matches.
     *
     * By index rather than by assigning `el.value`, so two options sharing a value stay distinct.
     * A bubbling `change` is what makes this visible to a framework: React reads the native change
     * event for `<select>` directly rather than going through the value-tracker it uses for text
     * inputs, so the prototype-setter trick above is neither needed nor sufficient here.
     */
    private const val PICK_OPTION =
        "function vpick(el,W){" +
            "var os=Array.from(el.options);" +
            "var i=-1;" +
            "for(var a=0;a<os.length;a++){if(os[a].value===W){i=a;break;}}" +
            "if(i<0){for(var b=0;b<os.length;b++){if(vlabel(os[b])===W){i=b;break;}}}" +
            "if(i<0)return '$NO_OPTION'+os.map(vlabel).join(' | ');" +
            "el.selectedIndex=i;" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));" +
            "el.dispatchEvent(new Event('change',{bubbles:true}));" +
            "return 'ok';}"

    /**
     * Replaces a contenteditable's text, leaving `T` as what to put there.
     *
     * `execCommand('insertText')` is what makes the change look like typing to the page — it goes
     * through the editing pipeline, fires its own `input`, and keeps the undo stack intact, none of
     * which assigning `textContent` does. But it inserts **at the caret**: without the select-all
     * in front of it, typing "typed" into an element reading "edit me" produced "typededit me".
     * Hence the range over the whole node first, and `delete` rather than an insert of nothing when
     * the text is empty.
     *
     * The fallback assigns `textContent` and fires the `input` that `execCommand` would have fired
     * itself, for a WebView that has finally retired the deprecated API.
     */
    private const val CONTENT_EDITABLE =
        "el.focus();" +
            "var s=window.getSelection?window.getSelection():null;" +
            "if(s){var r=document.createRange();r.selectNodeContents(el);" +
            "s.removeAllRanges();s.addRange(r);}" +
            "var done=false;" +
            "try{done=T===''?document.execCommand('delete',false,null):" +
            "document.execCommand('insertText',false,T);}catch(e){done=false;}" +
            "if(!done){el.textContent=T;el.dispatchEvent(new Event('input',{bubbles:true}));}"

    // ── Keys ───────────────────────────────────────────────────────────────────────────────────

    /**
     * One keystroke as the DOM describes it.
     *
     * [key] is what the key produces, [code] the physical key it sits on — a US layout, which is
     * the convention every automation tool settled on and the only one available without asking the
     * device what layout it has. [keyCode] is the deprecated numeric form, still read by a great
     * deal of shipped page code and therefore still sent.
     */
    private data class Key(
        val key: String,
        val code: String,
        val keyCode: Int,
        /** Whether a browser would fire `keypress` for it — character keys only, plus Enter. */
        val character: Boolean,
    )

    /** Resolves a key name or a single character, or null if it is neither. */
    private fun keyOf(key: String): Key? {
        NAMED[key]?.let { return it }
        if (key.length != 1) return null
        val c = key[0]
        return when {
            c in 'a'..'z' -> Key(key, "Key${c.uppercaseChar()}", c.uppercaseChar().code, true)

            c in 'A'..'Z' -> Key(key, "Key$c", c.code, true)

            c in '0'..'9' -> Key(key, "Digit$c", c.code, true)

            // A layout-dependent character. `code` is left empty rather than guessed: a browser
            // reports an empty code for a key it cannot place — during IME composition, say — so an
            // empty one is a shape page code already handles, and a wrong one is not.
            else -> PUNCTUATION[c]?.let { Key(key, it.first, it.second, true) } ?: Key(key, "", 0, true)
        }
    }

    private fun unknownKey(key: String): String =
        "`$key` is not a key. Press takes a single character, or one of: " +
            NAMED.keys
                .filter { it.isNotBlank() }
                .sorted()
                .joinToString(", ") + "."

    /**
     * The named keys, spelled as `KeyboardEvent.key` spells them.
     *
     * Deliberately without aliases — no `Esc`, no `Up`. A misspelling that fails names the whole
     * list, which costs one step; a misspelling that is quietly accepted as something else costs a
     * debugging session.
     */
    private val NAMED: Map<String, Key> =
        buildMap {
            // Enter is not a character key, and browsers fire keypress for it anyway (it carries
            // the carriage return), so page code that only listens for keypress still sees it.
            put("Enter", Key("Enter", "Enter", 13, character = true))
            put("Tab", Key("Tab", "Tab", 9, character = false))
            put("Escape", Key("Escape", "Escape", 27, character = false))
            put("Backspace", Key("Backspace", "Backspace", 8, character = false))
            put("Delete", Key("Delete", "Delete", 46, character = false))
            put("Insert", Key("Insert", "Insert", 45, character = false))
            put("ArrowUp", Key("ArrowUp", "ArrowUp", 38, character = false))
            put("ArrowDown", Key("ArrowDown", "ArrowDown", 40, character = false))
            put("ArrowLeft", Key("ArrowLeft", "ArrowLeft", 37, character = false))
            put("ArrowRight", Key("ArrowRight", "ArrowRight", 39, character = false))
            put("Home", Key("Home", "Home", 36, character = false))
            put("End", Key("End", "End", 35, character = false))
            put("PageUp", Key("PageUp", "PageUp", 33, character = false))
            put("PageDown", Key("PageDown", "PageDown", 34, character = false))
            put("Shift", Key("Shift", "ShiftLeft", 16, character = false))
            put("Control", Key("Control", "ControlLeft", 17, character = false))
            put("Alt", Key("Alt", "AltLeft", 18, character = false))
            put("Meta", Key("Meta", "MetaLeft", 91, character = false))
            // `key` is a space; `Space` is the name to write at a call site, where a bare " " is
            // indistinguishable from a typo.
            put("Space", Key(" ", "Space", 32, character = true))
            put(" ", Key(" ", "Space", 32, character = true))
            for (n in 1..12) put("F$n", Key("F$n", "F$n", 111 + n, character = false))
        }

    /** US-layout punctuation: the physical key, and the `keyCode` it reports shifted or not. */
    private val PUNCTUATION: Map<Char, Pair<String, Int>> =
        mapOf(
            '`' to ("Backquote" to 192),
            '~' to ("Backquote" to 192),
            '-' to ("Minus" to 189),
            '_' to ("Minus" to 189),
            '=' to ("Equal" to 187),
            '+' to ("Equal" to 187),
            '[' to ("BracketLeft" to 219),
            '{' to ("BracketLeft" to 219),
            ']' to ("BracketRight" to 221),
            '}' to ("BracketRight" to 221),
            '\\' to ("Backslash" to 220),
            '|' to ("Backslash" to 220),
            ';' to ("Semicolon" to 186),
            ':' to ("Semicolon" to 186),
            '\'' to ("Quote" to 222),
            '"' to ("Quote" to 222),
            ',' to ("Comma" to 188),
            '<' to ("Comma" to 188),
            '.' to ("Period" to 190),
            '>' to ("Period" to 190),
            '/' to ("Slash" to 191),
            '?' to ("Slash" to 191),
            '!' to ("Digit1" to 49),
            '@' to ("Digit2" to 50),
            '#' to ("Digit3" to 51),
            '$' to ("Digit4" to 52),
            '%' to ("Digit5" to 53),
            '^' to ("Digit6" to 54),
            '&' to ("Digit7" to 55),
            '*' to ("Digit8" to 56),
            '(' to ("Digit9" to 57),
            ')' to ("Digit0" to 48),
        )
}
