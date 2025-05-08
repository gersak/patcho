(ns patcho.patch
  (:refer-clojure :exclude [apply])
  (:require
   [version-clj.core :as vrs]))

(defmulti _upgrade (fn [topic to] [topic to]))
(defmulti _downgrade (fn [topic to] [topic to]))

(defn apply
  ([topic current target]
   (let [current (or current "0")]
     (when (or
            (vrs/older? target current)
            (vrs/newer? target current))
       (let [patch-direction (if (vrs/newer? target current)
                               :upgrade
                               :downgrade)
             patch-sequence (filter
                             (fn [[_topic _]]
                               (= _topic topic))
                             (keys
                              (methods (case patch-direction
                                         :upgrade _upgrade
                                         :downgrade _downgrade))))
             sorted (sort-by
                     second
                     (case patch-direction
                       :upgrade vrs/older?
                       :downgrade vrs/newer?)
                     patch-sequence)
             valid-sequence (keep
                             (fn [[_ version :as data]]
                               (when (case patch-direction
                                       :upgrade (and
                                                 (vrs/older-or-equal? version target)
                                                 (vrs/newer? version current))
                                       :downgrade (and
                                                   (vrs/older? version current)
                                                   (vrs/newer-or-equal? version target)))
                                 data))
                             sorted)]
         (doseq [[topic version] valid-sequence]
           (case patch-direction
             :upgrade (_upgrade topic version)
             :downgrade (_downgrade topic version))))))))

(defmacro upgrade [topic to & body]
  `(defmethod _upgrade [~topic ~to]
     [~'_ ~'_]
     ~@body))

(defmacro downgrade [topic to & body]
  `(defmethod _downgrade [~topic ~to]
     [~'_ ~'_]
     ~@body))

(comment
  (vrs/newer? "0" "0.0.0")
  (vrs/older? "0" "0.0.0")

  (upgrade ::datasets "0.0.1"  (println "Patching 0.3.37"))
  (upgrade ::datasets "0.3.37" (println "Patching 0.3.37"))
  (upgrade ::datasets "0.3.38" (println "Patching 0.3.38"))
  (upgrade ::datasets "0.3.39" (println "Patching 0.3.39"))
  (upgrade ::datasets "0.3.40" (println "Patching 0.3.40"))
  (upgrade ::datasets "0.3.41" (println "Patching 0.3.41"))
  (upgrade ::datasets "0.3.42" (println "Patching 0.3.42"))

  (vrs/newer-or-equal? "0.0.1" "0")
  (apply ::datasets nil "0.0.1")
  (macroexpand-1
   `(upgrade ::datasets "0.3.37" (println "Patching 0.3.37")))
  (group-by first (keys (methods _upgrade))))
