(in-ns 'degasolv.resolver)

(defmacro dbg2 [body]
  `(let [x# ~body]
     (println "dbg:" '~body "=" x#)
     x#))

(def ^:private relation-strings
  {:greater-than ">"
   :greater-equal ">="
   :equal-to "=="
   :not-equal "!="
   :less-equal "<="
   :less-than "<"
   :matches "<>"
   :in-range "=>"
   :pess-greater "><"})

(defrecord DecoratedRequirement [clause parent])

(defrecord VersionPredicate [relation version]
  Object
  (toString [this]
    (str
     ((:relation this) relation-strings)
     version)))

(defrecord Requirement [status id spec]
  Object
  (toString [this]
    (str
     (if (= (:status this) :absent)
       "!"
       "")
     (:id this)
     (clj-str/join
      ";"
      (map
       (fn conjoin-preds [conjunction]
         (clj-str/join
          ","
          (map
           #(str %)
           conjunction)))
       (:spec this))))))

(defrecord PackageInfo [id version location requirements])

;; Deprecated, do not use
(def ->requirement ->Requirement)
(def ->package ->PackageInfo)
(def ->version-predicate ->VersionPredicate)

(defmethod
  print-method
  degasolv.resolver.PackageInfo
  [this w]
  (tag/pr-tagged-record-on this w))

(defmethod
  print-method
  degasolv.resolver.VersionPredicate
  [this w]
  (tag/pr-tagged-record-on this w))

(defmethod
  print-method
  degasolv.resolver.Requirement
  [this w]
  (tag/pr-tagged-record-on this w))

(defn present
  ([id] (present id nil))
  ([id spec] (->Requirement :present id spec)))

(defn absent
  ([id] (absent id nil))
  ([id spec] (->Requirement :absent id spec)))

(defn- spec-call [f v]
  (f v))

(def ^:private nil-safe-spec-call
  (fnil spec-call (fn [v] true)))

(defn- successful? [result]
  (and (sequential? result)
       (= (first result) :successful)))

(defn- first-successful
  [result]
  (if (successful? result)
    result
    nil))

(defn- make-comparison [cmp pkg-ver relation version]
  (if (= relation :matches)
    (if-let [pattern (try (re-pattern version)
                          (catch Exception e
                            false))]
      (re-matches pattern pkg-ver)
      false)
    (let [cmp-result (cmp pkg-ver version)]
      (cond
        (= relation
             :in-range)
        (if-let [[_ rest re-num _] (re-find #"^(.*?)(\d+)(\D*)$" version)]
          (let [num (java.lang.Integer/parseInt re-num)
                higher-version (str rest (inc num))
                higher-result (cmp pkg-ver higher-version)]
            (and (>= cmp-result 0)
               (< higher-result 0)))
          false)
        (= relation
           :pess-greater)
        (if (not (re-find #"\d+" version))
          false
          (let [split-on-nums (clj-str/split version #"\d+")
                non-nums (if (empty? split-on-nums) [""] split-on-nums)
                nums (clj-str/split version #"\D+")
                strnum (first nums)
                intnum (java.lang.Integer/parseInt strnum)
                higher-result (as-> intnum it
                                (inc it)
                                (str (first non-nums) it)
                                (cmp pkg-ver it))]
            (and (>= cmp-result 0)
                 (< higher-result 0))))
        :else
        (case relation
          :greater-than
          (pos? cmp-result)
          :greater-equal
          (not (neg? cmp-result))
          :equal-to
          (zero? cmp-result)
          :not-equal
          (not (zero? cmp-result))
          :less-equal
          (not (pos? cmp-result))
          :less-than
          (neg? cmp-result)
          false)))))

(defprotocol ^:private SpecCaller
  (p-safe-spec-call [this spec present-package]))

(extend-protocol SpecCaller
  nil
  (p-safe-spec-call [this spec present-package]
    (nil-safe-spec-call spec present-package))
  clojure.lang.IFn
  (p-safe-spec-call [cmp spec present-package]
    (if (nil? spec)
      true
      (let [pkg-ver (:version present-package)]
        (reduce
         (fn [disj-cum disj-val]
           (or disj-cum
               (reduce
                (fn [conj-cum {:keys [relation version]}]
                  (and conj-cum
                       (make-comparison
                        cmp
                        pkg-ver
                        relation
                        version)))
                 true disj-val)))
         false
         spec)))))

(defn- aggregate-attempts [c v]
  (conj c v))

(defn make-spec-call [cmp]
  (partial p-safe-spec-call cmp))

(defn- cull-nothing [candidates]
  candidates)

(defn- cull-all-but-first [candidates]
  (if (empty? candidates)
    candidates
    [(first candidates)]))

(defn- hoist [alternatives
              absent-specs
              found-packages
              present-packages]
  (if (= 1 (count alternatives))
    alternatives
    (let [partn
          (group-by
           (fn [term]
             (let [id (get term :id)]
               (cond
                 (get
                  absent-specs
                  id)
                 :absent
                 (or
                  (get
                   found-packages
                   id)
                  (get
                   present-packages
                   id))
                 :present
                 :else
                 :unspecified)))
           alternatives)
          result
          (concat (:absent partn) (:present partn) (:unspecified partn))]
      result)))

;; If transformed value passes test,
;; return a singleton list of those;
;; otherwise, return the full list of transformed stuffs.
;; This is because lazy seqs in clojure aren't exactly lazy;
;; they're "chunked" lazy, which is sad. Sad panda :'(
(defn- first-found
  [f pred coll]
  (reduce
   (fn find-first
     [c v]
     (let [new-v (f v)]
       (if (pred new-v)
         (reduced [new-v])
         (conj c new-v))))
   []
   coll))

;; Does one of the present packages already
;; satisfy criteria? If so, return it.
(defn- present-packages-satisfies?
  [pkgs
   spec
   safe-spec-call
   status]
  (some
   #(when (not (nil? %)) %)
   (map
    (fn [pkg]
      (let [present-package-test
            (safe-spec-call
             spec
             pkg)]
        (when (or (and (= status :absent)
                       (not present-package-test))
                  (and (= status :present)
                       present-package-test))
          pkg)))
    pkgs)))

;; root -> a
;; root -> x
;; root -> b
;; a -> c
;; a -> d
;; x -> y
;; x -> z
;; b -> e
;; e -> a
;; b -> a
(defn list-packages [package-graph & {:keys [list-strat] :or {list-strat :lazy}}]
  (letfn [(list-pkgs-rec
            [already-visited
             parents
             children-of]
            (let [children (filter
                            #(and
                              (not (get already-visited %))
                              (not (get parents %)))
                             (get package-graph children-of))]
              (if (empty? children)
                {:pkg-list []
                 :visited #{}}
                (let [{list-from-children :pkg-list
                       visited-from-children :visited
                       :as children-results}
                      (reduce
                        (fn gather-lists [{:keys [pkg-list visited]} v]
                          (let [{grandchildren-list :pkg-list
                                 grandchildren-visited :visited}
                                (list-pkgs-rec visited (conj parents v) v)
                                base-pkg-list (into
                                                pkg-list
                                                grandchildren-list)
                                base-visited (into
                                              visited
                                              grandchildren-visited)]
                            (if (and (= list-strat :eager)
                                     (not (get base-visited v)))
                              {:pkg-list (conj base-pkg-list v)
                               :visited (conj base-visited v)}
                              {:pkg-list base-pkg-list
                               :visited base-visited})))
                        {:pkg-list []
                         :visited already-visited}
                        children)]
                  (if (= list-strat :lazy)
                    {:pkg-list (into list-from-children
                                     (filter
                                       #(not (get visited-from-children %))
                                       children))
                     :visited (into visited-from-children children)}
                    children-results)))))]
    (let [{:keys [pkg-list visited]} (list-pkgs-rec #{} #{:root} :root)]
      pkg-list)))

(defn resolve-dependencies
  [requirements
   query & {:keys [present-packages
                   conflicts
                   strategy
                   conflict-strat
                   compare
                   search-strat
                   allow-alternatives
                   list-strat]
            :or {present-packages {}
                 conflicts {}
                 strategy :thorough
                 conflict-strat :exclusive
                 compare nil
                 search-strat :breadth-first
                 allow-alternatives true
                 list-strat :as-set}}]
  (let [concat-reqs
        (if (= search-strat :depth-first)
          #(concat %2 %1)
          #(concat %1 %2))
        safe-spec-call (make-spec-call compare)
        cull (case strategy
               :thorough
               cull-nothing
               :fast
               cull-all-but-first
               (throw
                (ex-info (str
                          "Invalid strategy `"
                          strategy
                          "`.")
                         {:strategy strategy})))
        cull-alternatives
        (if allow-alternatives
          cull-nothing
          cull-all-but-first)]
    (letfn [(resolve-deps
              [repo
               present-packages
               found-packages
               absent-specs
               clauses
               package-graph]
              (if (empty? clauses)
                [:successful
                 package-graph]
                (let [fclause (first clauses)
                      rclauses (rest clauses)
                      {:keys [clause parent]}
                      fclause]
                  (if (empty? clause)
                    [:unsuccessful
                     {:problems
                      [{:term clause
                        :found-packages found-packages
                        :present-packages present-packages
                        :absent-specs absent-specs
                        :reason :empty-alternative-set}]}]
                    (let [clause-result
                          (first-found
                           (fn try-alternative
                             [alternative]
                             (let [{status :status id :id spec :spec}
                                   alternative
                                   present-id-packages
                                   (get present-packages id)
                                   found-id-packages
                                   (get found-packages id)
                                   get-pkg-exists
                                   (fn get-pkg-exists [pkgs]
                                     (when (not (nil? pkgs))
                                       (if (= conflict-strat :prioritized)
                                         (first pkgs)
                                         (present-packages-satisfies?
                                          pkgs
                                          spec
                                          safe-spec-call
                                          status))))
                                   present-package
                                   (get-pkg-exists present-id-packages)
                                   found-package
                                   (get-pkg-exists found-id-packages)]
                               (cond
                                 (not
                                  (nil? present-package))
                                 (resolve-deps
                                  repo
                                  present-packages
                                  found-packages
                                  absent-specs
                                  rclauses
                                  package-graph)
                                 (not
                                  (nil? found-package))
                                 (resolve-deps
                                  repo
                                  present-packages
                                  found-packages
                                  absent-specs
                                  rclauses
                                  (update-in package-graph
                                             [parent]
                                             #(if (empty? %1)
                                                (do [%2])
                                                (conj %1 %2))
                                             found-package))
                                 (and (or
                                       (not (nil? found-id-packages))
                                       (not (nil? present-id-packages)))
                                      (not (= conflict-strat :inclusive)))
                                 [:unsuccessful
                                  {:problems
                                   [
                                    {:term clause
                                     :found-packages found-packages
                                     :present-packages present-packages
                                     :absent-specs absent-specs
                                     :reason :present-package-conflict
                                     :alternative alternative
                                     :package-id id}]}]
                                 (= status :absent)
                                 (resolve-deps
                                  repo
                                  present-packages
                                  found-packages
                                  (update-in absent-specs
                                             [id]
                                             #(if (empty? %1)
                                                (do [%2])
                                                (conj %1 %2))
                                             spec)
                                  rclauses
                                  package-graph)
                                 (= status :present)
                                 (let [query-results (repo id)]
                                   (if (empty? query-results)
                                     [:unsuccessful
                                      {:problems
                                       [
                                        {:term clause
                                         :alternative alternative
                                         :found-packages found-packages
                                         :present-packages present-packages
                                         :absent-specs absent-specs
                                         :reason :package-not-found
                                         :package-id id}]}]
                                     (let [filtered-query-results
                                           (cull
                                            (filter
                                             (fn vet-candidate
                                               [candidate]
                                               (and
                                                (safe-spec-call spec candidate)
                                                (reduce
                                                 (fn [x y]
                                                   (and
                                                    x
                                                    (not
                                                     (safe-spec-call
                                                      y
                                                      candidate))))
                                                 true
                                                 (get absent-specs id))))
                                             query-results))]
                                       (if (empty? filtered-query-results)
                                         [:unsuccessful
                                          {:problems
                                           [{:term clause
                                             :alternative alternative
                                             :found-packages found-packages
                                             :present-packages present-packages
                                             :absent-specs absent-specs
                                             :reason :package-rejected
                                             :package-id id}]}]
                                         (let [candidate-results
                                               (first-found
                                                (fn
                                                  try-candidate
                                                  [candidate]
                                                  (resolve-deps
                                                   repo
                                                   present-packages
                                                   (update-in found-packages
                                                              [id]
                                                              #(if (empty? %1)
                                                                 (do [%2])
                                                                 (conj %1 %2))
                                                              candidate)
                                                   absent-specs
                                                   (concat-reqs
                                                    rclauses
                                                    (map
                                                     #(->DecoratedRequirement
                                                       %
                                                       candidate)
                                                     (:requirements
                                                      candidate)))
                                                   (update-in package-graph
                                                              [parent]
                                                              #(if (empty? %1)
                                                                 (do [%2])
                                                                 (conj %1 %2))
                                                              candidate)))
                                                successful?
                                                filtered-query-results)]
                                           (or
                                            (some
                                             first-successful
                                             candidate-results)
                                            [:unsuccessful
                                             {:problems
                                              (flatten
                                               (map
                                                #(:problems
                                                  (get % 1))
                                                candidate-results))}]))))))
                                 :else
                                 [:unsuccessful {:problems
                                                 [{:term clause
                                                   :reason :uncovered-case
                                                   :alternative alternative
                                                   :found-packages found-packages
                                                   :present-packages present-packages
                                                   :absent-specs absent-specs}]}])))
                           successful?
                           ;; Hoisting
                           (hoist (cull-alternatives
                                   clause)
                                  absent-specs
                                  found-packages
                                  present-packages))]
                      (or
                       (some
                        first-successful
                        clause-result)
                       [:unsuccessful
                        {:problems
                         (flatten
                          (map
                           #(:problems
                             (get % 1))
                           clause-result))}]))))))]
      (let [result
            (resolve-deps
             query
             present-packages
             {}
             conflicts
             (map #(->DecoratedRequirement % :root) requirements)
             {})]
        (if (= :successful (first result))
          [:successful
           (if (= :as-set list-strat)
             (set
              (list-packages
               (second result)
               :list-strat :lazy))
             (list-packages
              (second result)
              :list-strat list-strat))]
          result)))))
