(ns loicb.me.build.rss
  "RSS feed generator.

   Reads posts from content/blog/, filters by rss-feeds membership,
   generates XML feeds to resources/public/blog/rss/."
  (:require [clojure.string :as str]
            [loicb.me.build.md :as md]
            [loicb.me.config :as config]
            [clj-rss.core :as rss]
            [tick.core :as t])
  (:import [org.commonmark.parser Parser]
           [org.commonmark.renderer.html HtmlRenderer]
           [org.commonmark.ext.gfm.tables TablesExtension]))

(def extensions [(TablesExtension/create)])
(def md-parser (-> (Parser/builder) (.extensions extensions) .build))
(def html-renderer (-> (HtmlRenderer/builder) (.extensions extensions) .build))

(defn md->html
  "Convert markdown string to HTML via commonmark-java."
  [markdown-str]
  (.render html-renderer (.parse md-parser markdown-str)))

(def feed-configs (:rss-feeds config/config))

(def base-url (:base-url config/config))

(defn link-relative->absolute
  "Transform image src from relative to absolute URLs in HTML."
  [html]
  (str/replace html #"src=\"/" (str "src=\"" base-url "/")))

(defn generate-feed
  "Generate a single RSS feed XML file for feed-name."
  [feed-name]
  (if-let [feed-config (get feed-configs feed-name)]
    (let [blog-url    (str base-url "/blog")
          filtered    (->> (md/list-blog-files)
                           (mapv md/load-post)
                           (filter #(some #{feed-name} (:rss-feeds %)))
                           (sort-by :date #(compare %2 %1)))
          channel     {:title         (:title feed-config)
                       :link          base-url
                       :feed-url      (str blog-url "/rss/" feed-name "-feed.xml")
                       :description   (:description feed-config)
                       :language      "en-us"
                       :lastBuildDate (t/now)}
          items       (for [{:keys [slug date md-content md-content-short title]} filtered]
                        {:title       title
                         :link        (str blog-url "/" slug)
                         :guid        (str blog-url "/" slug)
                         :pubDate     (-> (t/time "18:00")
                                          (t/on date)
                                          (t/in "Asia/Singapore")
                                          t/instant)
                         :description md-content-short
                         "content:encoded" (str "<![CDATA["
                                                (-> md-content md->html link-relative->absolute)
                                                "]]>")})
          output-path (str "resources/public/blog/rss/" feed-name "-feed.xml")]
      (->> (apply rss/channel-xml (conj items channel))
           (spit output-path))
      (println (str "Generated: " output-path " (" (count filtered) " posts)")))
    (throw (ex-info (str "Unknown feed: " feed-name)
                    {:feed-name feed-name
                     :available (keys feed-configs)}))))

(defn generate-all
  "Generate all configured RSS feeds."
  []
  (doseq [feed-name (keys feed-configs)]
    (generate-feed feed-name))
  (println "All RSS feeds generated."))
