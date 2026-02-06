(ns patcho.lifecycle-test
  "Tests for module lifecycle management."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [patcho.lifecycle :as lifecycle]))

;;; ============================================================================
;;; Test Fixtures
;;; ============================================================================

(defn reset-registry-fixture
  "Reset registry and lifecycle store before each test."
  [f]
  (lifecycle/reset-registry!)
  (lifecycle/reset-store!)  ; Reset to fresh in-memory store
  (f)
  (lifecycle/reset-registry!)
  (lifecycle/reset-store!))

(use-fixtures :each reset-registry-fixture)

;;; ============================================================================
;;; Registration Tests
;;; ============================================================================

(deftest register-module-test
  (testing "Register simple module"
    (let [start-called (atom false)
          stop-called (atom false)]
      (lifecycle/register-module! :test/simple
                                  {:start (fn [] (reset! start-called true))
                                   :stop (fn [] (reset! stop-called true))})

      (is (= [:test/simple] (lifecycle/registered-modules)))
      (is (= false @start-called))
      (is (= false @stop-called))))

  (testing "Register module with dependencies"
    (lifecycle/reset-registry!)  ; Clear previous registrations
    (lifecycle/register-module! :test/database
                                {:start (fn [] nil)
                                 :stop (fn [] nil)})

    (lifecycle/register-module! :test/cache
                                {:depends-on [:test/database]
                                 :start (fn [] nil)
                                 :stop (fn [] nil)})

    (is (= [:test/database :test/cache] (lifecycle/registered-modules)))
    ;; Note: :setup-complete? only included when lifecycle store is set
    (is (= {:depends-on [:test/database]
            :started? false
            :has-setup? false
            :has-cleanup? false}
           (lifecycle/module-info :test/cache))))

  (testing "Register module with setup"
    (lifecycle/reset-registry!)  ; Clear previous registrations
    (let [setup-called (atom false)]
      (lifecycle/register-module! :test/with-setup
                                  {:setup (fn [& args] (reset! setup-called args))
                                   :start (fn [] nil)
                                   :stop (fn [] nil)})

      (is (= {:has-setup? true}
             (select-keys (lifecycle/module-info :test/with-setup) [:has-setup?]))))))

;;; ============================================================================
;;; Start/Stop Tests
;;; ============================================================================

(deftest start-stop-test
  (testing "Start and stop simple module"
    (let [execution-order (atom [])]
      (lifecycle/register-module! :test/simple
                                  {:start (fn [] (swap! execution-order conj :started))
                                   :stop (fn [] (swap! execution-order conj :stopped))})

      (lifecycle/start! :test/simple)
      (is (= [:started] @execution-order))
      (is (= [:test/simple] (lifecycle/started-modules)))
      (is (true? (lifecycle/started? :test/simple)))

      (lifecycle/stop! :test/simple)
      (is (= [:started :stopped] @execution-order))
      (is (= [] (lifecycle/started-modules)))
      (is (false? (lifecycle/started? :test/simple)))))

  (testing "Start is idempotent"
    (let [start-count (atom 0)]
      (lifecycle/register-module! :test/idempotent
                                  {:start (fn [] (swap! start-count inc))
                                   :stop (fn [] nil)})

      (lifecycle/start! :test/idempotent)
      (lifecycle/start! :test/idempotent)
      (lifecycle/start! :test/idempotent)

      (is (= 1 @start-count))))

  (testing "Stop is idempotent"
    (let [stop-count (atom 0)]
      (lifecycle/register-module! :test/idempotent-stop
                                  {:start (fn [] nil)
                                   :stop (fn [] (swap! stop-count inc))})

      (lifecycle/start! :test/idempotent-stop)
      (lifecycle/stop! :test/idempotent-stop)
      (lifecycle/stop! :test/idempotent-stop)
      (lifecycle/stop! :test/idempotent-stop)

      (is (= 1 @stop-count)))))

;;; ============================================================================
;;; Dependency Tests
;;; ============================================================================

(deftest dependency-validation-test
  (testing "start! recursively starts dependencies"
    (let [start-order (atom [])]
      (lifecycle/register-module! :test/database-dep
                                  {:start (fn [] (swap! start-order conj :database))
                                   :stop (fn [] nil)})

      (lifecycle/register-module! :test/cache-dep
                                  {:depends-on [:test/database-dep]
                                   :start (fn [] (swap! start-order conj :cache))
                                   :stop (fn [] nil)})

      ;; Start cache directly - should auto-start database first
      (lifecycle/start! :test/cache-dep)

      (is (= [:database :cache] @start-order))
      (is (= true (lifecycle/started? :test/database-dep)))
      (is (= true (lifecycle/started? :test/cache-dep)))))

  (testing "Can start modules in any order (recursive)"
    (lifecycle/register-module! :test/db-any
                                {:start (fn [] nil)
                                 :stop (fn [] nil)})

    (lifecycle/register-module! :test/app-any
                                {:depends-on [:test/db-any]
                                 :start (fn [] nil)
                                 :stop (fn [] nil)})

    ;; Can start in any order - dependencies started automatically
    (lifecycle/start! :test/app-any)

    (is (= true (lifecycle/started? :test/db-any)))
    (is (= true (lifecycle/started? :test/app-any))))

  (testing "Missing dependency throws error"
    (lifecycle/register-module! :test/broken
                                {:depends-on [:test/nonexistent]
                                 :start (fn [] nil)
                                 :stop (fn [] nil)})

    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Missing module dependencies"
          (lifecycle/start! :test/broken)))))

