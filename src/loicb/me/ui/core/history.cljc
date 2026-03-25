(ns loicb.me.ui.core.history
  "Browser history integration for SPA navigation.

   Routes:
   /                    -> home (all posts)
   /blog/:slug          -> post detail
   /tags/:name+:name    -> home filtered by tags (AND)"
  (:require [clojure.string :as str]))

(defn encode-uri [s]
  #?(:clj  (-> (java.net.URLEncoder/encode (str s) "UTF-8") (.replace "+" "%20"))
     :cljs (js/encodeURIComponent s)))

(defn decode-uri [s]
  #?(:clj  (java.net.URLDecoder/decode (str s) "UTF-8")
     :cljs (js/decodeURIComponent s)))

(defn state->path
  "Convert app state to URL path."
  [{:keys [view selected-slug tag-filters]}]
  (case view
    :home   (if (seq tag-filters)
              (str "/tags/" (->> tag-filters sort (map encode-uri) (str/join "+")))
              "/")
    :detail (str "/blog/" (encode-uri selected-slug))
    "/"))

^:rct/test
(comment
  (state->path {:view :home :tag-filters #{}})
  ;=> "/"

  (state->path {:view :home :tag-filters #{"clojure"}})
  ;=> "/tags/clojure"

  (state->path {:view :home :tag-filters #{"web" "clojure"}})
  ;=> "/tags/clojure+web"

  (state->path {:view :detail :selected-slug "my-post"})
  ;=> "/blog/my-post"

  (state->path {:view :unknown})
  ;=> "/"
  )

(defn path->state
  "Parse URL path to {:view ... :slug ... :tags ...}.
   Supports /tags/a+b (multi-tag) and /tag/a (legacy single-tag)."
  [path]
  (let [path (or path "/")]
    (cond
      (or (= path "/") (= path ""))
      {:view :home}

      :else
      (or
       (when-let [[_ tags-str] (re-matches #"/tags/(.+)" path)]
         {:view :home
          :tags (->> (str/split tags-str #"\+")
                     (map decode-uri)
                     set)})

       (when-let [[_ tag] (re-matches #"/tag/(.+)" path)]
         {:view :home :tags #{(decode-uri tag)}})

       (when-let [[_ slug] (re-matches #"/blog/(.+)" path)]
         {:view :detail :slug (decode-uri slug)})

       {:view :home}))))

^:rct/test
(comment
  (path->state "/")
  ;=> {:view :home}

  (path->state "/tags/clojure")
  ;=> {:view :home :tags #{"clojure"}}

  (path->state "/tags/clojure+web")
  ;=> {:view :home :tags #{"clojure" "web"}}

  (path->state "/tag/clojure")
  ;=> {:view :home :tags #{"clojure"}}

  (path->state "/blog/my-post")
  ;=> {:view :detail :slug "my-post"}

  (path->state "/unknown/path")
  ;=> {:view :home}

  ;; Round-trip
  (:tags (path->state (state->path {:view :home :tag-filters #{"clojure" "web"}})))
  ;=> #{"clojure" "web"}

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
