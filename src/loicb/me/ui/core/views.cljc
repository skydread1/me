(ns loicb.me.ui.core.views
  "UI components using Replicant defalias.

   Two views: home (post list with tag filter) and detail (full post).
   Markdown rendered at runtime via marked + highlight.js.
   Components receive {::db state ::dispatch! fn} as namespaced props."
  (:require [replicant.alias :refer [defalias]]
            [loicb.me.ui.core.db :as db]
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
;; Markdown rendering (CLJS only)
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

#?(:cljs
   (def marked-instance
     (let [m (Marked.)]
       (.use m (clj->js
                {:renderer
                 {:code (fn [obj]
                          (let [code (.-text obj)
                                lang (.-lang obj)
                                highlighted (if (and lang (hljs/getLanguage lang))
                                              (.-value (hljs/highlight code #js {:language lang}))
                                              (.-value (hljs/highlightAuto code)))]
                            (str "<pre><code class=\"hljs\">" highlighted "</code></pre>")))}}))
       m)))

(defn render-markdown
  "Render markdown string to HTML hiccup.
   On CLJ: returns raw text in a pre block (for RCT testing).
   On CLJS: uses marked + highlight.js."
  [content]
  (when (seq content)
    #?(:clj  [:pre content]
       :cljs [:div {:innerHTML (.parse marked-instance content)}])))

;;=============================================================================
;; Components
;;=============================================================================

(defalias site-header
  "Site header with name, links, and theme toggle."
  [{::keys [db dispatch!]}]
  (let [{:keys [author github linkedin]} (:site db)]
    [:header.site-header
     [:div.header-inner
      [:span.site-name
       {:on {:click #(dispatch! {:db db/go-home :history :push})}}
       [:img.site-logo {:src "/logo.png" :alt "Logo"}]
       author]
      [:div.header-right
       [:a.icon-link
        {:href github :target "_blank" :title "GitHub"}
        [:svg {:viewBox "0 0 24 24" :width "20" :height "20" :fill "currentColor"}
         [:path {:d "M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0024 12c0-6.63-5.37-12-12-12z"}]]]
       [:a.icon-link
        {:href linkedin :target "_blank" :title "LinkedIn"}
        [:svg {:viewBox "0 0 24 24" :width "20" :height "20" :fill "currentColor"}
         [:path {:d "M20.447 20.452h-3.554v-5.569c0-1.328-.027-3.037-1.852-3.037-1.853 0-2.136 1.445-2.136 2.939v5.667H9.351V9h3.414v1.561h.046c.477-.9 1.637-1.85 3.37-1.85 3.601 0 4.267 2.37 4.267 5.455v6.286zM5.337 7.433a2.062 2.062 0 01-2.063-2.065 2.064 2.064 0 112.063 2.065zm1.782 13.019H3.555V9h3.564v11.452zM22.225 0H1.771C.792 0 0 .774 0 1.729v20.542C0 23.227.792 24 1.771 24h20.451C23.2 24 24 23.227 24 22.271V1.729C24 .774 23.2 0 22.222 0h.003z"}]]]
       [:a.icon-link
        {:href "/blog/rss/all-feed.xml" :target "_blank" :title "RSS Feed"}
        [:svg {:viewBox "0 0 24 24" :width "20" :height "20" :fill "currentColor"}
         [:circle {:cx "6.18" :cy "17.82" :r "2.18"}]
         [:path {:d "M4 4.44v2.83c7.03 0 12.73 5.7 12.73 12.73h2.83c0-8.59-6.97-15.56-15.56-15.56zm0 5.66v2.83c3.9 0 7.07 3.17 7.07 7.07h2.83c0-5.47-4.43-9.9-9.9-9.9z"}]]]
       #?(:cljs
          [:button.theme-toggle
           {:title "Toggle theme"
            :on {:click (fn [_]
                          (let [body    (.-body js/document)
                                current (.getAttribute body "data-theme")
                                next-t  (if (= current "dark") "light" "dark")]
                            (.setAttribute body "data-theme" next-t)
                            (js/localStorage.setItem "theme" next-t)))}}
           [:span.show-light
            [:svg {:viewBox "0 0 24 24" :width "18" :height "18" :fill "none" :stroke "currentColor" :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round"}
             [:path {:d "M21 12.79A9 9 0 1111.21 3 7 7 0 0021 12.79z"}]]]
           [:span.show-dark
            [:svg {:viewBox "0 0 24 24" :width "18" :height "18" :fill "none" :stroke "currentColor" :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round"}
             [:circle {:cx "12" :cy "12" :r "5"}]
             [:line {:x1 "12" :y1 "1" :x2 "12" :y2 "3"}]
             [:line {:x1 "12" :y1 "21" :x2 "12" :y2 "23"}]
             [:line {:x1 "4.22" :y1 "4.22" :x2 "5.64" :y2 "5.64"}]
             [:line {:x1 "18.36" :y1 "18.36" :x2 "19.78" :y2 "19.78"}]
             [:line {:x1 "1" :y1 "12" :x2 "3" :y2 "12"}]
             [:line {:x1 "21" :y1 "12" :x2 "23" :y2 "12"}]
             [:line {:x1 "4.22" :y1 "19.78" :x2 "5.64" :y2 "18.36"}]
             [:line {:x1 "18.36" :y1 "5.64" :x2 "19.78" :y2 "4.22"}]]]])]]]))

(defn tag-bar
  "Tag filter bar. Shows all tags, highlights active filter."
  [{::keys [db dispatch!]}]
  (let [tags       (db/all-tags db)
        active-tag (:tag-filter db)]
    (when (seq tags)
      [:div.tag-bar
       [:button.tag-btn
        {:class (when-not active-tag "active")
         :on {:click #(dispatch! {:db db/clear-filter
                                  :history :push})}}
        "All"]
       (for [tag tags]
         [:button.tag-btn
          {:replicant/key tag
           :class         (when (= tag active-tag) "active")
           :on            {:click #(dispatch! {:db (fn [d] (db/filter-by-tag d tag))
                                               :history :push})}}
          tag])])))

(defn post-card
  "Single post item in the list."
  [{::keys [db dispatch!]} post]
  (let [{:keys [slug title date tags md-content-short]} post
        active-tag (:tag-filter db)]
    [:li.post-item {:replicant/key slug}
     [:div.post-title
      [:a {:on {:click #(dispatch! {:db      (fn [d] (db/select-post d slug))
                                    :history :push})}}
       title]]
     [:div.post-meta
      [:span date]
      (when (seq tags)
        [:span.post-tags
         (for [tag tags]
           [:span.post-tag {:replicant/key tag
                            :class         (when (= tag active-tag) "active")
                            :on {:click #(dispatch! {:db      (fn [d] (db/filter-by-tag d tag))
                                                     :history :push})}}
            tag])])]
     (when (seq md-content-short)
       [:div.post-tldr (render-markdown md-content-short)])]))

