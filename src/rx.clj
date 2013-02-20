(ns rx
  (:refer-clojure :exclude [map filter]))

(defn oseq [xs]
  (fn [onNext onError onCompleted]
      (try
          (doseq [x (seq xs)] (onNext x))
          (onCompleted)
      (catch Exception e
        (onError e)))
      (fn [])))

(defn dooseq
  ([xs onNext] (xs onNext (fn [ex]) (fn [])))
  ([xs onNext onError] (xs onNext onError (fn [])))
  ([xs onNext onError onCompleted] (xs onNext onError onCompleted)))

(defn materialize [o]
  (fn [onNext onError onCompleted]
    (o
      (fn [x] (onNext {:kind "onNext" :value x}))
      (fn [e]
        (onNext {:kind "onError" :value e})
        (onCompleted))
      (fn []
        (onNext {:kind "onCompleted"})
        (onCompleted)))))

(defn there-can-only-be-one [& fns]
  (let
    [called (ref false)]
    (vec
      (clojure.core/map
        (fn [func]
          (fn [& body]
            (dosync
              (when (not @called)
                (ref-set called true)
                (apply func body))
            )))
      fns))))

(defn filter [f oseq]
  (fn [onNext onError onCompleted]
    (oseq
      (fn [x] (when (f x) (onNext x)))
      onError
      onCompleted)))

(defn map [f & seqs]
  (fn [onNext onError onCompleted]
    (let
      [
        worker (agent nil)
        subsRef (ref nil)
        unsubscribe
          (fn []
            (dosync
              (let [subs @subsRef]
                (when (not (nil? subs))
                  (send worker (fn [_] (doseq [sub subs] (sub))))))))
        [onError onCompleted]
          (there-can-only-be-one
            (fn[error] (send worker (fn [_] (unsubscribe) (onError error))))
            (fn[] (send worker (fn[_] (unsubscribe) (onCompleted)))))
        onNext (fn [item] (send worker (fn [_] (onNext item))))
        queues (vec (clojure.core/map (fn [_] (ref clojure.lang.PersistentQueue/EMPTY)) seqs))
      ]
      (dosync
        (ref-set
          subsRef
          (vec
            (clojure.core/map
              (fn [o index]
                (dooseq (materialize o)
                  (fn [msg]
                    (dosync
                      (if (= (:kind msg) "onError")
                        (onError (:value msg))
                        (do
                          (alter (queues index) conj msg)
                          (when (every? (comp not empty? deref) queues)
                            (let [messages (doall (clojure.core/map (comp peek deref) queues))]
                              (doseq [queue queues] (alter queue pop))
                              (if (every? (fn [{:keys [kind]}] (= kind "onNext")) messages)
                                (onNext (apply f (clojure.core/map :value messages)))
                                (when (some (fn [{:keys [kind]}] (= kind "onCompleted")) messages)
                                  (onCompleted)))))))))))
              seqs (range))))
          unsubscribe))))

(dooseq (map (fn[x y] (print (+ x y))) (oseq [1 2 3]) (oseq [4 5 6])) (fn [x] (print x)))
