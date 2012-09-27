(ns typed.test.conduit
  (:import (clojure.lang Seqable IMeta IPersistentMap LazySeq ISeq))
  (:require [typed.core :refer [check-ns ann fn> def-alias tc-ignore ann-form declare-names inst
                                tc-pr-env inst-ctor]]
            [clojure.repl :refer [pst]]
            [arrows.core :refer [defarrow]]))

(def-alias Part (IPersistentMap Any Any))

(def-alias Result
  (All [x]
    (U nil ;stream is closed
       '[] ;abort/skip
       '[x];consume/continue
       )))

(def-alias Cont
  (All [x]
    [(U nil [(Result x) -> (Result x)]) -> (Result x)]))

(declare-names ==>)

(def-alias ==>
  (All [in out]
       [in -> '[(U nil (==> in out))
                (Cont out)]]))

(def-alias MaybePartMeta (IMeta (U nil (HMap {} :optional {:parts (U nil Part)}))))

(ann merge-parts [(Seqable MaybePartMeta) -> Part])
(tc-ignore
(defn merge-parts [ps]
  (let [parts (map (-> #(-> % meta :parts)
                     (ann-form [MaybePartMeta -> (U nil Part)]))
                   ps)]
    (apply (-> merge-with 
             (inst Any Part))
           (-> merge 
             (inst Any Any))
           parts)))
  )

(ann abort-c (All [x] (Cont x)))
(defn abort-c [c]
  (when c
    (c [])))

(ann conduit-seq-fn 
     (All [x]
       [(Seqable x) -> (==> Any x)]))
(defn conduit-seq-fn [l]
  (fn curr-fn [_]
    (let [new-f (conduit-seq-fn (rest l))]
      (if (empty? l)
        [nil (-> abort-c 
               (inst x))]
        [new-f
         (-> 
           (fn [c]
             (when c ;`when` added to conform to Cont type - Ambrose
               (c [(first l)])))
           (ann-form (Cont x)))]))))

(ann conduit-seq
     (All [x]
       [(Seqable x) -> (==> Any x)]))
(defn conduit-seq 
  "create a stream processor that emits the contents of a list
  regardless of what is fed to it"
  [l]
  ((inst conduit-seq-fn x) l))

(ann a-run
     (All [x]
       [(==> Any x) -> (Seqable x)]))
(defn a-run 
  "execute a stream processor function"
  [f]
  (let [[new-f c] (f nil)
        y (c (inst identity (Result x)))]
    (cond
      (nil? new-f) (list)
      (empty? y) (recur new-f)
      :else (lazy-seq
              (cons (first y)
                    (a-run new-f))))))

(ann comp-fn2
     (All [x y z]
          [(==> x y) (==> y z) -> (==> x z)]))
(defn comp-fn2 [f1 f2]
  (fn curr-fn [x]
    (let [[new-f1 first-c] (f1 x)
          y (first-c (inst identity (Result y)))
          [new-f2 new-c] (if (empty? y)
                           [f2 (inst abort-c z)]
                           (f2 (first y)))]
      [(when (and new-f1 new-f2)
         ((inst comp-fn2 x y z) new-f1 new-f2)) 
       new-c])))

;(defn comp-fn [[f & fs]]
;  (fn curr-fn [x]
;    (let [[new-f first-c] (f x)
;          [new-fs new-c] (reduce (fn [[new-fs c] f]
;                                   (let [y (c identity)
;                                         [new-f new-c] (if (empty? y)
;                                                         [f abort-c]
;                                                         (f (first y)))]
;                                     [(conj new-fs new-f) new-c]))
;                                 [[new-f] first-c]
;                                 fs)]
;      [(when-not (some nil? new-fs)
;         (comp-fn new-fs))
;       new-c])))
 

