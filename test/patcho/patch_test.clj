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
    (patch/previous-version ::test-deployed "2.0.0")

    (is (= "2.0.0" (patch/deployed-version ::test-deployed)))
    (is (= "3.0.0" (patch/version ::test-deployed)))))

(deftest single-arity-apply-test
  (testing "Single arity apply uses deployed-version"
    (let [executions (atom [])]
      (patch/current-version ::test-single "2.0.0")
      (patch/previous-version ::test-single "1.0.0")

      (patch/upgrade ::test-single "2.0.0"
                     (swap! executions conj "2.0.0"))

      ; Should migrate from deployed-version (1.0.0) to current-version (2.0.0)
      (patch/apply ::test-single)
      (is (= ["2.0.0"] @executions)))))