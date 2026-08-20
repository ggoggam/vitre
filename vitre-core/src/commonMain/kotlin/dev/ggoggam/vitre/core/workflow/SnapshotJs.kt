package dev.ggoggam.vitre.core.workflow

import dev.ggoggam.vitre.core.bridge.jsString

/**
 * The page-side half of snapshots and handles.
 *
 * Handles have to live in the page, because the only thing that can hold a reference to a DOM node
 * is the DOM's own JavaScript context. That gives their lifetime for free and gives it correctly: a
 * navigation replaces the context, so every handle from the old document stops resolving at exactly
 * the moment it stops meaning anything. Keeping a table on the native side instead would leave
 * handles that still look valid and now point at nothing.
 *
 * Two rules the registry keeps, both of which exist to make a wrong action impossible rather than
 * merely unlikely:
 *
 *  - **Numbers are never reused.** A second snapshot mints fresh refs for elements it has not seen
 *    and keeps the existing ref for elements it has. If handles were indices into the latest
 *    snapshot, an agent that snapshotted, thought, and then acted on `e3` would act on whatever
 *    element had since taken third place — silently, and plausibly.
 *  - **Resolution failure is reportable.** [statusOf] distinguishes "no snapshot yet" from "unknown
 *    ref" from "the element has been removed", because the recovery differs: take one, re-take one,
 *    re-take one and expect the page to have changed.
 */
internal object SnapshotJs {
    /** The page-global the registry hangs off. Namespaced to avoid colliding with the page. */
    private const val REGISTRY = "window.__vitre"

    /** Creates the registry if this document has not got one yet. */
    private const val ENSURE_REGISTRY =
        "var W=$REGISTRY||($REGISTRY={});" +
            "if(!W.byRef){W.byRef=new Map();W.byEl=new WeakMap();W.next=1;}"

    /**
     * An expression for the element [ref] names, or `null`.
     *
     * **Parenthesised as a whole, and it has to be.** Callers paste this into a larger expression —
     * `X?.click()`, `X!==null`, `X.slice(0,20)` — and `?.` and `!==` both bind tighter than the `||`
     * inside. Unbracketed, `A||null?.click()` groups as `A||(null?.click())`, which evaluates the
     * lookup, never calls the method, and reports success; `A||null!==null` groups as
     * `A||(null!==null)`, so a `WaitFor` polls until it times out on an element that is right there.
     * Neither shows up as an error anywhere: the click step goes green having done nothing.
     *
     * Deliberately does not throw. Android's `evaluateJavascript` reports a thrown exception as a
     * `null` result and nothing else, so a throw here would reach the engine as an indistinguishable
     * blank on one platform and an error on the other. The engine asks [statusOf] first instead, and
     * gets the same answer on both.
     */
    fun resolve(ref: String): String = "(($REGISTRY&&$REGISTRY.byRef?$REGISTRY.byRef.get(${jsString(ref)}):null)||null)"

    /** An expression returning `"ok"`, `"no-snapshot"`, `"unknown"` or `"detached"` for [ref]. */
    fun statusOf(ref: String): String =
        "(function(){var W=$REGISTRY;" +
            "if(!W||!W.byRef)return 'no-snapshot';" +
            "var e=W.byRef.get(${jsString(ref)});" +
            "if(!e)return 'unknown';" +
            "if(!e.isConnected)return 'detached';" +
            "return 'ok';})()"

    /** Turns a [statusOf] result into the sentence a caller should be told, or null if it resolved. */
    fun explain(
        ref: String,
        status: String,
    ): String? =
        when (status) {
            "ok" -> {
                null
            }

            "no-snapshot" -> {
                "Handle `$ref` cannot be resolved: no snapshot has been taken of the current " +
                    "document. Take a snapshot first — handles do not survive a navigation."
            }

            "unknown" -> {
                "Handle `$ref` is not one this document has issued. It most likely came from a " +
                    "snapshot of a previous page; take a new snapshot."
            }

            "detached" -> {
                "Handle `$ref` refers to an element that has since been removed from the page. " +
                    "Take a new snapshot to see what replaced it."
            }

            else -> {
                "Handle `$ref` could not be resolved ($status)."
            }
        }

