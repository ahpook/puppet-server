(ns puppetlabs.master.services.jruby.jruby-puppet-service-test
  (:import (java.util.concurrent ExecutionException)
           (com.puppetlabs.master JRubyPuppet))
  (:require [clojure.test :refer :all]
            [puppetlabs.master.services.protocols.jruby-puppet :as jruby-protocol]
            [puppetlabs.master.services.jruby.jruby-puppet-core :as jruby-puppet-core]
            [puppetlabs.master.services.jruby.testutils :as testutils]
            [puppetlabs.master.services.jruby.jruby-puppet-service :refer :all]
            [puppetlabs.trapperkeeper.app :as app]
            [puppetlabs.trapperkeeper.core :as tk]
            [clojure.stacktrace :as stacktrace]
            [puppetlabs.kitchensink.testutils.fixtures :as ks-fixtures]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as bootstrap]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]))

(use-fixtures :each testutils/mock-jruby-fixture)

(def jruby-service-test-config
  {:jruby-puppet (testutils/jruby-puppet-config-with-prod-env 1)})

(deftest test-error-during-init
  (testing
      (str "If there as an exception while putting a JRubyPuppet instance in "
           "the pool the application should shut down.")
    (logging/with-test-logging
      (with-redefs [jruby-puppet-core/create-jruby-instance
                    (fn [_] (throw (Exception. "42")))]
                   (bootstrap/with-app-with-config
                     app
                     [jruby-puppet-pooled-service]
                     jruby-service-test-config
                     (let [got-expected-exception (atom false)
                           main-thread (future (tk/run-app app))]
                       (try
                         @main-thread
                         (catch Exception e
                           (let [cause (stacktrace/root-cause e)]
                             (is (= (.getMessage cause) "42"))
                             (reset! got-expected-exception true))))
                       (is (true? @got-expected-exception))
                       (is (logged? #"^shutdown-on-error triggered because of exception!"
                                    :error))))))))

(deftest test-pool-size
  (testing "The pool is created and the size is correctly reported"
    (let [pool-size 2]
      (bootstrap/with-app-with-config
        app
        [jruby-puppet-pooled-service]
        {:jruby-puppet (testutils/jruby-puppet-config-with-prod-env pool-size)}
        (let [service (app/get-service app :JRubyPuppetService)
              all-the-instances
              (mapv (fn [_] (jruby-protocol/borrow-instance
                              service testutils/prod-pool-descriptor))
                    (range pool-size))]
          (is (= 0 (jruby-protocol/free-instance-count
                     service testutils/prod-pool-descriptor)))
          (is (= pool-size (count all-the-instances)))
          (doseq [instance all-the-instances]
            (is (not (nil? instance))
                "One of the JRubyPuppet instances retrieved from the pool is nil")
            (jruby-protocol/return-instance
              service testutils/prod-pool-descriptor instance))
          (is (= pool-size (jruby-protocol/free-instance-count
                             service testutils/prod-pool-descriptor))))))))

(deftest test-pool-population-during-init
  (testing "A JRuby instance can be borrowed from the 'init' phase of a service"
    (let [test-service (tk/service
                         [[:JRubyPuppetService borrow-instance return-instance]]
                         (init [this context]
                               (return-instance
                                 testutils/prod-pool-descriptor
                                 (borrow-instance testutils/prod-pool-descriptor))
                               context))]

      ; Bootstrap TK, causing the 'init' function above to be executed.
      (tk/boot-services-with-config
        [test-service jruby-puppet-pooled-service]
        jruby-service-test-config)

      ; If execution gets here, the test passed.
      (is (true? true)))))

(deftest test-with-jruby-puppet
  (testing "the `with-jruby-puppet macro`"
    (bootstrap/with-app-with-config
      app
      [jruby-puppet-pooled-service]
      jruby-service-test-config
      (let [service (app/get-service app :JRubyPuppetService)]
        (with-jruby-puppet
          jruby-puppet
          service
          testutils/prod-pool-descriptor
          (is (instance? JRubyPuppet jruby-puppet))
          (is (= 0 (jruby-protocol/free-instance-count
                     service testutils/prod-pool-descriptor))))
        (is (= 1 (jruby-protocol/free-instance-count
                   service testutils/prod-pool-descriptor)))))))