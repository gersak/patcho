(ns patcho.patch-test
  (:require [clojure.test :refer [deftest testing is]]
            [patcho.patch :as patch]))

(deftest version-definition-test
  (testing "Defining current version"
    (patch/current-version ::test-app "1.0.0")
    (is (= "1.0.0" (patch/version ::test-app)))

    (patch/current-version ::test-db "2.5.1")
    (is (= "2.5.1" (patch/version ::test-db)))))

(deftest available-versions-test
  (testing "Listing available versions"
    (patch/current-version ::test-v1 "1.0.0")
    (patch/current-version ::test-v2 "2.0.0")

    (testing "Get all versions"
      (let [versions (patch/available-versions)]
        (is (= "1.0.0" (::test-v1 versions)))
        (is (= "2.0.0" (::test-v2 versions)))))

    (testing "Get specific versions"
      (is (= {::test-v1 "1.0.0"}
             (patch/available-versions ::test-v1)))
      (is (= {::test-v1 "1.0.0" ::test-v2 "2.0.0"}
             (patch/available-versions ::test-v1 ::test-v2))))))

(deftest upgrade-patches-test
  (testing "Upgrade patches are applied in correct order"
    (let [execution-order (atom [])]
      (patch/current-version ::test-upgrades "3.0.0")

      (patch/upgrade ::test-upgrades "1.0.0"
                     (swap! execution-order conj "1.0.0"))

      (patch/upgrade ::test-upgrades "2.0.0"
                     (swap! execution-order conj "2.0.0"))

      (patch/upgrade ::test-upgrades "3.0.0"
                     (swap! execution-order conj "3.0.0"))

      ; Apply from beginning
      (reset! execution-order [])
      (patch/apply ::test-upgrades nil)
      (is (= ["1.0.0" "2.0.0" "3.0.0"] @execution-order))

      ; Apply from 1.0.0
      (reset! execution-order [])
      (patch/apply ::test-upgrades "1.0.0")
      (is (= ["2.0.0" "3.0.0"] @execution-order))

      ; Apply from 2.0.0 to 3.0.0
      (reset! execution-order [])
      (patch/apply ::test-upgrades "2.0.0" "3.0.0")
      (is (= ["3.0.0"] @execution-order)))))

(deftest downgrade-patches-test
  (testing "Downgrade patches are applied in correct order"
    (let [execution-order (atom [])]
      (patch/current-version ::test-downgrades "3.0.0")

      (patch/downgrade ::test-downgrades "3.0.0"
                       (swap! execution-order conj "down-3.0.0"))

      (patch/downgrade ::test-downgrades "2.0.0"
                       (swap! execution-order conj "down-2.0.0"))

      (patch/downgrade ::test-downgrades "1.0.0"
                       (swap! execution-order conj "down-1.0.0"))

      ; Downgrade from 3.0.0 to 1.0.0
      (reset! execution-order [])
      (patch/apply ::test-downgrades "3.0.0" "1.0.0")
      (is (= ["down-3.0.0" "down-2.0.0"] @execution-order))

      ; Downgrade from 2.0.0 to 0
      (reset! execution-order [])
      (patch/apply ::test-downgrades "2.0.0" "0")
      (is (= ["down-2.0.0" "down-1.0.0"] @execution-order)))))

