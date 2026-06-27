(ns loicb.me.ui.core.markdown
  "Runtime markdown rendering.

   marked + highlight.js render prose and code; mermaid (lazily loaded, self-hosted)
   renders diagrams and follows the live light/dark theme. The single public entry
   point is render-markdown — everything else is an internal rendering detail."
  (:require [clojure.string :as str]
            [loicb.me.util :as util]
            #?(:cljs ["marked" :refer [Marked]])
            #?(:cljs ["highlight.js/lib/core" :as hljs])
            #?(:cljs ["highlight.js/lib/languages/clojure" :as hljs-clojure])
            #?(:cljs ["highlight.js/lib/languages/javascript" :as hljs-js])
            #?(:cljs ["highlight.js/lib/languages/bash" :as hljs-bash])
            #?(:cljs ["highlight.js/lib/languages/json" :as hljs-json])
            #?(:cljs ["highlight.js/lib/languages/xml" :as hljs-xml])
            #?(:cljs ["highlight.js/lib/languages/yaml" :as hljs-yaml])
            #?(:cljs ["highlight.js/lib/languages/css" :as hljs-css])
            #?(:cljs ["highlight.js/lib/languages/markdown" :as hljs-md])))

;;=============================================================================
;; highlight.js languages
;;=============================================================================

#?(:cljs
   (do
     (hljs/registerLanguage "clojure" hljs-clojure)
     (hljs/registerLanguage "javascript" hljs-js)
     (hljs/registerLanguage "bash" hljs-bash)
     (hljs/registerLanguage "json" hljs-json)
     (hljs/registerLanguage "xml" hljs-xml)
     (hljs/registerLanguage "html" hljs-xml)
     (hljs/registerLanguage "yaml" hljs-yaml)
     (hljs/registerLanguage "css" hljs-css)
     (hljs/registerLanguage "markdown" hljs-md)))

(defn- escape-html
  "Escape the characters that have meaning in HTML so the original text is
   preserved as the element's textContent (what mermaid reads)."
  [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

^:rct/test
(comment
  (escape-html "a < b > c") ;=> "a &lt; b &gt; c"
  (escape-html "x & y")     ;=> "x &amp; y"
  ;; & escaped first, so < and > don't double-escape
  (escape-html "<br/> & co") ;=> "&lt;br/&gt; &amp; co"
  )

;;=============================================================================
;; mermaid (CLJS only)
;;=============================================================================

;; mermaid is a large, pure-ESM library that internally code-splits via dynamic
;; import(); shadow-cljs cannot bundle it through its CJS interop ("Cannot redefine
;; property: default"). We instead load the self-hosted, self-contained UMD build
;; (resources/public/vendor/, copied into dist/ by `bb dist`) lazily via a <script>
;; tag — only when a page actually has a diagram. The bundle assigns window.mermaid.
#?(:cljs
   (def ^:private mermaid-url "/vendor/mermaid.min.js"))

#?(:cljs
   (defonce ^:private mermaid-promise (atom nil)))

#?(:cljs
   (defn- load-mermaid!
     "Inject the mermaid bundle once and resolve to window.mermaid. Memoized; the
      cache is cleared on failure (see run-mermaid!) so a later visit can retry."
     []
     (or @mermaid-promise
         (reset! mermaid-promise
                 (js/Promise.
                  (fn [resolve reject]
                    (if-let [m (.-mermaid js/window)]
                      (resolve m)
                      (let [s (.createElement js/document "script")]
                        (set! (.-src s) mermaid-url)
                        (set! (.-async s) true)
                        (set! (.-onload s) (fn [] (resolve (.-mermaid js/window))))
                        (set! (.-onerror s) (fn [e] (reject e)))
                        (.appendChild (.-head js/document) s)))))))))

#?(:cljs
   (defn- run-mermaid!
     "Render any not-yet-processed mermaid diagrams inside `node`.
      Picks the diagram theme from the current site theme so it matches
      light/dark mode. No-op when there are no mermaid blocks.

      On failure it never throws: it logs, clears the cache so the next
      navigation retries, and reveals the raw definition (via .mermaid-failed)
      instead of leaving a blank gap."
     [node]
     (when node
       (let [nodes (.querySelectorAll node "pre.mermaid:not([data-processed=\"true\"])")]
         (when (pos? (.-length nodes))
           ;; stash each original definition so a later theme toggle can re-render
           ;; it — mermaid replaces the element's text with SVG on render.
           (doseq [n (array-seq nodes)]
             (when-not (.getAttribute n "data-src")
               (.setAttribute n "data-src" (.-textContent n))))
           (let [dark? (= "dark" (some-> js/document .-body (.getAttribute "data-theme")))]
             (-> (load-mermaid!)
                 (.then (fn [^js mermaid]
                          (.initialize mermaid #js {:startOnLoad   false
                                                    :securityLevel "loose"
                                                    :theme         (if dark? "dark" "default")})
                          (.run mermaid #js {:nodes nodes})))
                 (.catch (fn [e]
                           (js/console.error "mermaid render failed:" e)
                           (reset! mermaid-promise nil)
                           (doseq [n (array-seq nodes)]
                             (.add (.-classList n) "mermaid-failed")))))))))))

#?(:cljs
   (defn- retheme-mermaid!
     "Re-render every drawn diagram with the current site theme. mermaid bakes the
      theme into the SVG at render time, so a live light/dark toggle needs a redraw:
      restore each stashed definition, mark it unprocessed, and run again."
     []
     (let [done (.querySelectorAll js/document "pre.mermaid[data-processed=\"true\"]")]
       (when (pos? (.-length done))
         (doseq [n (array-seq done)]
           (when-let [src (.getAttribute n "data-src")]
             (set! (.-textContent n) src)
             (.removeAttribute n "data-processed")))
         (run-mermaid! (.-body js/document))))))

#?(:cljs
   ;; Redraw diagrams whenever the site theme changes, by any path (toggle button,
   ;; OS preference change, …). One observer on body's data-theme covers them all.
   (defonce _theme-observer
     (let [obs (js/MutationObserver. (fn [_ _] (retheme-mermaid!)))]
       (.observe obs (.-body js/document)
                 #js {:attributes true :attributeFilter #js ["data-theme"]})
       obs)))

;;=============================================================================
;; marked instance + entry point
;;=============================================================================

#?(:cljs
   (def ^:private marked-instance
     (let [m (Marked.)]
       (.use m (clj->js
                {:renderer
                 {:code (fn [obj]
                          (let [code (.-text obj)
                                lang (.-lang obj)]
                            (if (= lang "mermaid")
                              ;; Hand the raw (escaped) definition to mermaid; do not highlight.
                              (str "<pre class=\"mermaid\">" (escape-html code) "</pre>")
                              (let [highlighted (if (and lang (hljs/getLanguage lang))
                                                  (.-value (hljs/highlight code #js {:language lang}))
                                                  (.-value (hljs/highlightAuto code)))]
                                (str "<pre><code class=\"hljs\">" highlighted "</code></pre>")))))
                  :heading (fn [obj]
                             (let [text  (.-text obj)
                                   depth (.-depth obj)
                                   plain (str/replace text #"<[^>]*>" "")
                                   id    (util/slugify plain)]
                               (if (#{2 3} depth)
                                 (str "<h" depth " id=\"" id "\">"
                                      "<a class=\"heading-anchor\" href=\"#" id "\">" text "</a>"
                                      "</h" depth ">")
                                 (str "<h" depth " id=\"" id "\">" text "</h" depth ">"))))}}))
       m)))

(defn render-markdown
  "Render markdown string to HTML hiccup.
   On CLJ: returns raw text in a pre block (for RCT testing).
   On CLJS: uses marked + highlight.js, with mermaid diagrams rendered after mount."
  [content]
  (when (seq content)
    #?(:clj  [:pre content]
       :cljs [:div {:innerHTML           (.parse marked-instance content)
                    :replicant/on-render (fn [{:replicant/keys [node]}]
                                           (run-mermaid! node))}])))