(defalias post-list-view
  "Home page: profile section + tag filter + post list."
  [{::keys [db dispatch!] :as props}]
  (let [{:keys [subtitle bio]} (:site db)]
    [:div
     [:div.profile-section
      [:p.subtitle subtitle]
      [:p.bio bio]]
     (tag-bar props)
     [:ul.post-list
      (for [post (db/filtered-posts db)]
        (post-card props post))]]))

(defalias post-detail-view
  "Post detail: back link + title + meta + rendered markdown content."
  [{::keys [db dispatch!]}]
  (when-let [post (db/selected-post db)]
    (let [{:keys [title date tags repos md-content slug]} post]
      [:article.post-detail
       [:a.back-link {:on {:click #(dispatch! {:db db/go-home
                                               :history :push})}}
        "<- Back"]
       [:h1 title]
       [:div.post-meta
        [:span date]
        (when (seq tags)
          [:span.post-tags
           (for [tag tags]
             [:span.post-tag {:replicant/key tag
                              :on {:click #(dispatch! {:db      (fn [d] (db/filter-by-tag d tag))
                                                       :history :push})}}
              tag])])]
       (when (seq repos)
         [:div.post-repos
          [:span "Repos: "]
          (for [[repo-name repo-url] repos]
            [:a {:href repo-url :target "_blank" :replicant/key repo-name}
             repo-name])])
       [:div.markdown-content {:replicant/key slug}
        (render-markdown md-content)]])))

(defn app-view
  "Root view. Switches between home and detail based on :view in db."
  [{::keys [db dispatch!] :as props}]
  [:div.app-container
   [::site-header props]
   [:main.main-content
    (case (:view db)
      :detail [::post-detail-view props]
      [::post-list-view props])]
   [:footer.site-footer
    [:span (:footer (:site db))]
    [:span.version (str "v" (:version db))]]])
