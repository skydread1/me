(ns loicb.me.config
  "Reads config.edn at build/macro-expansion time.
   Provides config map for JVM code and a macro for CLJS embedding."
  (:require [clojure.edn :as edn])
  (:import [java.time Year]))

(def config
  (edn/read-string (slurp "config.edn")))

(defn- enrich-site
  "Add computed fields to site config."
  [site]
  (let [start (:career-start site)
        years (when start (- (.getValue (Year/now)) start))]
    (cond-> site
      years (assoc :years-experience years))))

(defmacro site-config
  "Embed site metadata in CLJS bundle at compile time."
  []
  `~(enrich-site (:site config)))

(def version
  (:version (edn/read-string (slurp "resources/version.edn"))))

(defmacro app-version
  "Embed app version string in CLJS bundle at compile time."
  []
  version)
