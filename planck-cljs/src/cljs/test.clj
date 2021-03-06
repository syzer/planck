;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cljs.test
  (:require-macros [planck.test.macros])
  (:require [cljs.env :as env]
            [cljs.analyzer :as ana]
            [planck.test.ana-api :as ana-api]
            [planck.test.template]
            [planck.test.glue]))

;; =============================================================================
;; Assertion Macros

(defmacro is
  "Generic assertion macro.  'form' is any predicate test.
  'msg' is an optional message to attach to the assertion.

  Example: (is (= 4 (+ 2 2)) \"Two plus two should be 4\")
  Special forms:
  (is (thrown? c body)) checks that an instance of c is thrown from
  body, fails if not; then returns the thing thrown.
  (is (thrown-with-msg? c re body)) checks that an instance of c is
  thrown AND that the message on the exception matches (with
  re-find) the regular expression re."
  ([form] `(cljs.test/is ~form nil))
  ([form msg]
   `(planck.test.macros/try-expr ~planck.test.glue/*assert-expr* ~msg ~form)))

(defmacro are
  "Checks multiple assertions with a template expression.
  See clojure.template/do-template for an explanation of
  templates.
  Example: (are [x y] (= x y)
                2 (+ 1 1)
                4 (* 2 2))
  Expands to:
           (do (is (= 2 (+ 1 1)))
               (is (= 4 (* 2 2))))
  Note: This breaks some reporting features, such as line numbers."
  [argv expr & args]
  (if (or
        ;; (are [] true) is meaningless but ok
        (and (empty? argv) (empty? args))
        ;; Catch wrong number of args
        (and (pos? (count argv))
          (pos? (count args))
          (zero? (mod (count args) (count argv)))))
    `(planck.test.macros/do-template ~argv (is ~expr) ~@args)
    (throw (ex-info "The number of args doesn't match are's argv." {}))))

(defmacro testing
  "Adds a new string to the list of testing contexts.  May be nested,
  but must occur inside a test function (deftest)."
  ([string & body]
   `(do
      (cljs.test/update-current-env! [:testing-contexts] conj ~string)
      (try
        ~@body
        (finally
          (cljs.test/update-current-env! [:testing-contexts] rest))))))

;; =============================================================================
;; Defining Tests

(defmacro deftest
  "Defines a test function with no arguments.  Test functions may call
  other tests, so tests may be composed.  If you compose tests, you
  should also define a function named test-ns-hook; run-tests will
  call test-ns-hook instead of testing all vars.
  Note: Actually, the test body goes in the :test metadata on the var,
  and the real function (the value of the var) calls test-var on
  itself.
  When cljs.analyzer/*load-tests* is false, deftest is ignored."
  [name & body]
  (when ana/*load-tests*
    `(do
       (def ~(vary-meta name assoc :test `(fn [] ~@body))
         (fn [] (cljs.test/test-var (.-cljs$lang$var ~name))))
       (set! (.-cljs$lang$var ~name) (var ~name)))))

(defmacro async
  "Wraps body as a CPS function that can be returned from a test to
  continue asynchronously.  Binds done to a function that must be
  invoked once and from an async context after any assertions.
  (deftest example-with-timeout
    (async done
      (js/setTimeout (fn []
                       ;; make assertions in async context...
                       (done) ;; ...then call done
                       )
                     0)))"
  [done & body]
  `(reify
     cljs.test/IAsyncTest
     cljs.core/IFn
     (~'-invoke [_# ~done]
       ~@body)))

;; =============================================================================
;; Running Tests

(defn ns? [x]
  (and (seq? x) (= (first x) 'quote)))

(defmacro run-tests-block
  "Like test-vars, but returns a block for further composition and
  later execution."
  [env-or-ns & namespaces]
  (assert (every?
            (fn [[quote ns]] (and (= quote 'quote) (symbol? ns)))
            namespaces)
    "All arguments to run-tests must be quoted symbols")
  (let [is-ns (ns? env-or-ns)
        env (gensym "env")
        summary (gensym "summary")]
    `(let [~env ~(if is-ns
                   `(cljs.test/empty-env)
                   env-or-ns)
           ~summary (cljs.core/volatile!
                      {:test 0 :pass 0 :fail 0 :error 0
                       :type :summary})]
       (concat ~@(map
                   (fn [ns]
                     `(concat (cljs.test/test-ns-block ~env ~ns)
                        [(fn []
                           (cljs.core/vswap!
                             ~summary
                             (partial merge-with +)
                             (:report-counters
                               (cljs.test/get-and-clear-env!))))]))
                   (if is-ns
                     (concat [env-or-ns] namespaces)
                     namespaces))
         [(fn []
            (cljs.test/set-env! ~env)
            (cljs.test/do-report (deref ~summary))
            (cljs.test/report (assoc (deref ~summary) :type :end-run-tests))
            (cljs.test/clear-env!))]))))

(defmacro run-tests
  "Runs all tests in the given namespaces; prints results.
  Defaults to current namespace if none given. Does not return a meaningful
  value due to the possiblity of asynchronous execution. To detect test
  completion add a :end-run-tests method case to the cljs.test/report
  multimethod."
  ([] `(run-tests (cljs.test/empty-env) '~ana/*cljs-ns*))
  ([env-or-ns]
   (if (ns? env-or-ns)
     `(run-tests (cljs.test/empty-env) ~env-or-ns)
     `(run-tests ~env-or-ns '~ana/*cljs-ns*)))
  ([env-or-ns & namespaces]
   `(cljs.test/run-block (run-tests-block ~env-or-ns ~@namespaces))))

(defmacro run-all-tests
  "Runs all tests in all namespaces; prints results.
  Optional argument is a regular expression; only namespaces with
  names matching the regular expression (with re-matches) will be
  tested."
  ([] `(cljs.test/run-all-tests nil (cljs.test/empty-env)))
  ([re] `(cljs.test/run-all-tests ~re (cljs.test/empty-env)))
  ([re env]
   `(cljs.test/run-tests ~env
      ~@(map
          (fn [ns] `(quote ~ns))
          (cond->> (ana-api/all-ns)
            re (filter #(re-matches re (name %))))))))

(defmacro test-all-vars-block
  ([[quote ns]]
   `(let [env# (cljs.test/get-current-env)]
      (concat
        [(fn []
           (when (nil? env#)
             (cljs.test/set-env! (cljs.test/empty-env)))
           ~(when (ana-api/ns-resolve ns 'cljs-test-once-fixtures)
              `(cljs.test/update-current-env! [:once-fixtures] assoc '~ns
                 ~(symbol (name ns) "cljs-test-once-fixtures")))
           ~(when (ana-api/ns-resolve ns 'cljs-test-each-fixtures)
              `(cljs.test/update-current-env! [:each-fixtures] assoc '~ns
                 ~(symbol (name ns) "cljs-test-each-fixtures"))))]
        (cljs.test/test-vars-block
          [~@(->> (ana-api/ns-interns ns)
               (filter (fn [[_ v]] (:test v)))
               (sort-by (fn [[_ v]] (:line v)))
               (map (fn [[k _]]
                      `(var ~(symbol (name ns) (name k))))))])
        [(fn []
           (when (nil? env#)
             (cljs.test/clear-env!)))]))))

(defmacro test-all-vars
  "Calls test-vars on every var with :test metadata interned in the
  namespace, with fixtures."
  [[quote ns :as form]]
  `(cljs.test/run-block
     (concat (test-all-vars-block ~form)
       [(fn []
          (cljs.test/report {:type :end-test-all-vars :ns ~form}))])))

(defmacro test-ns-block
  "Like test-ns, but returns a block for further composition and
  later execution.  Does not clear the current env."
  ([env [quote ns :as form]]
   (assert (and (= quote 'quote) (symbol? ns)) "Argument to test-ns must be a quoted symbol")
   (assert (ana-api/find-ns ns) (str "Namespace " ns " does not exist"))
   `[(fn []
       (cljs.test/set-env! ~env)
       (cljs.test/do-report {:type :begin-test-ns, :ns ~form})
       ;; If the namespace has a test-ns-hook function, call that:
       ~(if-let [v (ana-api/ns-resolve ns 'test-ns-hook)]
          `(~(symbol (name ns) "test-ns-hook"))
          ;; Otherwise, just test every var in the namespace.
          `(cljs.test/block (cljs.test/test-all-vars-block ~form))))
     (fn []
       (cljs.test/do-report {:type :end-test-ns, :ns ~form}))]))

(defmacro test-ns
  "If the namespace defines a function named test-ns-hook, calls that.
  Otherwise, calls test-all-vars on the namespace.  'ns' is a
  namespace object or a symbol.
  Internally binds *report-counters* to a ref initialized to
  *initial-report-counters*.  "
  ([ns] `(cljs.test/test-ns (cljs.test/empty-env) ~ns))
  ([env [quote ns :as form]]
   `(cljs.test/run-block
      (concat (cljs.test/test-ns-block ~env ~form)
        [(fn []
           (cljs.test/clear-env!))]))))

;; =============================================================================
;; Fixes

(defmacro use-fixtures [type & fns]
  (condp = type
    :once
    `(def ~'cljs-test-once-fixtures
       [~@fns])
    :each
    `(def ~'cljs-test-each-fixtures
       [~@fns])
    :else
    (throw
      (ex-info "First argument to cljs.test/use-fixtures must be :once or :each" {}))))