(deftest mixed-upgrade-downgrade-test
  (testing "Both upgrades and downgrades work correctly"
    (let [state (atom {:version "0" :changes []})]
      (patch/current-version ::test-mixed "3.0.0")

      ; Define upgrades
      (patch/upgrade ::test-mixed "1.0.0"
                     (swap! state #(-> %
                                       (assoc :version "1.0.0")
                                       (update :changes conj "Added feature A"))))

      (patch/upgrade ::test-mixed "2.0.0"
                     (swap! state #(-> %
                                       (assoc :version "2.0.0")
                                       (update :changes conj "Added feature B"))))

      (patch/upgrade ::test-mixed "3.0.0"
                     (swap! state #(-> %
                                       (assoc :version "3.0.0")
                                       (update :changes conj "Added feature C"))))

      ; Define downgrades
      (patch/downgrade ::test-mixed "3.0.0"
                       (swap! state #(-> %
                                         (assoc :version "2.0.0")
                                         (update :changes conj "Removed feature C"))))

      (patch/downgrade ::test-mixed "2.0.0"
                       (swap! state #(-> %
                                         (assoc :version "1.0.0")
                                         (update :changes conj "Removed feature B"))))

      ; Test upgrade path
      (reset! state {:version "0" :changes []})
      (patch/apply ::test-mixed "0" "2.0.0")
      (is (= "2.0.0" (:version @state)))
      (is (= ["Added feature A" "Added feature B"] (:changes @state)))

      ; Test downgrade path
      (patch/apply ::test-mixed "2.0.0" "1.0.0")
      (is (= "1.0.0" (:version @state)))
      (is (= ["Added feature A" "Added feature B" "Removed feature B"]
             (:changes @state))))))

(deftest edge-cases-test
  (testing "Edge cases and special scenarios"
    (patch/current-version ::test-edge "1.0.0")

    (testing "No patches needed when versions are equal"
      (let [executed (atom false)]
        (patch/upgrade ::test-edge "1.0.0"
                       (reset! executed true))

        (patch/apply ::test-edge "1.0.0" "1.0.0")
        (is (false? @executed))))

    (testing "Nil current version treated as '0'"
      (let [executed (atom false)]
        (patch/upgrade ::test-edge "0.5.0"
                       (reset! executed true))

        (patch/apply ::test-edge nil "0.5.0")
        (is (true? @executed))))

    (testing "Skip patches outside version range"
      (let [executions (atom [])]
        (patch/current-version ::test-range "5.0.0")

        (patch/upgrade ::test-range "1.0.0"
                       (swap! executions conj "1.0.0"))
        (patch/upgrade ::test-range "2.0.0"
                       (swap! executions conj "2.0.0"))
        (patch/upgrade ::test-range "3.0.0"
                       (swap! executions conj "3.0.0"))
        (patch/upgrade ::test-range "4.0.0"
                       (swap! executions conj "4.0.0"))

        ; Only apply patches between 2.0.0 and 3.0.0
        (patch/apply ::test-range "2.0.0" "3.0.0")
        (is (= ["3.0.0"] @executions))))))

(deftest deployed-version-test
  (testing "Deployed version functionality"
    (patch/current-version ::test-deployed "3.0.0")
    (patch/installed-version ::test-deployed "2.0.0")

    (is (= "2.0.0" (patch/deployed-version ::test-deployed)))
    (is (= "3.0.0" (patch/version ::test-deployed)))))

(deftest single-arity-apply-test
  (testing "Single arity apply uses deployed-version"
    (let [executions (atom [])]
      (patch/current-version ::test-single "2.0.0")
      (patch/installed-version ::test-single "1.0.0")

      (patch/upgrade ::test-single "2.0.0"
                     (swap! executions conj "2.0.0"))

      ; Should migrate from deployed-version (1.0.0) to current-version (2.0.0)
      (patch/apply ::test-single)
      (is (= ["2.0.0"] @executions)))))

;;; VersionStore tests

(deftest atom-version-store-test
  (testing "AtomVersionStore read and write"
    (let [store (patch/->AtomVersionStore (atom {}))]
      (testing "Read non-existent version returns '0'"
        (is (= "0" (patch/read-version store ::test-atom-store))))

      (testing "Write and read version"
        (patch/write-version store ::test-atom-store "1.5.0")
        (is (= "1.5.0" (patch/read-version store ::test-atom-store))))

      (testing "Multiple topics in same store"
        (patch/write-version store ::app1 "2.0.0")
        (patch/write-version store ::app2 "3.0.0")
        (is (= "2.0.0" (patch/read-version store ::app1)))
        (is (= "3.0.0" (patch/read-version store ::app2)))))))

