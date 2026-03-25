(ns loicb.me.util
  "Cross-cutting text utilities."
  (:require [clojure.string :as str]))

(defn slugify
  "Convert text to a URL-friendly slug.
   Lowercase, replace non-alphanumeric runs with hyphens, trim hyphens."
  [text]
  (-> text
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-|-$" "")))

^:rct/test
(comment
  (slugify "Hello World")
  ;=> "hello-world"

  (slugify "Using marked.js for rendering")
  ;=> "using-marked-js-for-rendering"

  (slugify "  Extra   spaces  ")
  ;=> "extra-spaces"

  (slugify "Getting Started with Replicant.md")
  ;=> "getting-started-with-replicant-md"
  )

(defn strip-inline-md
  "Remove inline markdown formatting from heading text.
   Strips: backtick code, bold, italic, links, images."
  [text]
  (-> text
      (str/replace #"!\[([^\]]*)\]\([^)]*\)" "$1")
      (str/replace #"\[([^\]]*)\]\([^)]*\)" "$1")
      (str/replace #"`([^`]+)`" "$1")
      (str/replace #"\*\*([^*]+)\*\*" "$1")
      (str/replace #"\*([^*]+)\*" "$1")
      str/trim))

^:rct/test
(comment
  (strip-inline-md "Using `marked.js` for **rendering**")
  ;=> "Using marked.js for rendering"

  (strip-inline-md "[link text](http://example.com) here")
  ;=> "link text here"

  (strip-inline-md "Plain text")
  ;=> "Plain text"
  )