    /**
     * The DOM walk, as a single expression returning a [PageSnapshot]-shaped object.
     *
     * What it keeps is what an agent can act on or read: anything with a role, and any element
     * carrying prose of its own. What it drops is the rest of the document, which on a real page is
     * most of it. [maxNodes] is a hard budget rather than a hint, because the cost of overrunning it
     * is paid in the model's context window by the caller, who cannot see it coming.
     *
     * Interactive elements are not recursed into. A `<button><span>Add to cart</span></button>` is
     * one thing to click, and reporting the span underneath it as a second, differently-addressed
     * node invites the agent to click the wrong one and doubles the tokens for no information.
     */
    fun snapshot(
        maxNodes: Int,
        nameLimit: Int,
    ): String =
        """
        (function(){
        $ENSURE_REGISTRY
        var MAX=$maxNodes,NAMELEN=$nameLimit,HREFLEN=200;
        var out=[],truncated=false;
        var SKIP={SCRIPT:1,STYLE:1,NOSCRIPT:1,TEMPLATE:1,HEAD:1,SVG:1,CANVAS:1,IFRAME:1};
        var HEADING={H1:1,H2:1,H3:1,H4:1,H5:1,H6:1};
        // A heading is not interactive and is not a leaf: the ubiquitous <h2><a href>Title</a></h2>
        // search-result pattern hides the link — its ref, its href, its click target — if the walk
        // stops at the heading. So headings are recursed into; only genuinely atomic controls are here.
        var LEAF={link:1,button:1,checkbox:1,radio:1,textbox:1,searchbox:1,image:1,option:1};
        var LABELLED={textbox:1,searchbox:1,combobox:1,checkbox:1,radio:1};

        function attr(el,n){return el.getAttribute?el.getAttribute(n):null;}
        function clean(s){return (s||'').replace(/\s+/g,' ').trim();}
        function cut(s,n){s=clean(s);return s.length>n?s.slice(0,n)+'…':s;}

        function visible(el){
          var s=window.getComputedStyle?window.getComputedStyle(el):null;
          if(s&&(s.display==='none'||s.visibility==='hidden'))return false;
          if(attr(el,'aria-hidden')==='true')return false;
          // An <option> in a closed <select> has no box but is still selectable, so a rect test
          // would hide exactly the elements a combobox exists to offer.
          if(el.tagName.toUpperCase()==='OPTION')return true;
          var r=el.getBoundingClientRect?el.getBoundingClientRect():null;
          return !r||(r.width>0||r.height>0);
        }

        function roleOf(el){
          var explicit=clean(attr(el,'role'));
          if(explicit)return explicit.split(' ')[0];
          var t=el.tagName.toUpperCase();
          if(HEADING[t])return 'heading';
          if(t==='A')return el.hasAttribute('href')?'link':null;
          if(t==='BUTTON'||t==='SUMMARY')return 'button';
          if(t==='SELECT')return 'combobox';
          if(t==='TEXTAREA')return 'textbox';
          if(t==='OPTION')return 'option';
          if(t==='IMG')return 'image';
          if(t==='INPUT'){
            var ty=(attr(el,'type')||'text').toLowerCase();
            if(ty==='hidden')return null;
            if(ty==='checkbox')return 'checkbox';
            if(ty==='radio')return 'radio';
            if(ty==='submit'||ty==='button'||ty==='reset'||ty==='image')return 'button';
            if(ty==='search')return 'searchbox';
            return 'textbox';
          }
          if(el.isContentEditable)return 'textbox';
          // A control the page built out of a div has no role and is still a control. Missing it
          // is worse than over-reporting it: the agent simply cannot see the thing it must press.
          var ti=attr(el,'tabindex');
          if(el.hasAttribute('onclick')||(ti!==null&&ti!=='-1'))return 'button';
          return null;
        }

        function labelText(el){
          try{
            if(el.id&&window.CSS&&CSS.escape){
              var l=document.querySelector('label[for="'+CSS.escape(el.id)+'"]');
              if(l)return l.textContent;
            }
            var p=el.closest?el.closest('label'):null;
            if(p)return p.textContent;
          }catch(e){}
          return '';
        }

        function nameOf(el,role){
          var n=attr(el,'aria-label');
          if(!clean(n)){
            var lb=attr(el,'aria-labelledby');
            if(lb){
              n=lb.split(/\s+/).map(function(i){
                var e=document.getElementById(i);return e?e.textContent:'';
              }).join(' ');
            }
          }
          if(!clean(n)&&LABELLED[role])n=labelText(el)||attr(el,'placeholder');
          if(!clean(n)&&role==='image')n=attr(el,'alt');
          if(!clean(n))n=attr(el,'title');
          if(!clean(n))n=el.textContent;
          return cut(n,NAMELEN);
        }

        function refFor(el){
          var r=W.byEl.get(el);
          if(!r){r='e'+(W.next++);W.byEl.set(el,r);W.byRef.set(r,el);}
          return r;
        }

        function push(el,role,name,depth){
          var node={ref:refFor(el),role:role,name:name,tag:el.tagName.toLowerCase(),depth:depth};
          var t=el.tagName.toUpperCase();
          if(t==='INPUT'||t==='TEXTAREA'||t==='SELECT'){
            node.value=String(el.value==null?'':el.value);
            if(el.disabled)node.disabled=true;
            if(role==='checkbox'||role==='radio')node.checked=!!el.checked;
          }
          if(t==='A'){var h=attr(el,'href');if(h)node.href=cut(h,HREFLEN);}
          out.push(node);
        }

        function ownText(el){
          var s='';
          for(var i=0;i<el.childNodes.length;i++){
            var c=el.childNodes[i];
            if(c.nodeType===3)s+=c.nodeValue;
          }
          return clean(s);
        }

        function walk(el,depth){
          if(out.length>=MAX){truncated=true;return;}
          if(SKIP[el.tagName.toUpperCase()])return;
          if(!visible(el))return;
          var role=roleOf(el),next=depth;
          if(role){
            push(el,role,nameOf(el,role),depth);
            if(LEAF[role])return;
            next=depth+1;
          }else{
            var own=ownText(el);
            if(own){push(el,'text',cut(own,NAMELEN),depth);next=depth+1;}
          }
          var kids=el.children;
          for(var i=0;i<kids.length;i++)walk(kids[i],next);
        }

        if(document.body)walk(document.body,0);
        return {url:location.href,title:document.title,nodes:out,truncated:truncated};
        })()
        """.trimIndent()
}
