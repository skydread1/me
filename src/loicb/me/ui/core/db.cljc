(ns ^:dev/always loicb.me.ui.core.db
  "Application db — pure db->db updater functions.

   State lives under :app/me in the app-db atom.
   All posts are embedded at compile time via the posts-data macro;
   ^:dev/always forces shadow to recompile every build so new/edited
   markdown in content/blog/ is picked up without a cache bust."
  (:require [clojure.string :as str]
            [loicb.me.util :as util])
  #?(:cljs (:require-macros [loicb.me.build.md :as md]
                            [loicb.me.config :as config])))

(def posts
  "Blog posts loaded at compile time (CLJS) or empty (CLJ for testing)."
  #?(:cljs (md/posts-data) :clj []))

(def site
  "Site metadata loaded at compile time (CLJS) or defaults (CLJ for testing)."
  #?(:cljs (config/site-config)
     :clj  {:author           "Test"
            :title            ""
            :company          ""
            :company-url      ""
            :location         ""
            :bio              ""
            :years-experience 0
            :highlights       []
            :github           ""
            :linkedin         ""
            :footer           ""
            :project-tags     #{}}))

(def app-version
  #?(:cljs (config/app-version) :clj "dev"))

(def initial-db
  {:view          :home
   :posts         posts
   :site          site
   :version       app-version
   :selected-slug nil
   :tag-filters   #{}})

(defn project-tag?
  "Check if a tag is a project tag."
  [db tag]
  (contains? (get-in db [:site :project-tags]) tag))

^:rct/test
(comment
  (project-tag? {:site {:project-tags #{"hibou" "magic"}}} "hibou")
  ;=> true

  (project-tag? {:site {:project-tags #{"hibou" "magic"}}} "clojure")
  ;=> false

  (project-tag? {:site {:project-tags #{}}} "anything")
  ;=> false
  )

;;=============================================================================
;; Table of contents
;;=============================================================================

(defn extract-headings
  "Extract h2/h3 headings from markdown for table of contents.
   Returns [{:level 2 :text \"Section\" :id \"section\"} ...].
   Skips headings inside fenced code blocks."
  [md-content]
  (when (seq md-content)
    (let [no-code (str/replace md-content #"(?s)```.*?```" "")]
      (->> (str/split-lines no-code)
           (keep (fn [line]
                   (when-let [[_ hashes text] (re-matches #"(#{2,3})\s+(.*)" line)]
                     (let [clean (util/strip-inline-md text)]
                       {:level (count hashes)
                        :text  clean
                        :id    (util/slugify clean)}))))
           vec))))

^:rct/test
(comment
  (extract-headings "## Intro\nText\n### Details\nMore\n## Conclusion")
  ;=> [{:level 2 :text "Intro" :id "intro"}
  ;    {:level 3 :text "Details" :id "details"}
  ;    {:level 2 :text "Conclusion" :id "conclusion"}]

  (extract-headings "## Real heading\n```\n## Fake heading\n```\n## Another real")
  ;=> [{:level 2 :text "Real heading" :id "real-heading"}
  ;    {:level 2 :text "Another real" :id "another-real"}]

  (extract-headings nil)
  ;=> nil

  (extract-headings "")
  ;=> nil

  (extract-headings "No headings here")
  ;=> []
  )

;;=============================================================================
;; Tags and filtering
;;=============================================================================

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

(defn tags-by-type
  "Split all tags into {:project-tags [...] :topic-tags [...]}.
   Both sorted alphabetically."
  [db]
  (let [ptags (get-in db [:site :project-tags] #{})
        all   (all-tags db)]
    {:project-tags (filterv #(contains? ptags %) all)
     :topic-tags   (filterv #(not (contains? ptags %)) all)}))

^:rct/test
(comment
  (tags-by-type {:posts [{:tags ["clojure" "hibou" "magic"]}
                         {:tags ["web" "hibou"]}]
                 :site {:project-tags #{"hibou" "magic"}}})
  ;=> {:project-tags ["hibou" "magic"] :topic-tags ["clojure" "web"]}

  (tags-by-type {:posts [{:tags ["a"]}] :site {:project-tags #{}}})
  ;=> {:project-tags [] :topic-tags ["a"]}
  )

(defn filtered-posts
  "Filter posts matching ALL selected tags (AND logic), sorted newest first."
  [db]
  (let [tags (:tag-filters db)]
    (cond->> (:posts db)
      (seq tags) (filter (fn [p] (every? (set (:tags p)) tags)))
      true (sort-by :date #(compare %2 %1)))))

^:rct/test
(comment
  (filtered-posts {:posts [{:date "2026-02-20" :tags ["a" "b"]}
                           {:date "2026-02-18" :tags ["b"]}
                           {:date "2026-02-19" :tags ["a"]}]
                   :tag-filters #{"a"}})
  ;=>> [{:date "2026-02-20"} {:date "2026-02-19"}]

  (filtered-posts {:posts [{:date "2026-02-20" :tags ["a" "b"]}
                           {:date "2026-02-18" :tags ["b"]}
                           {:date "2026-02-19" :tags ["a"]}]
                   :tag-filters #{"a" "b"}})
  ;=>> [{:date "2026-02-20"}]

  (count (filtered-posts {:posts [{:tags ["a"]} {:tags ["b"]}]
                          :tag-filters #{}}))
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
  "Navigate to home view, clearing selection and tag filters."
  [db]
  (assoc db :view :home :selected-slug nil :tag-filters #{}))

^:rct/test
(comment
  (go-home {:view :detail :selected-slug "x" :tag-filters #{"clojure"}})
  ;=>> {:view :home :selected-slug nil :tag-filters #{}}
  )

(defn filter-by-tag
  "Navigate to home with a single fresh tag filter."
  [db tag]
  (assoc db :tag-filters #{tag} :view :home :selected-slug nil))

^:rct/test
(comment
  (filter-by-tag {:view :detail :selected-slug "x" :tag-filters #{"web"}} "clojure")
  ;=>> {:tag-filters #{"clojure"} :view :home :selected-slug nil}
  )

(defn toggle-tag
  "Toggle a tag in the filter set. AND logic with other active tags."
  [db tag]
  (let [current (:tag-filters db #{})
        next-filters (if (contains? current tag)
                       (disj current tag)
                       (conj current tag))]
    (assoc db :tag-filters next-filters :view :home :selected-slug nil)))

^:rct/test
(comment
  (:tag-filters (toggle-tag {:tag-filters #{}} "clojure"))
  ;=> #{"clojure"}

  (:tag-filters (toggle-tag {:tag-filters #{"clojure"}} "web"))
  ;=> #{"clojure" "web"}

  (:tag-filters (toggle-tag {:tag-filters #{"clojure" "web"}} "clojure"))
  ;=> #{"web"}
  )

(defn clear-filters
  "Clear tag filters for the given set of tags, keeping others."
  [db tags-to-clear]
  (update db :tag-filters #(reduce disj (or % #{}) tags-to-clear)))

^:rct/test
(comment
  (:tag-filters (clear-filters {:tag-filters #{"hibou" "clojure" "web"}}
                               #{"hibou"}))
  ;=> #{"clojure" "web"}

  (:tag-filters (clear-filters {:tag-filters #{"hibou" "clojure"}}
                               #{"clojure" "web"}))
  ;=> #{"hibou"}

  (:tag-filters (clear-filters {:tag-filters #{}} #{"a"}))
  ;=> #{}
  )
