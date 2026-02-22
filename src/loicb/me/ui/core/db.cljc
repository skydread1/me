(ns loicb.me.ui.core.db
  "Application db — pure db->db updater functions.

   State lives under :app/me in the app-db atom.
   All posts are embedded at compile time via the posts-data macro.
   No server communication needed."
  #?(:cljs (:require-macros [loicb.me.build.md :as md]
                            [loicb.me.config :as config])))

(def posts
  "Blog posts loaded at compile time (CLJS) or empty (CLJ for testing)."
  #?(:cljs (md/posts-data) :clj []))

(def site
  "Site metadata loaded at compile time (CLJS) or defaults (CLJ for testing)."
  #?(:cljs (config/site-config)
     :clj  {:author   "Test"
            :subtitle ""
            :bio      ""
            :github   ""
            :linkedin ""
            :footer   ""}))

(def app-version
  #?(:cljs (config/app-version) :clj "dev"))

(def initial-db
  {:view          :home
   :posts         posts
   :site          site
   :version       app-version
   :selected-slug nil
   :tag-filter    nil})

(defn all-tags
  "Collect all unique tags from posts, sorted alphabetically."
  [db]
  (->> (:posts db)
       (mapcat :tags)
       distinct
       sort
       vec))

^:rct/test
(comment
  (all-tags {:posts [{:tags ["b" "a"]}
                     {:tags ["a" "c"]}]})
  ;=> ["a" "b" "c"]

  (all-tags {:posts []})
  ;=> []
  )

(defn filtered-posts
  "Filter posts by tag-filter (if set), sorted newest first."
  [db]
  (let [tag (:tag-filter db)]
    (cond->> (:posts db)
      tag (filter #(some #{tag} (:tags %)))
      true (sort-by :date #(compare %2 %1)))))

^:rct/test
(comment
  (filtered-posts {:posts [{:date "2026-02-20" :tags ["a"]}
                           {:date "2026-02-18" :tags ["b"]}
                           {:date "2026-02-19" :tags ["a"]}]
                   :tag-filter "a"})
  ;=>> [{:date "2026-02-20"} {:date "2026-02-19"}]

  (count (filtered-posts {:posts [{:tags ["a"]} {:tags ["b"]}]
                          :tag-filter nil}))
  ;=> 2
  )

(defn selected-post
  "Find the currently selected post by slug."
  [db]
  (when-let [slug (:selected-slug db)]
    (some #(when (= (:slug %) slug) %) (:posts db))))

^:rct/test
(comment
  (selected-post {:selected-slug "hello"
                  :posts [{:slug "hello" :title "Hello"}
                          {:slug "world" :title "World"}]})
  ;=>> {:slug "hello" :title "Hello"}

  (selected-post {:selected-slug nil :posts []})
  ;=> nil
  )

(defn select-post
  "Navigate to post detail view."
  [db slug]
  (assoc db :view :detail :selected-slug slug))

^:rct/test
(comment
  (select-post initial-db "my-post")
  ;=>> {:view :detail :selected-slug "my-post"}
  )

(defn go-home
  "Navigate to home view, clearing selection and tag filter."
  [db]
  (assoc db :view :home :selected-slug nil :tag-filter nil))

^:rct/test
(comment
  (go-home {:view :detail :selected-slug "x" :tag-filter "clojure"})
  ;=>> {:view :home :selected-slug nil :tag-filter nil}
  )

(defn filter-by-tag
  "Set tag filter on home view."
  [db tag]
  (assoc db :tag-filter tag :view :home :selected-slug nil))

^:rct/test
(comment
  (filter-by-tag initial-db "clojure")
  ;=>> {:tag-filter "clojure" :view :home}
  )

(defn clear-filter
  "Clear the tag filter."
  [db]
  (assoc db :tag-filter nil))

^:rct/test
(comment
  (:tag-filter (clear-filter {:tag-filter "web"}))
  ;=> nil
  )
