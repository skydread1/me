(ns loicb.me.build.md
  "Markdown file loader for blog posts.

   Reads from content/blog/, parses YAML frontmatter via clj-yaml,
   extracts TLDR sections, and derives slug and title from filename.

   Raw markdown is stored (not HTML). Rendering happens at runtime
   in the browser via marked + highlight.js.

   Provides a macro `posts-data` for embedding posts in CLJS at compile time."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-yaml.core :as yaml]
            [malli.core :as m]
            [malli.error :as me]))

(def content-dir "./content/blog/")

(def post-schema
  [:map
   [:slug :string]
   [:title :string]
   [:date :string]
   [:md-content :string]
   [:md-content-short :string]
   [:tags {:optional true} [:vector :string]]
   [:rss-feeds {:optional true} [:vector :string]]
   [:repos {:optional true} [:vector [:vector :string]]]])

(defn filepath->slug
  "Derive URL slug from filepath.
   `Getting Started with Replicant.md` -> `getting-started-with-replicant`"
  [filepath]
  (-> (io/file filepath)
      .getName
      (str/replace #"\.md$" "")
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-|-$" "")))

^:rct/test
(comment
  (filepath->slug "content/blog/Getting Started with Replicant.md")
  ;=> "getting-started-with-replicant"

  (filepath->slug "The Dispatch-of Pattern.md")
  ;=> "the-dispatch-of-pattern"
  )

(defn filepath->title
  "Derive post title from filepath (filename without .md extension)."
  [filepath]
  (-> (io/file filepath)
      .getName
      (str/replace #"\.md$" "")))

^:rct/test
(comment
  (filepath->title "content/blog/Getting Started with Replicant.md")
  ;=> "Getting Started with Replicant"
  )

(defn extract-tldr
  "Extract TLDR section from markdown content.
   Returns {:tldr \"...\" :full-content \"...\"}."
  [markdown-content]
  (let [pattern #"(?i)##\s+(TLDR|Summary)\s*\n([\s\S]*?)(?=\n##|\z)"]
    (if-let [matches (re-find pattern markdown-content)]
      {:tldr         (str/trim (nth matches 2))
       :full-content (str/trim (str/replace markdown-content pattern ""))}
      {:tldr         ""
       :full-content (str/trim markdown-content)})))

^:rct/test
(comment
  (extract-tldr "## TLDR\n\nShort summary.\n\n## Intro\n\nFull content here.")
  ;=> {:tldr "Short summary." :full-content "## Intro\n\nFull content here."}

  (extract-tldr "## Introduction\n\nNo TLDR here.")
  ;=> {:tldr "" :full-content "## Introduction\n\nNo TLDR here."}
  )

(defn parse-frontmatter
  "Split YAML frontmatter from markdown body."
  [raw-text]
  (if-let [[_ yaml-str body] (re-matches #"(?s)---\n(.*?)\n---\n(.*)" raw-text)]
    {:metadata (yaml/parse-string yaml-str)
     :body     (str/trim body)}
    {:metadata {}
     :body     (str/trim raw-text)}))

^:rct/test
(comment
  (:metadata (parse-frontmatter "---\ntags:\n  - clojure\n---\n# Hello"))
  ;=>> {:tags ["clojure"]}

  (:body (parse-frontmatter "---\ntags:\n  - clojure\n---\n# Hello"))
  ;=> "# Hello"

  (parse-frontmatter "No frontmatter here.")
  ;=> {:metadata {} :body "No frontmatter here."}
  )

(defn inst->date-string
  "Convert an inst to YYYY-MM-DD string."
  [inst-obj]
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") inst-obj))

(defn ->vector
  "Ensure value is a vector."
  [coll]
  (if (vector? coll) coll (vec coll)))

(defn normalize-metadata
  "Normalize YAML metadata to proper types."
  [metadata]
  (reduce-kv
   (fn [acc k v]
     (let [nk (keyword (name k))]
       (case nk
         :date      (assoc acc nk (if (inst? v) (inst->date-string v) (str v)))
         :tags      (assoc acc nk (->vector v))
         :rss-feeds (assoc acc nk (->vector v))
         :repos     (assoc acc nk (mapv ->vector v))
         (assoc acc nk v))))
   {}
   metadata))

^:rct/test
(comment
  (:tags (normalize-metadata {:tags ["clojure" "web"]}))
  ;=> ["clojure" "web"]

  (:date (normalize-metadata {:date "2026-02-20"}))
  ;=> "2026-02-20"

  (:date (normalize-metadata {:date #inst "2026-02-20T00:00:00.000Z"}))
  ;=> "2026-02-20"

  (:tags (normalize-metadata {:tags '("a" "b")}))
  ;=> ["a" "b"]
  )

(defn validate-post
  "Validate a post map against schema. Throws with humanized errors on failure."
  [post file-path]
  (when-let [explanation (m/explain post-schema post)]
    (throw (ex-info (str "Invalid post: " file-path "\n"
                         (pr-str (me/humanize explanation)))
                    {:file   file-path
                     :errors (me/humanize explanation)})))
  post)

(defn load-post
  "Load a markdown file into a post map.
   Derives slug and title from filename. Stores raw markdown (no HTML).
   Validates against schema and throws with clear errors on failure."
  [file-path]
  (let [raw                          (slurp file-path)
        {:keys [metadata body]}      (parse-frontmatter raw)
        {:keys [tldr full-content]}  (extract-tldr body)
        post (-> metadata
                 normalize-metadata
                 (assoc :slug             (filepath->slug file-path)
                        :title            (filepath->title file-path)
                        :md-content       full-content
                        :md-content-short tldr))]
    (validate-post post file-path)))

(defn list-blog-files
  "List all .md files in content-dir."
  []
  (when-let [dir (io/file content-dir)]
    (when (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(str/ends-with? (.getName %) ".md"))
           (map str)
           sort))))

(def load-posts
  "All blog posts loaded at macro-expansion time."
  (mapv load-post (list-blog-files)))

(defmacro posts-data
  "Embed blog post data in the CLJS bundle at compile time."
  []
  `~load-posts)