;;; ============================================================================
;;; Setup Tests
;;; ============================================================================

(deftest setup-test
  (testing "Setup multiple topics at once"
    (let [setup-calls (atom [])]
      (lifecycle/register-module! :test/setup1
                                  {:setup (fn [] (swap! setup-calls conj :setup1))
                                   :start (fn [] nil)
                                   :stop (fn [] nil)})
      (lifecycle/register-module! :test/setup2
                                  {:setup (fn [] (swap! setup-calls conj :setup2))
                                   :start (fn [] nil)
                                   :stop (fn [] nil)})
      (lifecycle/register-module! :test/setup3
                                  {:setup (fn [] (swap! setup-calls conj :setup3))
                                   :start (fn [] nil)
                                   :stop (fn [] nil)})

      (lifecycle/setup! :test/setup1 :test/setup2 :test/setup3)

      (is (= [:setup1 :setup2 :setup3] @setup-calls))
      (is (= true (:setup-complete? (lifecycle/module-info :test/setup1))))
      (is (= true (:setup-complete? (lifecycle/module-info :test/setup2))))
      (is (= true (:setup-complete? (lifecycle/module-info :test/setup3))))))

  (testing "Setup is idempotent"
    (let [setup-count (atom 0)]
      (lifecycle/register-module! :test/setup-once
                                  {:setup (fn [] (swap! setup-count inc))
                                   :start (fn [] nil)
                                   :stop (fn [] nil)})

      (lifecycle/setup! :test/setup-once)
      (lifecycle/setup! :test/setup-once)
      (lifecycle/setup! :test/setup-once)

      (is (= 1 @setup-count))))

  (testing "Module without setup is a no-op (idempotent)"
    (lifecycle/register-module! :test/no-setup-idempotent
                                {:start (fn [] nil)
                                 :stop (fn [] nil)})

    ;; Calling setup! on module without setup function is safe (no-op)
    (lifecycle/setup! :test/no-setup-idempotent)

    ;; Module info shows setup-complete even though no setup function
    (is (= true (:setup-complete? (lifecycle/module-info :test/no-setup-idempotent)))))

  (testing "start! auto-runs setup if needed"
    (let [setup-count (atom 0)
          start-count (atom 0)]
      (lifecycle/register-module! :test/auto-setup
                                  {:setup (fn [] (swap! setup-count inc))
                                   :start (fn [] (swap! start-count inc))
                                   :stop (fn [] nil)})

      ;; Call start! without calling setup! first
      (lifecycle/start! :test/auto-setup)

      ;; Verify both setup and start were called
      (is (= 1 @setup-count) "Setup should be called automatically")
      (is (= 1 @start-count) "Start should be called")
      (is (true? (:setup-complete? (lifecycle/module-info :test/auto-setup))))
      (is (true? (lifecycle/started? :test/auto-setup)))

      ;; Stop and start again - setup should NOT run again (idempotent)
      (lifecycle/stop! :test/auto-setup)
      (lifecycle/start! :test/auto-setup)

      (is (= 1 @setup-count) "Setup should not run again (idempotent)")
      (is (= 2 @start-count) "Start should be called again")))

  (testing "start! without setup works for modules without setup function"
    (let [start-count (atom 0)]
      (lifecycle/register-module! :test/no-setup-needed
                                  {:start (fn [] (swap! start-count inc))
                                   :stop (fn [] nil)})

      ;; Start should work fine without setup function
      (lifecycle/start! :test/no-setup-needed)

      (is (= 1 @start-count))
      (is (true? (lifecycle/started? :test/no-setup-needed))))))

;;; ============================================================================
;;; Restart Tests
;;; ============================================================================

(deftest restart-test
  (testing "Restart stops then starts module"
    (let [execution-order (atom [])]
      (lifecycle/register-module! :test/restart
                                  {:start (fn [] (swap! execution-order conj :start))
                                   :stop (fn [] (swap! execution-order conj :stop))})

      (lifecycle/start! :test/restart)
      (reset! execution-order [])

      (lifecycle/restart! :test/restart)

      (is (= [:stop :start] @execution-order))
      (is (true? (lifecycle/started? :test/restart))))))

;;; ============================================================================
;;; Dependency Graph Tests
;;; ============================================================================

(deftest dependency-graph-test
  (testing "Generate dependency graph"
    (lifecycle/register-module! :test/database
                                {:start (fn [] nil)
                                 :stop (fn [] nil)})

    (lifecycle/register-module! :test/cache
                                {:depends-on [:test/database]
                                 :start (fn [] nil)
                                 :stop (fn [] nil)})

    (lifecycle/register-module! :test/api
                                {:depends-on [:test/cache]
                                 :start (fn [] nil)
                                 :stop (fn [] nil)})

    (let [graph (lifecycle/dependency-graph)]
      (is (= {:depends-on []
              :dependents [:test/cache]}
             (get graph :test/database)))
      (is (= {:depends-on [:test/database]
              :dependents [:test/api]}
             (get graph :test/cache)))
      (is (= {:depends-on [:test/cache]
              :dependents []}
             (get graph :test/api))))))
