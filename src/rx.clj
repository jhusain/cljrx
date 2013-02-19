(ns rx)

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

(defmacro dbgoseq [seq] `(dooseq ~seq print))

(defn materialize [o]
  (fn [onNext onError onCompleted]
    (o
      (fn [x] (onNext {:kind "onNext" :value x}))
      (fn [e]
        (do
          (onNext {:kind "onError" :value e})
          (onCompleted)))
      (fn []
        (do
          (onNext {:kind "onCompleted"})
          (onCompleted))))))

(defn there-can-only-be-one [& fns]
  (let
    [called (ref false)]
    (vec
      (map
        (fn [func]
          (fn [& body]
            (dosync
              (when (not @called)
                (alter called true)
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
        queues (vec (map (fn [_] (ref (clojure.lang.PersistentQueue/EMPTY))) seqs))
      ]
      (comment (dosync
        (ref-set
          subsRef
          (vec
            (map
              (fn [o index]
                (dooseq (materialize o)
                  (fn [msg]
                    (dosync
                      (if (= (get msg :kind) "onError")
                        (onError (get msg :value))
                        (do
                          (alter (queues index) conj msg)
                          (if (every? (comp not empty? deref) queues)
                             (let [messages (map (comp pop deref) queues)]
                               (if (every? (fn [{:keys [kind]}] (= kind "onNext")) messages)
                                 (onNext (apply f messages))
                                 (when (some (fn [{:keys [kind]}] (= kind "onCompleted")) messages)
                                   (onCompleted)))))))))))
              seqs (range))))
          unsubscribe)))))


(dbgoseq (map (oseq [1 2 3]) #(+ 1 %)))
