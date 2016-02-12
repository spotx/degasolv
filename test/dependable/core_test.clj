(ns dependable.core-test
  (:require [clojure.test :refer :all]
            [dependable.core :refer :all]))

(defn map-query [m]
  (fn [nm]
    (let [result (find m nm)]
      (if (nil? result)
        []
        (let [[k v] result]
          v)))))

(let  [package-a30
      {:name "a"
       :version 30
       :location "a_loc30"
       :conflicts {"c" nil}
       }
      package-a20
      {:name "a"
       :version 20
       :location "a_loc20"}
      package-c10
      {:name "c"
       :version 10
       :location "c_loc10"}
      package-d22
       {:name "d"
        :version 22
        :location "d_loc22"}
      package-e18
       {:name "e"
        :version 18
        :location "e_loc18"
        :conflicts {"d" nil}}
      repo-info
      {"a"
       [package-a30
        package-a20]
       "c"
       [package-c10]
       "d"
       [package-d22]}
       query (map-query repo-info)]
  (deftest retrieval
           (testing "Asking for a present package succeeds."
                    (is (= (resolve-dependencies
                             [{:name "a"}]
                             query)
                           [:successful package-a30])))
                    (is (= (resolve-dependencies
                             [{:name "c"}]
                             query)
                           [:successful package-c10]))
           (testing "Asking for a nonexistent package fails."
                    (is (= (resolve-dependencies
                             [{:name "b"}]
                             query)
                           [:unsatisfiable "b"])))
           (testing (str "Asking for a package present within the repo but "
                         "at no suitable version fails.")
                    (is (= (resolve-dependencies
                             [{:name "a"
                               :version-spec #(>= %1 40)}]
                             query)
                           [:unsatisfiable "a"])))
           (testing (str "Asking for a package present within the repo but "
                         "with unsatisfiable constraints.")
                    (is (= (resolve-dependencies
                             [{:name "a"
                               :version-spec (fn [v] false)}]
                             query)
                           [:unsatisfiable "a"])))
           (testing (str "Asking for a package present and having a "
                         "version that fits")
                    (is (= (resolve-dependencies
                             [{:name "a"
                               :version-spec #(and (>= %1 15) (<= %1 25))}]
                             query)
                             [:successful package-a20]))
                    (is (= (resolve-dependencies
                             [{:name "a"
                               :version-spec #(>= %1 25)}]
                             query)
                           [:successful package-a30]))))
  (deftest already-installed
           (testing "Asking to install a package twice."
                    (is (= (resolve-dependencies
                             [{:name "a"}
                              {:name "a"}]
                             query)
                           [:successful package-a30])))
           (testing (str "Asking to install a package that I have given as "
                    "already installed.")
                    (is (= (resolve-dependencies [{:name "c"}]
                                                 query
                                                 :already-installed {"c" 18})
                           [:successful])))
           (testing (str "Asking to install a package that I have given as already "
                         "installed, even though the package isn't available.")
                    (is (= (resolve-dependencies
                             [{:name "b"}]
                             query
                             :already-installed {"b" 30})
                           [:successful])))
           (testing (str "Asking to install a package that is already "
                         "installed, but the installed version doesn't "
                         "suit, even though there is a suitable version "
                         "available.")
                    (is (= (resolve-dependencies
                             [{:name "a"
                               :version-spec #(>= % 25)}]
                             query
                              :already-installed {"a" 15})
                           [:unsatisfiable "a"])))
           (testing (str "Asking to install a package that is already "
                         "installed, and the installed version suits.")
                    (is (= (resolve-dependencies
                             [{:name "a"
                               :version-spec #(>= % 25)}]
                             query
                             :already-installed {"a" 30})
                           [:successful]))))
  (deftest conflicts
           (testing (str "Find a package which conflicts with another "
                         "package also to be installed.")
                    (is (= (resolve-dependencies
                             [{:name "d"}
                              {:name "e"}]
                             query)
                           [:unsatisfiable "e"])))
           (testing (str "Find a package which conflicts with a package "
                         "marked a priori as conflicting.")
                    (is (= (resolve-dependencies
                             [{:name "a"}
                              {:name "d"}]
                             query
                             :conflicts {"d" nil})
                           [:unsatisfiable "d"])))
           (testing (str "Find a package which conflicts with a package "
                         "marked a priori as conflicting with something "
                         "already installed.")
                    (is (= (resolve-dependencies
                             [{:name "d"}]
                             query
                             :already-installed {"a" 11}
                             :conflicts {"d" nil})
                           [:unsatisfiable "d"])))

           (testing (str "Find a package which conflicts with another "
                         "package but not at its current version")
                    (is (= (resolve-dependencies
                             [{:name "d"}]
                             query
                             :conflicts {"d" #(< % 22)})
                           [:successful package-d22])))))

(deftest requires
         (let [package-a
               {:name "a"
                :version 30
                :location "a_loc30"
                :requires [{:name "b"}]}
               package-b
               {:name "b"
                :version 20
                :location "b_loc20"}
               repo-info
               {"a" [package-a]
                "b" [package-b]}
               query (map-query repo-info)]
           (testing (str "One package should require another and both "
                         "should be found.")
                    (is (= (resolve-dependencies
                             [{:name "a"}]
                             query)
                           [:successful package-a package-b])))
           (testing (str "One package should be found when it requires "
                         "another, but it's already installed.")
                    (is (= (resolve-dependencies
                             [{:name "a"}]
                             query
                             :already-installed {"b" 20})
                           [:successful package-a]))))
         (let [package-a
               {:name "a"
                :version 10
                :location "a_loc30"}
               package-b
               {:name "b"
                :version 20
                :location "b_loc20"
                :requires [{:name "c"}]}
               package-c
               {:name "c"
                :version 10
                :conflicts {"a" nil}}

               repo-info
               {"a" [package-a]
                "b" [package-b]
                "c" [package-c]}
               query (map-query repo-info)]
           (testing (str "A package having dependencies which conflicts with "
                         "other packages downloaded should be rejected.")
                    (is (= (resolve-dependencies
                             [{:name "a"}
                              {:name "b"}]
                             query)
                           [:unsatisfiable "b" "c"]))))
           (let [package-a
                 {:name "a"
                  :version 10
                  :location "a_loc10"}
                 package-b
                 {:name "b"
                  :version 10
                  :location "b_loc10"
                  :requires [{:name "c"}]}
                 package-c
                 {:name "c"
                  :version 10
                  :local "c_loc10"}
                 repo-info
                 {"a" [package-a]
                  "b" [package-b]
                  "c" [package-c]}
                 query (map-query repo-info)]
           (testing (str "A package having dependencies rejected a priori "
                         "also gets rejected")
                    (is (= (resolve-dependencies
                             [{:name "a"}
                              {:name "b"}]
                             query
                             :conflicts {"c" nil})
                           [:unsatisfiable "b" "c"])))))


;; *maybe*. I'll think about it.
;; Also get other tests from notebook.
#_(deftest no-locking
           (testing (str "Find two packages, even when the preferred version "
                         "of one package conflicts with the other")
                    (is (= (resolve-dependencies
                             [{:name "a"}
                              {:name "c"}]
                             query)
                           [:successful package-a20 package-c10]))))