(deftest file-version-store-test
  (testing "FileVersionStore read and write"
    (let [temp-file (java.io.File/createTempFile "patcho-test" ".edn")
          file-path (.getAbsolutePath temp-file)
          store (patch/->FileVersionStore file-path)]
      (try
        (testing "Read non-existent file returns '0'"
          (.delete temp-file)
          (is (= "0" (patch/read-version store ::test-file-store))))

        (testing "Write and read version"
          (patch/write-version store ::test-file-store "1.0.0")
          (is (= "1.0.0" (patch/read-version store ::test-file-store))))

        (testing "Persistence across store instances"
          (let [store2 (patch/->FileVersionStore file-path)]
            (is (= "1.0.0" (patch/read-version store2 ::test-file-store)))))

        (testing "Multiple topics in same file"
          (patch/write-version store ::db "2.5.0")
          (patch/write-version store ::api "3.1.0")
          (is (= "1.0.0" (patch/read-version store ::test-file-store)))
          (is (= "2.5.0" (patch/read-version store ::db)))
          (is (= "3.1.0" (patch/read-version store ::api))))

        (finally
          (.delete temp-file))))))

(deftest apply-with-store-test
  (testing "Apply automatically persists version to store"
    (let [store (patch/->AtomVersionStore (atom {}))
          executions (atom [])]
      (patch/set-store! store)
      (patch/current-version ::test-with-store "3.0.0")
      (patch/installed-version ::test-with-store (patch/read-version store ::test-with-store))

      (patch/upgrade ::test-with-store "1.0.0"
                     (swap! executions conj "1.0.0"))
      (patch/upgrade ::test-with-store "2.0.0"
                     (swap! executions conj "2.0.0"))
      (patch/upgrade ::test-with-store "3.0.0"
                     (swap! executions conj "3.0.0"))

      (testing "Store starts at 0"
        (is (= "0" (patch/read-version store ::test-with-store))))

      (testing "After apply, store is updated to target version"
        (patch/apply ::test-with-store)
        (is (= "3.0.0" (patch/read-version store ::test-with-store)))
        (is (= ["1.0.0" "2.0.0" "3.0.0"] @executions)))

      (testing "Subsequent apply does nothing (versions equal)"
        (reset! executions [])
        (patch/apply ::test-with-store)
        (is (= [] @executions))
        (is (= "3.0.0" (patch/read-version store ::test-with-store)))))))

(deftest scoped-store-with-binding-test
  (testing "with-store macro provides scoped store"
    (let [global-store (patch/->AtomVersionStore (atom {}))
          scoped-store (patch/->AtomVersionStore (atom {}))
          executions (atom [])]
      (patch/set-store! global-store)
      (patch/current-version ::test-scoped "2.0.0")
      (patch/installed-version ::test-scoped
                               (or (patch/read-version global-store ::test-scoped) "0"))

      (patch/upgrade ::test-scoped "1.0.0"
                     (swap! executions conj "1.0.0"))
      (patch/upgrade ::test-scoped "2.0.0"
                     (swap! executions conj "2.0.0"))

      (testing "Scoped store is used instead of global"
        (patch/with-store scoped-store
          (patch/apply ::test-scoped))

        (is (= "2.0.0" (patch/read-version scoped-store ::test-scoped)))
        (is (= "0" (patch/read-version global-store ::test-scoped))))

      (testing "Global store is used outside with-store"
        (reset! executions [])
        (patch/apply ::test-scoped)
        (is (= "2.0.0" (patch/read-version global-store ::test-scoped)))))))

(deftest installed-version-from-store-test
  (testing "installed-version can read from store dynamically"
    (let [store (patch/->AtomVersionStore (atom {::test-dynamic "1.5.0"}))
          executions (atom [])]
      (patch/current-version ::test-dynamic "3.0.0")
      (patch/installed-version ::test-dynamic (patch/read-version store ::test-dynamic))

      (patch/upgrade ::test-dynamic "2.0.0"
                     (swap! executions conj "2.0.0"))
      (patch/upgrade ::test-dynamic "3.0.0"
                     (swap! executions conj "3.0.0"))

      (testing "Reads 1.5.0 from store and migrates to 3.0.0"
        (patch/set-store! store)
        (patch/apply ::test-dynamic)
        (is (= ["2.0.0" "3.0.0"] @executions))
        (is (= "3.0.0" (patch/read-version store ::test-dynamic)))))))