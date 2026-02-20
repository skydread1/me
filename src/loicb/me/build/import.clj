(ns loicb.me.build.import
  "Normalize imported blog posts in content/blog/.

   Transforms Obsidian-flavored markdown into renderable markdown:
   strips internal refs, converts wiki links to web links."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [loicb.me.config :as config]))

(def base-url (:base-url config/config))

(defn name->slug
  "Convert a note name to a URL slug."
  [note-name]
  (-> note-name
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-|-$" "")))

^:rct/test
(comment
  (name->slug "Getting Started with Replicant")
  ;=> "getting-started-with-replicant"

  (name->slug "The Dispatch-of Pattern")
  ;=> "the-dispatch-of-pattern"
  )

(defn wiki-links->web-links
  "Convert [[Note]] to [Note](base-url/blog/note-slug)
   and [[Note|Display]] to [Display](base-url/blog/note-slug)."
  [content]
  (-> content
      (str/replace #"\[\[([^\]|]+)\|([^\]]+)\]\]"
                   (fn [[_ note display]]
                     (str "[" display "](" base-url "/blog/" (name->slug note) ")")))
      (str/replace #"\[\[([^\]]+)\]\]"
                   (fn [[_ note]]
                     (str "[" note "](" base-url "/blog/" (name->slug note) ")")))))

^:rct/test
(comment
  (wiki-links->web-links "See [[Some Note]] for details.")
  ;=> "See [Some Note](https://www.loicb.dev/blog/some-note) for details."

  (wiki-links->web-links "See [[Some Note|the note]] for details.")
  ;=> "See [the note](https://www.loicb.dev/blog/some-note) for details."
  )

(defn strip-internal-refs
  "Remove the ## Internal refs section (vault-only, not for public)."
  [content]
  (str/replace content #"(?i)\n## Internal refs[\s\S]*?(?=\n## |\z)" ""))

^:rct/test
(comment
  (strip-internal-refs "## Intro\n\nHello.\n\n## Internal refs\n\n- [[Note A]]\n- [[Note B]]")
  ;=> "## Intro\n\nHello.\n"

  (strip-internal-refs "## Intro\n\nNo refs here.")
  ;=> "## Intro\n\nNo refs here."
  )

(defn media-paths->absolute
  "Rewrite relative media paths to absolute /assets/media/ paths."
  [content]
  (str/replace content #"\.\./media/" "/assets/media/"))

^:rct/test
(comment
  (media-paths->absolute "![img](../media/git/flow.png)")
  ;=> "![img](/assets/media/git/flow.png)"
  )

(defn normalize-content
  "Normalize Obsidian-flavored markdown to renderable markdown."
  [content]
  (-> content
      strip-internal-refs
      wiki-links->web-links
      media-paths->absolute
      str/trim))

^:rct/test
(comment
  (normalize-content "## Intro\n\nSee [[My Note]].\n\n## Internal refs\n\n- [[X]]")
  ;=> "## Intro\n\nSee [My Note](https://www.loicb.dev/blog/my-note)."

  (normalize-content "![pic](../media/foo/bar.png)")
  ;=> "![pic](/assets/media/foo/bar.png)"
  )

(defn normalize-dir!
  "Normalize all .md files in dir-path in-place."
  [dir-path]
  (doseq [f (sort (.listFiles (io/file dir-path)))
          :when (str/ends-with? (.getName f) ".md")]
    (spit f (normalize-content (slurp f)))))