;Type only works for vectors of length 2
(ann nth-fn
     (All [x y z]
       (Fn ['0 (U nil (==> x z)) -> (==> '[x y] '[z y])]
           ['1 (U nil (==> y z)) -> (==> '[x y] '[x z])])))
(defn nth-fn [n f]
  (fn curr-fn [xs]
    (let [abort-c (ann-form (inst abort-c Any)
                            (Fn [(U nil [(Result '[x y]) -> (Result '[z y])]) -> (Result '[z y])]
                                [(U nil [(Result '[x y]) -> (Result '[x z])]) -> (Result '[x z])]))]
      (cond 
        (<= (count xs) n) [curr-fn abort-c]
        (nil? f) [nil abort-c] ;added - Ambrose
        :else
        (let [[new-f new-c] (f (nth xs n))
              next-c (->
                       (fn [c]
                         (if (nil? c)
                           (new-c nil)
                           (let [y (new-c (inst identity (Result z)))]
                             (if (empty? y)
                               (c [])
                               (c [(assoc xs n (first y))])))))
                       (ann-form (Fn [(U nil [(Result '[x y]) -> (Result '[z y])]) -> (Result '[z y])]
                                     [(U nil [(Result '[x y]) -> (Result '[x z])]) -> (Result '[x z])])))]
          [((inst nth-fn x y z) n new-f) next-c])))))


(tc-ignore
(defn gather-fn [[fs ys] [f y]]
  [(conj fs f) (conj ys y)])

(defn par-fn [fs]
  (fn curr-fn [xs]
      (if (not= (count xs) (count fs))
        [curr-fn abort-c]
        (let [[new-fs cs] (reduce gather-fn
                                  [[] []]
                                  (map #(%1 %2) fs xs))]
          [(par-fn new-fs)
           (fn [c]
               (if (nil? c)
                 (doseq [c cs]
                   (c nil))
                 (let [ys (map #(% identity) cs)]
                   (if (some empty? ys)
                     (c [])
                     (c [(apply concat ys)])))))]))))
  )

(ann select-fn
     (All [x y z]
       [(IPersistentMap x (U nil (==> y z))) -> (==> '[x y] z)]))
(defn select-fn [selection-map]
  (fn curr-fn [[v x]]
    (if-let [f (ann-form (or ((inst get (U nil (==> y z))) selection-map v)
                             ((inst get (U nil (==> y z))) selection-map '_))
                         (U nil (==> y z)))]
      (let [[new-f c] (f x)]
        [((inst select-fn x y z)
           ((inst assoc x (U nil (==> y z)) Any) 
              selection-map v new-f)) c])
      [curr-fn abort-c])))

(tc-ignore
(defn loop-fn
  ([f prev-x]
   (fn curr-fn [x]
     (let [[new-f c] (f [prev-x x])
           y (c identity)]
       (if (empty? y)
         [curr-fn abort-c]
         [(loop-fn new-f (first y)) (fn [c]
                                      (when c
                                        (c y)))]))))
  ([f fb-f prev-x]
   (fn curr-fn [x]
     (let [[new-f c] (f [prev-x x])
           y (c identity)]
       (if (empty? y)
         [curr-fn abort-c]
         (let [[new-fb fb-c] (fb-f (first y))
               fb-y (fb-c identity)]
           (if (empty? fb-y)
             [curr-fn abort-c]
             [(loop-fn new-f new-fb (first fb-y))
              (fn [c]
                (when c
                  (c y)))])))))))
  )

(def-alias AArr
  (All [x y]
       (I (==> x y)
          (IMeta '{:created-by ':a-arr
                   :args [x -> y]}))))

(def-alias AArrCtor 
  (All [x y]
       [[x -> y] -> (AArr x y)]))

(def-alias ACompMeta
  (All [x y z]
       '{:created-by ':a-comp
         :parts Part
         :args '[(==> x y) (==> y z)]}))

(def-alias AComp
  (All [x y z]
       (I (==> x z) (IMeta (ACompMeta x y z)))))

(def-alias ACompCtor
  (All [x y z]
       [(I (==> x y) MaybePartMeta) (I (==> y z) MaybePartMeta) -> (AComp x y z)]))

(ann a-arr AArrCtor)
(ann a-comp ACompCtor)

(ann conduit '{:a-arr AArrCtor :a-comp ACompCtor})
(defarrow conduit
  [a-arr (->
           (fn [f]
             (->
               (fn a-arr [x]
                 (let [y (f x)
                       c (->
                           (fn [c]
                             (when c
                               (c [y])))
                           (ann-form (Cont y)))]
                   [a-arr c]))
               (ann-form (==> x y))
               ((inst with-meta (==> x y) '{:created-by ':a-arr
                                            :args [x -> y]})
                 {:created-by :a-arr
                  :args f})))
           (ann-form AArrCtor))

   a-comp (->
            (fn [p1 p2]
              ((inst with-meta (==> x z) (ACompMeta x y z))
                (-> ((inst comp-fn2 x y z) p1 p2)
                  (ann-form (==> x z)))
                {:created-by :a-comp
                 :parts (ann-form (merge-parts [p1 p2]) Part)
                 :args (ann-form [p1 p2] '[(==> x y) (==> y z)])}))
            (ann-form ACompCtor))

   ;apply p to position n in passed pair
   ;eg. increment second element of each list
   ; (conduit-map (a-nth 1 (a-arr inc)) [[3 5] [3 4]])
   ;([3 6] [3 5])
   a-nth (ann-form
           (fn [n p]
             (ann-form [n p] '[(U '0 '1) (==> x y)])
             (ann-form ((inst nth-fn x y z) n p) (U (==> '[x y] '[z y])
                                                    (==> '[x y] '[x z])))
             (with-meta
               ((inst nth-fn x y z) n p)
               {:created-by :a-nth
                :parts (:parts (meta p))
                :args [n p]}))
           (All [x y z]
             (Fn ['0 (I (==> x z) MaybePartMeta) -> (I (==> '[x y] '[z y])
                                                       (IMeta '{:created-by ':a-nth
                                                                :parts (U nil Part)
                                                                :args '['0 (==> x z)]}))]
                 ['1 (I (==> y z) MaybePartMeta) -> (I (==> '[x y] '[x z])
                                                       (IMeta '{:created-by ':a-nth
                                                                :parts (U nil Part)
                                                                :args '['1 (==> y z)]}))])))

   ;like juxt
   ;modified to accept 2 arrows rather than n arrows
   a-par (ann-form 
           (fn [p1 p2]
             (with-meta
               (par-fn [p1 p2])
               {:created-by :a-par
                :args [p1 p2]
                :parts (merge-parts [p1 p2])}))
           (All [x y z]
             [(==> x y) (==> x z) -> (I (==> x '[y z])
                                        (IMeta '{:created-by ':a-par
                                                 :args [(==> x y) (==> x z)]
                                                 :parts Any}))]))

   ;apply functions to lhs and rhs of pairs
   ; modified to accept 2 arrows instead of n arrows
   a-all (ann-form 
           (fn [p1 p2]
             (with-meta
               (a-comp (a-arr (ann-form #(vector % %)
                                        (All [x] 
                                          [x -> '[x x]])))
                       (a-par p1 p2))
               {:created-by :a-all
                :args [p1 p2]
                :parts (merge-parts [p1 p2])}))
           (All [x y]
             [(==> x y) (==> z a) -> (I (==> '[x z] '[y a])
                                        (IMeta '{:created-by ':a-all
                                                 :args '[(==> x y) (==> z a)]
                                                 :parts Any}))]))

   ;select a value
   a-select (ann-form
              (fn [pair-map]
                (with-meta
                  (select-fn pair-map)
                  {:created-by :a-select
                   :args pair-map
                   :parts (merge-parts (vals pair-map))}))
              (All [x y z]
                [(IPersistentMap x (==> y z)) -> (==> x (==> y z))]))

   a-loop (ann-form 
            (fn
              ([p initial-value]
               (with-meta
                 (loop-fn p initial-value)
                 {:created-by :a-loop
                  :args [p initial-value]
                  :parts (:parts p)}))
              ([p initial-value fb-p]
               (with-meta
                 (loop-fn p fb-p initial-value)
                 {:created-by :a-loop
                  :args [p initial-value fb-p]
                  :parts (:parts p)})))
            (All [state in]
                 [(==> '[state in] state) state -> (I (==> in state)
                                                      (IMeta '{:created-by ':a-loop
                                                               :args '[(==> '[state in] state) state]}))]))
   ])

(def a-arr (conduit :a-arr))
(def a-comp (conduit :a-comp))
(def a-nth (conduit :a-nth))
(def a-par (conduit :a-par))
(def a-all (conduit :a-all))
(def a-select (conduit :a-select))
(def a-loop (conduit :a-loop))

(ann conduit-map
     (All [x y]
       [(==> x y) (Seqable x) -> (Seqable y)]))
(defn conduit-map [p l]
  (if (empty? l)
    l
    (a-run (comp-fn2 (conduit-seq l) p))))

(ann pass-through 
     (All [x]
       (==> x x)))
(def pass-through
  (a-arr identity))

(ann a-selectp
     (All [x y z a]
          [[x -> y] (IPersistentMap y (==> z a)) -> (==> '[x z] a)]))
(defn a-selectp [pred pair-map]
  (a-comp
    (a-all (a-arr pred)
           pass-through)
    (a-select pair-map)))

(ann a-if
     (All [x y z]
       (Fn [[x -> y] [y -> z] -> (==> x (U z nil))]
           [[x -> y] [y -> z] [y -> z] -> (==> x z)])))
(defn a-if
  ([a b] (a-if a b nil))
  ([a b c]
   (let [c (or c (a-arr (constantly nil)))]
     (a-comp (a-all (a-arr (comp boolean a))
                    pass-through)
             (a-select
               {true b
                false c})))))

(defn a-catch
  ([p catch-p]
   (a-catch Exception p catch-p))
  ([class p catch-p]
   (letfn [(a-catch [f catch-f]
             (fn [x]
               (try
                 (let [[new-f c] (f x)]
                   [(a-catch f catch-f) c])
                 (catch Throwable e
                   (if (instance? class e)
                     (let [[new-catch c] (catch-f [e x])]
                       [(a-catch f new-catch) c])
                     (throw e))))))]
     (with-meta
       (a-catch p catch-p)
       {:parts (:parts p)
        :created-by :a-catch
        :args [class p catch-p]}))))

(defn a-finally [p final-p]
  (letfn [(a-finally [f final-f]
            (fn [x]
              (try
                (let [[new-f c] (f x)]
                  [(a-finally new-f final-f) c])
                (finally
                  (final-f x)))))]
    (with-meta
      (a-finally p final-p)
      {:parts (:parts p)
       :created-by :a-finally
       :args [p final-p]})))

(defmacro def-arr [name args & body]
  `(def ~name (a-arr (fn ~name ~args ~@body))))

(defn a-filter [f]
  (with-meta
    (fn curr-fn [x]
      (if (f x)
        [curr-fn (fn [c]
                   (when c
                     (c [x])))]
        [curr-fn abort-c]))
    {:created-by :a-filter
     :args f}))

(defn tap [p]
  (fn [x]
    (let [[new-f new-c] (p x)]
      (new-c nil)
      [new-f (fn [c]
               (when c
                 (c [x])))])))

(defn disperse [p]
  (with-meta
    (fn curr-fn [xs]
      (if (empty? xs)
        [curr-fn (fn [c]
                   (when c
                     (c [xs])))]
        (let [[new-f cs] (reduce (fn [[new-f cs] x]
                                   (let [[new-f c] (new-f x)]
                                     [new-f (conj cs c)]))
                                 [p []]
                                 xs)]
          [(disperse new-f) (fn [c]
                              (if (nil? c)
                                (doseq [c cs]
                                  (c nil))
                                (let [ys (map #(% identity) cs)]
                                  (if (some empty? ys)
                                    (c [])
                                    (c [(apply concat ys)])))))])))
    {:created-by :disperse
     :args p
     :parts (:parts p)}))

(defn enqueue [f x]
  ((second (f x)) nil)
  nil)

(defn wait-for-reply [f x]
  ((second (f x)) identity))

(defn test-conduit [p]
  (let [args (:args (meta p))]
    (condp = (:created-by (meta p))
      nil p
      :a-arr (a-arr args)
      :a-comp (apply a-comp (map test-conduit args))
      :a-nth (apply a-nth (map test-conduit args))
      :a-par (apply a-par (map test-conduit args))
      :a-all (apply a-all (map test-conduit args))
      :a-select (apply a-select (mapcat (fn [[k v]]
                                          [k (test-conduit v)])
                                        args))
      :a-loop (let [[bp iv fb] args]
                (if fb
                  (a-loop (test-conduit bp)
                          iv
                          (test-conduit fb))
                  (a-loop (test-conduit bp)
                          iv)))
      :a-catch (apply a-catch (first args)
                      (map test-conduit (rest args)))
      :a-finally (apply a-finally (map test-conduit args))
      :a-filter p
      :disperse (disperse (test-conduit args)))))

(defn test-conduit-fn [p]
  (partial wait-for-reply (test-conduit p)))

