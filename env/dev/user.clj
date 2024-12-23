(ns user
  "For REPL-based start/stop of the server.
   This file isn't used in cljs and is a problem for shadow-cljs without the
   :clj compiler directives."
  (:require
   [bling.core                        :as bling :refer [bling]]
   [clojure.pprint                    :refer [pprint]]
   [clojure.string                    :as str]
   [clojure.spec.alpha                :as s]
   [clojure.tools.namespace.repl      :refer [set-refresh-dirs]]
   [expound.alpha                     :as expound]
   [lambdaisland.classpath.watch-deps :as watch-deps]      ; hot loading for deps.
   [taoensso.telemere                 :as tel :refer [log!]]
   [taoensso.telemere.timbre          :as tel-tim]
   [taoensso.telemere.tools-logging   :as tel-log]
   [taoensso.timbre                   :as timbre]))

;;; If you get stuck do: (clojure.tools.namespace.repl/refresh)

;; uncomment to enable hot loading for deps
(watch-deps/start! {:aliases [:dev :test]})

(alter-var-root #'s/*explain-out* (constantly expound/printer))
(add-tap (bound-fn* clojure.pprint/pprint))
(set-refresh-dirs "src/owl-db-tools")  ; Put here as many as you need. test messes with ns-setup!
(s/check-asserts true) ; Error on s/assert, run s/valid? rather than just returning the argument.
(tel/call-on-shutdown! tel/stop-handlers!)

(defn custom-console-output-fn
  "I don't want to see hostname and time, etc. in console logging."
  ([] :can-be-a-no-op) ; for shutdown, at least.
  ([signal]
   (when-not (= (:kind signal) :agents)
     (let [{:keys [kind level location msg_]} signal
           file (:file location)
           file (when (string? file)
                  (let [[_ odb-file] (re-matches  #"^.*(owl-db-tools.*)$" file)]
                    (or odb-file file)))
           line (:line location)
           msg (if-let [s (not-empty (force msg_))] s "\"\"")
           heading (-> (str "\n" (name kind) "/" (name level) " ") str/upper-case)]
       (cond (= :error level)      (bling [:bold.red.white-bg heading] " " [:red    (str file ":" line " - " msg)])
             (= :warn  level)      (bling [:bold.blue heading]         " " [:yellow (str file ":" line " - " msg)])
             :else                 (bling [:bold.blue heading]         " "  (str file ":" line " - " msg)))))))

(def diag (atom nil))

(defn timbre->telemere-appender
  "Returns a simple Timbre appender that'll redirect logs to Telemere. Used to rein in Datahike."
  [& args]
  (reset! diag args)
  {:enabled?  true
   :min-level nil
   :fn (fn [data]
         (let [{:keys [level context ?err output_
                       ?ns-str ?file ?line ?column]} data]
           (tel/signal!
            {:kind :log
             :via-timbre? true ; User-level kv to help identify these signals

             :level level
             :inst  (java.util.Date.)
             :ctx+  context

             :ns     ?ns-str
             :file   ?file
             :line   ?line
             :column ?column

             :error  ?err
             :msg    (str "FROM DH: "(force output_))})))})

(defn config-log!
  "Configure Telemere: set reporting levels and specify a custom :output-fn."
  []
  (tel/add-handler! :default/console (tel/handler:console {:output-fn custom-console-output-fn}))
  (tel-log/tools-logging->telemere!)  ;; Send tools.logging through telemere. Check this with (tel/check-interop)
  (tel/streams->telemere!)            ;; likewise for *out* and *err* but "Note that Clojure's *out*, *err* are not necessarily automatically affected."
  (tel/event! ::config-log {:level :info :msg (str "Logging configured:\n" (with-out-str (pprint (tel/check-interop))))})
  ;; The following is needed because of datahike; no timbre-logging->telemere!
  (timbre/set-config! (-> timbre/*config*
                          (assoc :min-level [[#{"datahike.*"} :error]
                                             [#{"konserve.*"} :error]])
                          (assoc-in [:appenders :println :fn] timbre->telemere-appender))))


(defn ^:diag unconfig-log!
  "Set :default/console back to its default handler. Typically done at REPL."
  []
  (tel/remove-handler! :default/console)
  (tel/add-handler!    :default/console (tel/handler:console)))

(defn ^:diag start []
  (config-log!)
  (log! :info "Log configured."))

(defn ^:diag stop []
  (unconfig-log!))
