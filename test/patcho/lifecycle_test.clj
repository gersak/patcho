(ns patcho.lifecycle-test
  "Tests for module lifecycle management."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [patcho.lifecycle :as lifecycle]))

;;; ============================================================================
;;; Test Fixtures
;;; ============================================================================

(defn reset-registry-fixture [f]
  "Reset registry before each test."
  (lifecycle/reset-registry!)
  (f)
  (lifecycle/reset-registry!))

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
    (lifecycle/register-module! :test/database
                                {:start (fn [] nil)
                                 :stop (fn [] nil)})

    (lifecycle/register-module! :test/cache
                                {:depends-on [:test/database]
                                 :start (fn [] nil)
                                 :stop (fn [] nil)})

    (is (= [:test/database :test/cache] (lifecycle/registered-modules)))
    (is (= {:depends-on [:test/database]
            :started? false
            :setup-complete? false
            :has-setup? false}
           (lifecycle/module-info :test/cache))))

  (testing "Register module with setup"
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
  (testing "Cannot start module with unstarted dependency"
    (lifecycle/register-module! :test/database
                                {:start (fn [] nil)
                                 :stop (fn [] nil)})

    (lifecycle/register-module! :test/cache
                                {:depends-on [:test/database]
                                 :start (fn [] nil)
                                 :stop (fn [] nil)})

    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Dependency not started"
         (lifecycle/start! :test/cache))))

  (testing "Can start module after dependency started"
    (lifecycle/register-module! :test/db
                                {:start (fn [] nil)
                                 :stop (fn [] nil)})

    (lifecycle/register-module! :test/app
                                {:depends-on [:test/db]
                                 :start (fn [] nil)
                                 :stop (fn [] nil)})

    (lifecycle/start! :test/db)
    (lifecycle/start! :test/app)

    (is (= [:test/db :test/app] (lifecycle/started-modules))))

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
;;; Topological Sort Tests
;;; ============================================================================

(deftest start-all-test
  (testing "Start all modules in dependency order"
    (let [execution-order (atom [])]
      ;; Register modules in random order
      (lifecycle/register-module! :test/cache
                                  {:depends-on [:test/database]
                                   :start (fn [] (swap! execution-order conj :cache))
                                   :stop (fn [] nil)})

      (lifecycle/register-module! :test/api
                                  {:depends-on [:test/cache]
                                   :start (fn [] (swap! execution-order conj :api))
                                   :stop (fn [] nil)})

      (lifecycle/register-module! :test/database
                                  {:start (fn [] (swap! execution-order conj :database))
                                   :stop (fn [] nil)})

      ;; Start all - should be in dependency order
      (lifecycle/start-all!)

      (is (= [:database :cache :api] @execution-order))
      (is (= [:test/database :test/cache :test/api]
             (lifecycle/started-modules)))))

  (testing "Circular dependency detection"
    (lifecycle/register-module! :test/a
                                {:depends-on [:test/b]
                                 :start (fn [] nil)
                                 :stop (fn [] nil)})

    (lifecycle/register-module! :test/b
                                {:depends-on [:test/a]
                                 :start (fn [] nil)
                                 :stop (fn [] nil)})

    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Circular dependency"
         (lifecycle/start-all!)))))

(deftest stop-all-test
  (testing "Stop all modules in reverse dependency order"
    (let [execution-order (atom [])]
      ;; Register chain: database → cache → api
      (lifecycle/register-module! :test/database
                                  {:start (fn [] nil)
                                   :stop (fn [] (swap! execution-order conj :database))})

      (lifecycle/register-module! :test/cache
                                  {:depends-on [:test/database]
                                   :start (fn [] nil)
                                   :stop (fn [] (swap! execution-order conj :cache))})

      (lifecycle/register-module! :test/api
                                  {:depends-on [:test/cache]
                                   :start (fn [] nil)
                                   :stop (fn [] (swap! execution-order conj :api))})

      (lifecycle/start-all!)
      (lifecycle/stop-all!)

      ;; Should stop in reverse order
      (is (= [:api :cache :database] @execution-order))
      (is (= [] (lifecycle/started-modules))))))

;;; ============================================================================
;;; Setup Tests
;;; ============================================================================

(deftest setup-test
  (testing "Setup function is called with arguments"
    (let [setup-args (atom nil)]
      (lifecycle/register-module! :test/setup
                                  {:setup (fn [& args] (reset! setup-args args))
                                   :start (fn [] nil)
                                   :stop (fn [] nil)})

      (lifecycle/setup! :test/setup :arg1 :arg2 :arg3)

      (is (= [:arg1 :arg2 :arg3] @setup-args))
      (is (= true (:setup-complete? (lifecycle/module-info :test/setup))))))

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

  (testing "Module without setup throws error"
    (lifecycle/register-module! :test/no-setup
                                {:start (fn [] nil)
                                 :stop (fn [] nil)})

    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Module has no setup function"
         (lifecycle/setup! :test/no-setup)))))

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
                                {:start (fn [] nil) :stop (fn [] nil)})

    (lifecycle/register-module! :test/cache
                                {:depends-on [:test/database]
                                 :start (fn [] nil) :stop (fn [] nil)})

    (lifecycle/register-module! :test/api
                                {:depends-on [:test/cache]
                                 :start (fn [] nil) :stop (fn [] nil)})

    (let [graph (lifecycle/dependency-graph)]
      (is (= {:depends-on [] :dependents [:test/cache]}
             (get graph :test/database)))
      (is (= {:depends-on [:test/database] :dependents [:test/api]}
             (get graph :test/cache)))
      (is (= {:depends-on [:test/cache] :dependents []}
             (get graph :test/api))))))
