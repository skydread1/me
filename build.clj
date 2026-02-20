(ns build
  (:require [loicb.me.build.rss :as rss]))

(defn rss-feeds
  "Generate all configured RSS feeds."
  [_]
  (rss/generate-all))
