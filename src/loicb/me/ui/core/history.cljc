(ns loicb.me.ui.core.history
  "Browser history integration for SPA navigation.

   Routes:
   /              -> home (all posts)
   /blog/:slug    -> post detail
   /tag/:name     -> home filtered by tag")

(defn encode-uri [s]
  #?(:clj  (-> (java.net.URLEncoder/encode (str s) "UTF-8") (.replace "+" "%20"))
     :cljs (js/encodeURIComponent s)))

(defn decode-uri [s]
  #?(:clj  (java.net.URLDecoder/decode (str s) "UTF-8")
     :cljs (js/decodeURIComponent s)))

(defn state->path
  "Convert app state to URL path."
  [{:keys [view selected-slug tag-filter]}]
  (case view
    :home   (if tag-filter
              (str "/tag/" (encode-uri tag-filter))
              "/")
    :detail (str "/blog/" (encode-uri selected-slug))
    "/"))

^:rct/test
(comment
  (state->path {:view :home :tag-filter nil})
  ;=> "/"

  (state->path {:view :home :tag-filter "clojure"})
  ;=> "/tag/clojure"

  (state->path {:view :detail :selected-slug "my-post"})
  ;=> "/blog/my-post"

  (state->path {:view :unknown})
  ;=> "/"
  )

(defn path->state
  "Parse URL path to {:view ... :slug ... :tag ...}.
   Returns nil for unknown paths."
  [path]
  (let [path (or path "/")]
    (cond
      (or (= path "/") (= path ""))
      {:view :home}

      :else
      (or
       (when-let [[_ tag] (re-matches #"/tag/(.+)" path)]
         {:view :home :tag (decode-uri tag)})

       (when-let [[_ slug] (re-matches #"/blog/(.+)" path)]
         {:view :detail :slug (decode-uri slug)})

       {:view :home}))))

^:rct/test
(comment
  (path->state "/")
  ;=> {:view :home}

  (path->state "/tag/clojure")
  ;=> {:view :home :tag "clojure"}

  (path->state "/blog/my-post")
  ;=> {:view :detail :slug "my-post"}

  (path->state "/unknown/path")
  ;=> {:view :home}

  ;; Round-trip: state->path->state
  (:tag (path->state (state->path {:view :home :tag-filter "clojure"})))
  ;=> "clojure"

  (:slug (path->state (state->path {:view :detail :selected-slug "hello"})))
  ;=> "hello"
  )

#?(:cljs
   (defn push-state!
     "Push a new history entry for the given app state."
     [state]
     (let [path (state->path state)]
       (when-not (= path (.-pathname js/location))
         (.pushState js/history nil "" path)))))

#?(:cljs
   (defn replace-state!
     "Replace current history entry (for initial load)."
     [state]
     (.replaceState js/history nil "" (state->path state))))

#?(:cljs
   (defn current-path
     "Get current URL pathname."
     []
     (.-pathname js/location)))

#?(:cljs
   (defn on-popstate!
     "Handle popstate event. Parses current URL and calls on-navigate."
     [on-navigate _e]
     (when-let [parsed (path->state (current-path))]
       (on-navigate parsed))))
