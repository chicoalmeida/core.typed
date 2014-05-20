(ns ^:skip-wiki 
  ^{:core.typed {:collect-only true}}
  clojure.core.typed.check
  (:refer-clojure :exclude [defrecord])
  (:require [clojure.core.typed :as t :refer [*already-checked* letfn>]]
            [clojure.core.typed.utils :as u :refer [expr-type]]
            [clojure.core.typed.ast-utils :as ast-u]
            [clojure.core.typed.errors :as err]
            [clojure.core.typed.coerce-utils :as coerce]
            [clojure.core.typed.contract-utils :as con]
            [clojure.core.typed.check.utils :as cu]
            [clojure.core.typed.check.case :as case]
            [clojure.core.typed.check.value :as value]
            [clojure.core.typed.check.map :as map]
            [clojure.core.typed.check.if :as if]
            [clojure.core.typed.check.funapp :as funapp]
            [clojure.core.typed.check.invoke-kw :as invoke-kw]
            [clojure.core.typed.open-result :as open-result]
            [clojure.core.typed.update :as update]
            [clojure.core.typed.parse-unparse :as prs]
            [clojure.core.typed.current-impl :as impl]
            [clojure.core.typed.type-rep :as r :refer [ret-t ret-f ret-o ret TCResult? Type?]]
            [clojure.core.typed.type-ctors :as c]
            [clojure.core.typed.object-rep :as obj]
            [clojure.core.typed.filter-protocols :as fprotocol]
            [clojure.core.typed.filter-rep :as fl]
            [clojure.core.typed.filter-ops :as fo]
            [clojure.core.typed.path-rep :as pe]
            [clojure.core.typed.lex-env :as lex]
            [clojure.core.typed.constant-type :as const]
            [clojure.core.typed.util-vars :as vs]
            [clojure.core.typed.subtype :as sub]
            [clojure.core.typed.fold-rep :as fold]
            [clojure.core.typed.cs-rep :as crep]
            [clojure.core.typed.cs-gen :as cgen]
            [clojure.core.typed.subst :as subst]
            [clojure.core.typed.subst-obj :as subst-obj]
            [clojure.core.typed.frees :as frees]
            [clojure.core.typed.free-ops :as free-ops]
            [clojure.core.typed.tvar-env :as tvar-env]
            [clojure.core.typed.tvar-bnds :as tvar-bnds]
            [clojure.core.typed.dvar-env :as dvar-env]
            [clojure.core.typed.var-env :as var-env]
            [clojure.core.typed.protocol-env :as ptl-env]
            [clojure.core.typed.array-ops :as arr-ops]
            [clojure.core.typed.datatype-ancestor-env :as ancest]
            [clojure.core.typed.datatype-env :as dt-env]
            [clojure.core.typed.inst :as inst]
            [clojure.core.typed.mm-env :as mm]
            [clojure.core.typed.rclass-env :as rcls]
            [clojure.core.typed.protocol-env :as pcl-env]
            [clojure.core.typed.method-param-nilables :as mtd-param-nil]
            [clojure.core.typed.method-return-nilables :as mtd-ret-nil]
            [clojure.core.typed.method-override-env :as mth-override]
            [clojure.core.typed.ctor-override-env :as ctor-override]
            [clojure.core.typed.analyze-clj :as ana-clj]
            [clojure.core.typed.tc-equiv :as equiv]
            [clojure.tools.analyzer.ast :as ast-ops]
            [clojure.core.typed.ns-deps :as ns-deps]
            [clojure.core.typed.ns-options :as ns-opts]
            [clojure.jvm.tools.analyzer.hygienic :as hygienic]
            [clojure.pprint :as pprint]
            [clojure.math.combinatorics :as comb]
            [clojure.repl :as repl]
            [clojure.string :as str]
            [clojure.set :as set])
  (:import (clojure.core.typed.type_rep Function FnIntersection RClass Poly DottedPretype HeterogeneousSeq
                                        Value KwArgs HeterogeneousMap DataType TCResult HeterogeneousVector
                                        FlowSet Union)
           (clojure.core.typed.object_rep Path)
           (clojure.core.typed.filter_rep NotTypeFilter TypeFilter FilterSet AndFilter OrFilter)
           (clojure.lang APersistentMap IPersistentMap IPersistentSet Var Seqable ISeq
                         PersistentHashSet)))

(alter-meta! *ns* assoc :skip-wiki true)

(t/ann ^:no-check clojure.core.typed.parse-unparse/*unparse-type-in-ns* (U nil t/Sym))
(t/ann ^:no-check clojure.core.typed/*already-checked* (U nil (t/Atom1 (t/Vec t/Sym))))

;==========================================================
; # Type Checker
;
; The type checker is implemented here.

(t/ann error-ret [(U nil TCResult) -> TCResult])
(defn error-ret 
  "Return a TCResult appropriate for when a type
  error occurs, with expected type expected.
  
  Use *only* in case of a type error."
  [expected]
  (ret (or (when expected
             (ret-t expected))
           (r/TCError-maker))))

(declare check-expr)

(t/ann checked-ns! [t/Sym -> nil])
(defn- checked-ns! [nsym]
  (t/when-let-fail [a *already-checked*]
    (swap! a conj nsym))
  nil)

(t/ann already-checked? [t/Sym -> Boolean])
(defn- already-checked? [nsym]
  (t/when-let-fail [a *already-checked*]
    (boolean (@a nsym))))


(t/ann check-ns-and-deps [t/Sym -> nil])
(defn check-ns-and-deps 
  "Type check a namespace and its dependencies.
  Assumes type annotations in each namespace
  has already been collected."
  ([nsym]
   {:pre [(symbol? nsym)]
    :post [(nil? %)]}
   (u/p :check/check-ns-and-deps
   (let [ns (find-ns nsym)
         _ (assert ns (str "Namespace " nsym " not found during type checking"))]
     (cond 
       (already-checked? nsym) (do
                                 ;(println (str "Already checked " nsym ", skipping"))
                                 ;(flush)
                                 nil)
       :else
       ; check deps
       (let [deps (u/p :check/ns-immediate-deps 
                    (ns-deps/immediate-deps nsym))]
         (checked-ns! nsym)
         (doseq [dep deps]
           (check-ns-and-deps dep))
         ; ignore ns declaration
         (let [check? (not (-> ns meta :core.typed :collect-only))]
           (if-not check?
             (do (println (str "Not checking " nsym " (tagged :collect-only in ns metadata)"))
                 (flush))
             (let [start (. System (nanoTime))
                   asts (u/p :check/gen-analysis (ana-clj/ast-for-ns nsym))
                   _ (println "Start checking" nsym)
                   _ (flush)
                   casts (doall
                           (for [ast asts]
                             (check-expr ast)))
                   _ (when-let [checked-asts t/*checked-asts*]
                       (swap! checked-asts assoc nsym casts))
                   _ (println "Checked" nsym "in" (/ (double (- (. System (nanoTime)) start)) 1000000.0) "msecs")
                   _ (flush)
                   ]
         nil)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Checker

(defmulti check (fn [expr & [expected]]
                  {:pre [((some-fn nil? TCResult?) expected)]}
                  (:op expr)))

(u/add-defmethod-generator check)

(defn check-expr [expr & [expected]]
  (when t/*trace-checker*
    (println "Checking line:" (-> expr :env :line))
    (flush))
  (u/p :check/check-expr
  (let [expr (check expr expected)]
    (when expected
      (when-not (sub/subtype? (-> expr expr-type ret-t)
                              (-> expected ret-t))
        (cu/expected-error (-> expr expr-type ret-t)
                        (-> expected ret-t))))
    expr)))

(add-check-method :const [expr & [expected]] 
  (value/check-value expr expected))

(add-check-method :quote [{:keys [expr] :as quote-expr} & [expected]] 
  (let [cexpr (check expr expected)]
    (assoc quote-expr
           :expr cexpr
           expr-type (expr-type cexpr))))

(add-check-method :map
  [{keyexprs :keys valexprs :vals :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)
          (vector? (:keys %))
          (vector? (:vals %))]}
  (let [ckeyexprs (mapv check keyexprs)
        key-types (map (comp ret-t expr-type) ckeyexprs)

        val-rets
        (map/expected-vals key-types expected)

        cvalexprs (mapv check valexprs val-rets)
        val-types (map (comp ret-t expr-type) cvalexprs)

        ts (zipmap key-types val-types)
        actual (if (every? c/keyword-value? (keys ts))
                 (c/-complete-hmap ts)
                 (c/RClass-of APersistentMap [(apply c/Un (keys ts))
                                              (apply c/Un (vals ts))]))
        _ (when expected
            (when-not (sub/subtype? actual (ret-t expected))
              (cu/expected-error actual (ret-t expected))))]
    (assoc expr
           :keys ckeyexprs
           :vals cvalexprs
           expr-type (ret actual (fo/-true-filter)))))

(add-check-method :set
  [{:keys [items] :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)
          (vector? (:args %))]}
  (let [cargs (mapv check items)
        res-type (c/RClass-of PersistentHashSet [(apply c/Un (mapv (comp ret-t expr-type) cargs))])
        _ (when (and expected (not (sub/subtype? res-type (ret-t expected))))
            (cu/expected-error res-type (ret-t expected)))]
    (assoc expr
           :args cargs
           expr-type (ret res-type (fo/-true-filter)))))

(add-check-method :vector
  [{:keys [items] :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)
          (vector? (:args %))]}
  (let [cargs (mapv check items)
        res-type (r/-hvec (mapv (comp ret-t expr-type) cargs)
                          :filters (mapv (comp ret-f expr-type) cargs)
                          :objects (mapv (comp ret-o expr-type) cargs))
        _ (when (and expected (not (sub/subtype? res-type (ret-t expected))))
            (cu/expected-error res-type (ret-t expected)))]
    (assoc expr
           :args cargs
           expr-type (ret res-type (fo/-true-filter)))))

(add-check-method :var
  [{:keys [var] :as expr} & [expected]]
  {:pre [(var? var)]}
  (binding [vs/*current-expr* expr]
    (let [id (coerce/var->symbol var)
          _ (when-not (var-env/used-var? id)
              (var-env/add-used-var id))
          t (var-env/lookup-Var-nofail (coerce/var->symbol var))]
      (if t
        (assoc expr
               expr-type (ret t (fo/-FS fl/-top fl/-top) obj/-empty))
        (err/tc-delayed-error
          (str "Unannotated var " id
               "\nHint: Add the annotation for " id
               " via check-ns or cf")
          :return (assoc expr
                         expr-type 
                         (ret (or (when expected
                                    (ret-t expected))
                                  (r/TCError-maker))
                              (fo/-FS fl/-top fl/-top) 
                              obj/-empty)))))))

(add-check-method :the-var
  [{:keys [^Var var env] :as expr} & [expected]]
  {:pre [(var? var)]}
  (let [id (coerce/var->symbol var)
        macro? (.isMacro var)
        _ (when-not (or macro?
                        (var-env/used-var? id))
            (var-env/add-used-var id))
        t (var-env/lookup-Var-nofail id)
        t (cond
            t t
            macro? r/-any
            :else (err/tc-delayed-error (str "Untyped var reference: " id
                                           "\nHint: Add the annotation for " id
                                           " via check-ns or cf")
                                      :form (ast-u/emit-form-fn expr)
                                      :return (r/TCError-maker)))]
    (assoc expr
           expr-type (ret (c/RClass-of Var [t t])
                          (fo/-true-filter)
                          obj/-empty))))


(comment
  (equiv/tc-equiv (ret (r/-val :if))
            (ret (prs/parse-type '(U ':if ':case))
                 ))

  )

(defmulti invoke-special (fn [{fexpr :fn :keys [op] :as expr} & args] 
                           {:pre [(#{:invoke} op)]
                            :post [((some-fn nil? symbol?) %)]}
                           (when (#{:var} (:op fexpr))
                             (when-let [var (:var fexpr)]
                               (coerce/var->symbol var)))))
(u/add-defmethod-generator invoke-special)

(defmulti invoke-apply (fn [{[fexpr] :args :keys [op] :as expr} & args]
                         {:pre [(#{:invoke} op)]
                          :post [((some-fn nil? symbol?) %)]}
                         (when (#{:var} (:op fexpr))
                           (when-let [var (:var fexpr)]
                             (coerce/var->symbol var)))))
(u/add-defmethod-generator invoke-apply)

(defmulti static-method-special (fn [expr & args]
                                  {:post [((some-fn nil? symbol?) %)]}
                                  (cu/MethodExpr->qualsym expr)))
(defmulti instance-method-special (fn [expr & args]
                                    {:post [((some-fn nil? symbol?) %)]}
                                    (cu/MethodExpr->qualsym expr)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Keyword lookups

;[Type TCResult -> Type]
(defn- extend-method-expected 
  "Returns the expected type with target-type intersected with the first argument"
  [target-type expected]
  {:pre [(Type? target-type)
         (Type? expected)]
   :post [(Type? %)]}
  (cond
    (r/FnIntersection? expected)
    (-> expected
        (update-in [:types] #(for [ftype %]
                               (do
                                 (assert (<= 1 (count (:dom ftype))))
                                 (-> ftype
                                     (update-in [:dom] (fn [dom] 
                                                         (update-in (vec dom) [0] (partial c/In target-type)))))))))

    (r/Poly? expected)
    (let [names (c/Poly-fresh-symbols* expected)
          body (c/Poly-body* names expected)
          body (extend-method-expected target-type body)]
      (c/Poly* names 
               (c/Poly-bbnds* names expected)
               body))

    (r/PolyDots? expected)
    (let [names (c/PolyDots-fresh-symbols* expected)
          body (c/PolyDots-body* names expected)
          body (extend-method-expected target-type body)]
      (c/PolyDots* names 
                   (c/PolyDots-bbnds* names expected)
                   body))
    :else (err/int-error (str "Expected Function type, found " (prs/unparse-type expected)))))

; only handle special case that the first argument is literal class
(add-invoke-special-method 'clojure.core/cast
  [{:keys [args] :as expr} & [expected]]
  {:post [(or (#{:default} %)
              (and (TCResult? (expr-type %))
                   (vector? (:args %))))]}
  (when-not (#{2} (count args))
    (err/int-error (str "Wrong number of arguments to clojure.core/cast,"
                      " expected 2, given " (count args))))
  (let [cargs (mapv check args)
        ct (-> (first cargs) expr-type ret-t c/fully-resolve-type)]
    (if (and (r/Value? ct) (class? (:val ct)))
      (let [v-t (-> (check (second args)) expr-type ret-t)
            t (c/In v-t (c/RClass-of-with-unknown-params (:val ct)))
            _ (when (and t expected)
                (when-not (sub/subtype? t (ret-t expected))
                  (cu/expected-error t (ret-t expected))))]
        (-> expr
            (update-in [:fn] check)
            (assoc :args cargs
                   expr-type (ret t))))
      :default)))

(declare normal-invoke)

(defn quote-expr-val [{:keys [op expr] :as q}]
  {:pre [(or (and (#{:quote} op)
                  (#{:const} (:op expr)))
             (#{:const} op))]}
  (if (#{:quote} op)
    (:val expr)
    (:val q)))

(add-invoke-special-method 'clojure.core.typed/var>*
  [{[sym-expr :as args] :args fexpr :fn :as expr} & [expected]]
  {:post [(and (TCResult? (expr-type %))
               (vector? (:args %)))]}
  (when-not (#{1} (count args))
    (err/int-error (str "Wrong number of arguments to clojure.core.typed/var>,"
                      " expected 1, given " (count args))))
  (let [sym (quote-expr-val sym-expr)
        _ (assert (symbol? sym))
        t (var-env/lookup-Var-nofail sym)
        _ (when-not t
            (err/tc-delayed-error (str "Unannotated var: " sym)))
        _ (when (and t expected)
            (when-not (sub/subtype? t (ret-t expected))
              (cu/expected-error t (ret-t expected))))]
    (-> expr
        ; var>* is internal, don't check
        #_(update-in [:fn] check)
        (assoc expr-type (ret (or t (r/TCError-maker)))))))

; ignore some keyword argument related intersections
(add-invoke-special-method 'clojure.core/seq?
  [{fexpr :fn :keys [args] :as expr} & [expected]]
  {:post [(and (TCResult? (expr-type %))
               (vector? (:args %)))]}
  (when-not (#{1} (count args))
    (err/int-error (str "Wrong number of arguments to clojure.core/seq?,"
                      " expected 1, given " (count args))))
  (let [cfexpr (check fexpr)
        [ctarget :as cargs] (mapv check args)]
    (cond 
      ; handle keyword args macroexpansion
      (r/KwArgsSeq? (-> ctarget expr-type ret-t))
      (assoc expr
             :fn cfexpr
             :args cargs
             expr-type (ret r/-true (fo/-true-filter)))
      ; records never extend ISeq
      (r/Record? (-> ctarget expr-type ret-t c/fully-resolve-type))
      (assoc expr
             :fn cfexpr
             :args cargs
             expr-type (ret r/-false (fo/-false-filter)))
      :else (normal-invoke expr fexpr args expected
                           :cargs cargs))))

(add-invoke-special-method 'clojure.core/extend
  [{[atype & protos :as args] :args :as expr} & [expected]]
  {:post [(and (TCResult? (expr-type %))
               (vector? (:args %)))]}
  (when-not (and atype (even? (count protos))) 
    (err/int-error "Wrong number of arguments to extend, expected at least one with an even "
                 "number of variable arguments, given " (count args)))
  (let [catype (check atype)
        ret-expr (-> expr
                     ; don't check extend
                     ;(update-in [:fn] check)
                     (assoc expr-type (ret r/-nil)))
        ; this is a Value type containing a java.lang.Class instance representing
        ; the type extending the protocol, or (Value nil) if extending to nil
        target-literal-class (ret-t (expr-type catype))]
    (cond
      (not (and (r/Value? target-literal-class)
                ((some-fn class? nil?) (:val target-literal-class))))
      (err/tc-delayed-error
        (str "Must provide a Class or nil as first argument to extend, "
             "got " (pr-str (prs/unparse-type target-literal-class)))
        :return ret-expr)

      (and expected (not (sub/subtype? r/-any (ret-t expected))))
      (do (cu/expected-error r/-any (ret-t expected))
          ret-expr)
      :else
      (let [; this is the actual core.typed type of the thing extending the protocol
            target-type (let [v (:val target-literal-class)]
                          (if (nil? v)
                            r/-nil
                            (c/RClass-of-with-unknown-params v)))

            ; build expected types for each method map
            extends (for [[prcl-expr mmap-expr] (partition 2 protos)]
                      (let [protocol (do (when-not (= :var (:op prcl-expr))
                                           (err/int-error  "Must reference protocol directly with var in extend"))
                                         (ptl-env/resolve-protocol (coerce/var->symbol (:var prcl-expr))))
                            expected-mmap (c/make-HMap ;get all combinations
                                                       :optional
                                                       (into {}
                                                             (for [[msym mtype] (:methods protocol)]
                                                               [(r/-val (keyword (name msym))) 
                                                                (extend-method-expected target-type mtype)])))]
                        {:expected-hmap expected-mmap
                         :prcl-expr prcl-expr
                         :mmap-expr mmap-expr}))
            cargs (vec
                    (cons catype
                          (mapcat
                            (fn [{:keys [mmap-expr expected-hmap prcl-expr]}]
                              (let [cprcl-expr (check prcl-expr)
                                    cmmap-expr (check mmap-expr (ret expected-hmap))
                                    actual (-> cmmap-expr expr-type ret-t)]
                                [cprcl-expr cmmap-expr]))
                            extends)))
            _ (assert (== (count cargs)
                          (count args)))]
        (assoc ret-expr
               :args cargs)))))

;into-array>
;
; Usage: (into-array> javat cljt coll)
;        (into-array> cljt coll)
(add-invoke-special-method 'clojure.core.typed/into-array>*
  [{:keys [args] :as expr} & [expected]]
  {:post [(and (TCResult? (expr-type %))
               (vector? (:args %)))]}
  (when-not (#{2 3 4} (count args)) 
    (err/int-error "Wrong number of args to into-array>*"))
  (let [has-java-syn? (#{3 4} (count args))
        [javat-syn cljt-syn coll-expr]
        (cond 
          (= 3 (count args)) args
          (= 4 (count args)) (next args) ;handle temporary hacky case
          :else (cons nil args))

        javat (let [syn (or (when has-java-syn? (quote-expr-val javat-syn))  ; generalise javat-syn if provided, otherwise cljt-syn
                            (quote-expr-val cljt-syn))
                    c (-> 
                        (binding [prs/*parse-type-in-ns* (cu/expr-ns expr)]
                          (prs/parse-type syn))
                        arr-ops/Type->array-member-Class)]
                (assert (class? c))
                c)
        cljt (binding [prs/*parse-type-in-ns* (cu/expr-ns expr)]
               (prs/parse-type (quote-expr-val cljt-syn)))
        ccoll (check coll-expr (ret (c/Un r/-nil (c/RClass-of Seqable [cljt]))))]
    (-> expr
        ; into-array>* is internal, don't check it
        #_(update-in [:fn] check)
        ; the coll is always last
        (assoc :args (-> args pop (conj ccoll))
               expr-type (ret (r/PrimitiveArray-maker javat cljt cljt))))))

;not
(add-invoke-special-method 'clojure.core/not
  [{:keys [args] :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)
          (vector? (:args %))]}
  (when-not (= 1 (count args)) 
    (err/int-error "Wrong number of args to clojure.core/not"))
  (let [[ctarget :as cargs] (mapv check args)
        {fs+ :then fs- :else} (-> ctarget expr-type ret-f)]
    (assoc expr
           :args cargs
           expr-type (ret (prs/parse-type 'boolean) 
                          ;flip filters
                          (fo/-FS fs- fs+)
                          obj/-empty))))

(defn invoke-get [{:keys [args] :as expr} expected & {:keys [cargs]}]
  {:pre [(vector? cargs)]
   :post [((some-fn 
             #(-> % expr-type TCResult?)
             #{::not-special})
           %)]}
  (assert (#{:invoke :static-call} (:op expr)) (:op expr))
  (assert (vector? cargs))
  (assert (#{2 3} (count args)) "Wrong number of args to clojure.core/get")
  (let [[ctarget ckw cdefault] cargs
        kwr (expr-type ckw)]
    (cond
      (c/keyword-value? (ret-t kwr))
      (assoc expr
             :args cargs
             expr-type (invoke-kw/invoke-keyword 
                         kwr
                         (expr-type ctarget)
                         (when cdefault
                           (expr-type cdefault))
                         expected))

;      ((every-pred r/Value? (comp integer? :val)) (ret-t kwr))
;      (err/nyi-error (str "get lookup of vector (like nth) NYI"))

      :else ::not-special)))

;get
(add-invoke-special-method 'clojure.core/get
  [{fexpr :fn :keys [args] :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)]}
  (let [cargs (mapv check args)
        r (invoke-get expr expected :cargs cargs)]
    (if-not (#{::not-special} r)
      r
      (normal-invoke expr fexpr args expected
                     :cargs cargs))))

(declare check-invoke-method)

(defmethod static-method-special 'clojure.lang.RT/get
  [{:keys [args] :as expr} & [expected]]
  {:pre [args]
   :post [(-> % expr-type TCResult?)]}
  (let [cargs (mapv check args)
        r (invoke-get expr expected :cargs cargs)]
    (if-not (#{::not-special} r)
      r
      (check-invoke-method expr expected false
                           :cargs cargs))))

;FIXME should be the same as (apply hash-map ..) in invoke-apply
(defmethod static-method-special 'clojure.lang.PersistentHashMap/create
  [{:keys [args] :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)
          (vector? (:args %))]}
  (binding [vs/*current-expr* expr]
    (let [_ (when-not (#{1} (count args)) 
              (err/int-error "Incorrect number of arguments to clojure.lang.PersistentHashMap/create"))
          cargs (mapv check args)
          targett (-> (first cargs) expr-type ret-t)]
      (cond
        (r/KwArgsSeq? targett)
        (assoc expr
               :args cargs
               expr-type (ret (c/KwArgsSeq->HMap targett)))
        (not (r/HeterogeneousSeq? targett))
        (err/tc-delayed-error (str "Must pass HeterogeneousSeq to clojure.lang.PersistentHashMap/create given "
                                 (prs/unparse-type targett)
                                 "\n\nHint: Check the expected type of the function and the actual argument list for any differences. eg. extra undeclared arguments"
                                 "\n\nForm:\n\t"
                                 (ast-u/emit-form-fn expr))
                            :return (assoc expr
                                           :args cargs
                                           expr-type (error-ret expected)))
        :else
        (let [res (reduce (fn [t [kt vt]]
                            {:pre [(Type? t)]}
                            ;preserve bottom
                            (if (= (c/Un) vt)
                              vt
                              (do (assert (r/HeterogeneousMap? t))
                                  (assoc-in [:types kt] vt))))
                          (c/-complete-hmap {}) (.types ^HeterogeneousSeq targett))]
          (assoc expr
                 :args cargs
                 expr-type (ret res)))))))

(add-check-method :keyword-invoke
  [{kw :fn :keys [args] :as expr} & [expected]]
  {:pre [(and (#{:const} (:op kw))
              (keyword? (:val kw)))
         (#{1 2} (count args))]
   :post [(TCResult? (expr-type %))
          (vector? (:args %))]}
  (let [ckw (check kw)
        cargs (mapv check args)]
    (assoc expr
           :fn ckw
           :args cargs
           expr-type (invoke-kw/invoke-keyword 
                       (expr-type ckw)
                       (expr-type (first cargs))
                       (when (#{2} (count cargs))
                         (expr-type (second cargs)))
                       expected))))

; Will this play nicely with file mapping?
(add-check-method :prim-invoke ; protocol methods
  [expr & [expected]]
  (check (assoc expr :op :invoke)))

(add-check-method :protocol-invoke ; protocol methods
  [expr & [expected]]
  (check (assoc expr :op :invoke)))

;TODO pass fexpr and args for better errors
;[Type Type (Option Type) -> Type]
(defn find-val-type [t k default]
  {:pre [(Type? t)
         (Type? k)
         ((some-fn nil? Type?) default)]
   :post [(Type? %)]}
  (let [t (c/fully-resolve-type t)]
    (cond
      ; propagate the error
      (r/TCError? t) t
      (r/Nil? t) (or default r/-nil)
      (r/AssocType? t) (let [t* (apply c/assoc-pairs-noret (:target t) (:entries t))]
                         (cond
                           (:dentries t) (do
                                           (prn "dentries NYI")
                                           r/-any)
                           (r/HeterogeneousMap? t*) (find-val-type t* k default)

                           (and (not t*)
                                (r/F? (:target t))
                                (every? c/keyword-value? (map first (:entries t))))
                           (let [hmap (apply c/assoc-pairs-noret (c/-partial-hmap {}) (:entries t))]
                             (if (r/HeterogeneousMap? hmap)
                               (find-val-type hmap k default)
                               r/-any))
                           :else r/-any))
      (r/HeterogeneousMap? t) (let [^HeterogeneousMap t t]
                                ; normal case, we have the key declared present
                                (if-let [v (get (.types t) k)]
                                  v
                                  ; if key is known absent, or we have a complete map, we know precisely the result.
                                  (if (or (contains? (.absent-keys t) k)
                                          (c/complete-hmap? t))
                                    (do
                                      #_(u/tc-warning
                                        "Looking up key " (prs/unparse-type k) 
                                        " in heterogeneous map type " (prs/unparse-type t)
                                        " that declares the key always absent.")
                                      (or default r/-nil))
                                    ; if key is optional the result is the val or the default
                                    (if-let [opt (get (:optional t) k)]
                                      (c/Un opt (or default r/-nil))
                                      ; otherwise result is Any
                                      (do #_(u/tc-warning "Looking up key " (prs/unparse-type k)
                                                        " in heterogeneous map type " (prs/unparse-type t)
                                                        " which does not declare the key absent ")
                                          r/-any)))))

      (r/Record? t) (find-val-type (c/Record->HMap t) k default)

      (r/Intersection? t) (apply c/In 
                               (for [t* (:types t)]
                                 (find-val-type t* k default)))
      (r/Union? t) (apply c/Un
                        (for [t* (:types t)]
                          (find-val-type t* k default)))
      (r/RClass? t)
      (->
        (funapp/check-funapp nil nil (ret (prs/parse-type 
                                     ;same as clojure.core/get
                                     '(All [x y]
                                           (Fn 
                                             ;no default
                                             [(clojure.lang.IPersistentSet x) Any -> (clojure.core.typed/Option x)]
                                             [nil Any -> nil]
                                             [(U nil (clojure.lang.ILookup Any x)) Any -> (U nil x)]
                                             [java.util.Map Any -> (U nil Any)]
                                             [String Any -> (U nil Character)]
                                             ;default
                                             [(clojure.lang.IPersistentSet x) Any y -> (U y x)]
                                             [nil Any y -> y]
                                             [(U nil (clojure.lang.ILookup Any x)) Any y -> (U y x)]
                                             [java.util.Map Any y -> (U y Any)]
                                             [String Any y -> (U y Character)]
                                             ))))
                      [(ret t) (ret (or default r/-nil))] nil)
        ret-t)
      :else r/-any)))


;binding
;FIXME use `check-normal-def`
;FIXME record checked-var-def info
(add-invoke-special-method 'clojure.core/push-thread-bindings
  [{[bindings-expr & other-args :as args] :args :as expr} & [expected]]
  {:post [(vector? (:args %))
          (-> % expr-type r/TCResult?)]}
  (when-not (empty? other-args)
    (err/int-error (str "push-thread-bindings expected one argument, given " (count args))))
  ; only support (push-thread-bindings (hash-map @~[var bnd ...]))
  ; like `binding`s expansion
  (when-not (and (#{:invoke} (-> bindings-expr :op))
                 (#{#'hash-map} (-> bindings-expr :fn :var))
                 (even? (count (-> bindings-expr :args))))
    (err/nyi-error (str "Can only check push-thread-bindings with a well-formed call to hash-map as first argument"
                      " (like bindings expansion)")))
  (let [new-bindings-exprs (apply hash-map (-> bindings-expr :args))
        cargs
        (vec
          (apply concat
                 (for [[{:keys [op var] :as var-expr} bnd-expr] new-bindings-exprs]
                   (do
                     (assert (#{:the-var} op))
                     (let [expected (var-env/type-of (coerce/var->symbol var))
                           cvar-expr (check var-expr)
                           cexpr (check bnd-expr (ret expected))
                           actual (-> cexpr expr-type ret-t)]
                       (when (not (sub/subtype? actual expected))
                         (err/tc-delayed-error (str "Expected binding for "
                                                  (coerce/var->symbol var)
                                                  " to be: " (prs/unparse-type expected)
                                                  ", Actual: " (prs/unparse-type actual))))
                       [cvar-expr cexpr])))))]
    (-> expr
        (update-in [:fn] check)
        (assoc :args cargs
               expr-type (ret r/-nil)))))

(defn dummy-invoke-expr [fexpr args env]
  {:op :invoke
   :env env
   :fn fexpr
   :args args})

(defn dummy-fn-method-expr [body required-params rest-param env]
  {:op :fn-method
   :env env
   :body body
   :params (vec (concat required-params (when rest-param [rest-param])))
   :variadic? (boolean rest-param)})

(defn dummy-fn-expr [methods variadic-method env]
  {:op :fn
   :env env
   :methods (vec (concat methods (when variadic-method [variadic-method])))
   :variadic? (boolean variadic-method)})

(defn dummy-local-binding-expr [sym env]
  {:op :local
   :env env
   :name sym})

(defn dummy-var-expr [vsym env]
  (let [v (resolve vsym)]
    (assert (var? v))
    {:op :var
     :env env
     :var v}))

(defn swap!-dummy-arg-expr [env [target-expr & [f-expr & args]]]
  (assert f-expr)
  (assert target-expr)
  (let [; transform (swap! t f a ...) to (swap! t (fn [t'] (f t' a ...)))
        ;
        ;generate fresh symbol for function param
        sym (gensym 'swap-val)
        derefed-param (dummy-local-binding-expr sym env)
        ;
        ;dummy-fn is (fn [t'] (f t' a ...)) with hygienic bindings
        dummy-fn-expr (dummy-fn-expr
                        [; (fn [t'] (f t' a ...))
                         (dummy-fn-method-expr
                           ; (f t' a ...)
                           (dummy-invoke-expr f-expr
                                              (concat [derefed-param]
                                                      args)
                                              env)
                           [(dummy-local-binding-expr sym env)]
                           nil
                           env)]
                        nil
                        env)]
    dummy-fn-expr))

(defn dummy-do-expr [statements ret env]
  {:op :do
   :statements statements
   :ret ret
   :env env})

(defn dummy-const-expr [val env]
  {:op :const
   :val val
   :env env})

; Any Type Env -> Expr
(defn dummy-ann-form-expr [expr t env]
  (dummy-do-expr
    [(dummy-const-expr ::t/special-form env)
     (dummy-const-expr ::t/ann-form env)
     (dummy-const-expr 
       {:type (binding [t/*verbose-types* true]
                (prs/unparse-type t))}
       env)]
    expr
    env))

;; TODO repopulate :args etc.
;swap!
;
; attempt to rewrite a call to swap! to help type inference
(add-invoke-special-method 'clojure.core/swap!
  [{fexpr :fn :keys [args env] :as expr} & [expected]]
  (let [target-expr (first args)
        ctarget-expr (check target-expr)
        target-t (-> ctarget-expr expr-type ret-t c/fully-resolve-type)
        deref-type (when (and (r/RClass? target-t)
                              (= 'clojure.lang.Atom (:the-class target-t)))
                     (when-not (= 2 (count (:poly? target-t)))
                       (err/int-error (str "Atom takes 2 arguments, found " (count (:poly? target-t)))))
                     (second (:poly? target-t)))
        ]
    (if deref-type
      (cond
        ; TODO if this is a lambda we can do better eg. (swap! (atom> Number 1) (fn [a] a))
        ;(#{:fn} (:op (second args)))

        :else
          (let [dummy-arg (swap!-dummy-arg-expr env args)
                ;_ (prn (ast-u/emit-form-fn dummy-arg) "\n" deref-type)
                expected-dummy-fn-type (r/make-FnIntersection
                                         (r/make-Function
                                           [deref-type]
                                           deref-type))
                delayed-errors (t/-init-delayed-errors)
                actual-dummy-fn-type 
                (binding [t/*delayed-errors* delayed-errors]
                  (-> (normal-invoke expr
                                     (dummy-var-expr
                                       'clojure.core/swap!
                                       env)
                                     [(first args)
                                      (dummy-ann-form-expr
                                        dummy-arg
                                        expected-dummy-fn-type
                                        env)]
                                     expected)
                      expr-type ret-t))]
            ;(prn "deref expected" deref-type)
            ;(prn "expected-dummy-fn-type" expected-dummy-fn-type)
            ;(prn "actual-dummy-fn-type" actual-dummy-fn-type)
            ;(prn "subtype?" (sub/subtype? actual-dummy-fn-type expected-dummy-fn-type))
            (if (seq @delayed-errors)
              :default
              (assoc expr
                     expr-type (ret deref-type)))))
      :default)))

;=
(add-invoke-special-method 'clojure.core/= 
  [{:keys [args] :as expr} & [expected]]
  {:post [(vector? (:args %))
          (-> % expr-type r/TCResult?)]}
  (let [cargs (mapv check args)]
    (-> expr
        (update-in [:fn] check)
        (assoc :args cargs
               expr-type (apply equiv/tc-equiv := (map expr-type cargs))))))

;identical
(defmethod static-method-special 'clojure.lang.Util/identical
  [{:keys [args] :as expr} & [expected]]
  {:post [(vector? (:args %))
          (-> % expr-type r/TCResult?)]}
  (let [cargs (mapv check args)]
    (assoc expr
           :args cargs
           expr-type (apply equiv/tc-equiv := (map expr-type cargs)))))

;equiv
(defmethod static-method-special 'clojure.lang.Util/equiv
  [{:keys [args] :as expr} & [expected]]
  (let [cargs (mapv check args)]
    (assoc expr
           :args cargs
           expr-type (apply equiv/tc-equiv := (map expr-type cargs)))))

(t/ann hvec->rets [HeterogeneousVector -> (Seqable TCResult)])
(defn hvec->rets [v]
  {:pre [(r/HeterogeneousVector? v)]
   :post [(every? TCResult? %)]}
  (map ret
       (:types v)
       (:fs v)
       (:objects v)))

(t/ann tc-isa? [TCResult TCResult -> TCResult])
(defn tc-isa? 
  "Type check a call to isa?. Assumes global hierarchy.
  Also supports the case where both elements are vectors, but not recursively."
  [child-ret parent-ret]
  {:pre [(TCResult? child-ret)
         (TCResult? parent-ret)]
   :post [(TCResult? %)]}
  (letfn> [fs :- [TCResult TCResult -> '{:then fprotocol/IFilter :else fprotocol/IFilter}]
           (fs [child1 parent1]
             {:pre [(TCResult? child1)
                    (TCResult? parent1)]
              :post [((con/hmap-c? :then fl/Filter? :else fl/Filter?) %)]}
             {:then (fo/-filter-at (ret-t parent1) (ret-o child1))
              :else (fo/-not-filter-at (ret-t parent1) (ret-o child1))})]
    (let [child-t (ret-t child-ret)
          parent-t (ret-t parent-ret)
          fs (cond
               ; interesting case with (isa? [...] [...])
               ; use each pairing between child and parent
               (and (r/HeterogeneousVector? child-t)
                    (r/HeterogeneousVector? parent-t))
               (let [individual-fs (map fs (hvec->rets child-t) (hvec->rets parent-t))]
                 (fo/-FS (apply fo/-and (map :then individual-fs))
                         (apply fo/-or (map :else individual-fs))))
               ; simple (isa? child parent) 
               :else (let [{:keys [then else]} (fs child-ret parent-ret)]
                       (fo/-FS then else)))]
      (ret (c/Un r/-true r/-false) fs obj/-empty))))


;isa? (2 arity is special)
(add-invoke-special-method 'clojure.core/isa?
  [{:keys [args] :as expr} & [expected]]
  (cond
    (#{2} (count args))
    (let [[cchild-expr cparent-expr :as cargs] (mapv check args)]
      (-> expr
          (update-in [:fn] check)
          (assoc :args cargs
                 expr-type (tc-isa? (expr-type cchild-expr)
                                    (expr-type cparent-expr)))))
    :else :default))

;FIXME need to review if any repeated "check"s happen between invoke-apply and specials
;apply
(add-invoke-special-method 'clojure.core/apply
  [expr & [expected]]
  ;(prn "special apply:")
  (let [e (invoke-apply expr expected)]
    (if (= e ::not-special)
      :default
      e)))


;TODO this should be a special :do op
;manual instantiation
(add-invoke-special-method 'clojure.core.typed/inst-poly
  [{[pexpr targs-exprs :as args] :args :as expr} & [expected]]
  (when-not (#{2} (count args)) 
    (err/int-error "Wrong arguments to inst"))
  (let [ptype (c/fully-resolve-type (-> (check pexpr) expr-type ret-t))
        ; support (inst :kw ...)
        ptype (if (c/keyword-value? ptype)
                (c/KeywordValue->Fn ptype)
                ptype)]
    (if-not ((some-fn r/Poly? r/PolyDots?) ptype)
      (binding [vs/*current-expr* pexpr]
        (err/tc-delayed-error (str "Cannot instantiate non-polymorphic type: " (prs/unparse-type ptype))
                            :return (assoc expr
                                           expr-type (error-ret expected))))
      (let [targs (binding [prs/*parse-type-in-ns* (cu/expr-ns expr)]
                    (doall (map prs/parse-type (quote-expr-val targs-exprs))))]
        (assoc expr
               expr-type (ret (inst/manual-inst ptype targs)))))))

(defonce ^:dynamic *inst-ctor-types* nil)
(set-validator! #'*inst-ctor-types* (some-fn nil? (con/every-c? Type?)))

;TODO this should be a special :do op
;manual instantiation for calls to polymorphic constructors
(add-invoke-special-method 'clojure.core.typed/inst-poly-ctor
  [{[ctor-expr targs-exprs] :args :as expr} & [expected]]
  (let [targs (binding [prs/*parse-type-in-ns* (cu/expr-ns expr)]
                (mapv prs/parse-type (quote-expr-val targs-exprs)))
        cexpr (binding [*inst-ctor-types* targs]
                (check ctor-expr))]
    (assoc expr
           expr-type (expr-type cexpr))))

(defn- print-lex-env [l]
  {:pre [(lex/lex-env? l)]}
  (prn (into {} (for [[k v] l]
                  [k (prs/unparse-type v)]))))

(defn- print-env*
  ([] (print-env* lex/*lexical-env*))
  ([e]
   {:pre [(lex/PropEnv? e)]}
   ;; DO NOT REMOVE
   (let [tvar-scope tvar-env/*current-tvars*
         tvar-bounds tvar-bnds/*current-tvar-bnds*
         scoped-names (keys tvar-scope)
         actual-names (map :name (vals tvar-scope))
         _ (every? symbol? actual-names)
         actual-bnds (map tvar-bounds actual-names)]
     (prn {:env (into {} (for [[k v] (:l e)]
                           [k (prs/unparse-type v)]))
           :props (map prs/unparse-filter (:props e))
           ;:frees (map (t/fn> 
           ;              [nme :- t/Sym, bnd :- (U nil Bounds)]
           ;              {:pre [(symbol? nme)
           ;                     ((some-fn nil? r/Bounds?) bnd)]}
           ;              (if bnd
           ;                (prs/unparse-poly-bounds-entry nme bnd)
           ;                [nme 'NO-BOUNDS]))
           ;            scoped-names
           ;            actual-bnds)
           ;:tvar-scope tvar-scope
           ;:tvar-bnds tvar-bounds
           }))))

;debug printing
(add-invoke-special-method 'clojure.core.typed/print-env
  [{[debug-string :as args] :args :as expr} & [expected]]
  (when-not (#{1} (count args)) 
    (err/int-error (str "Wrong arguments to print-env, Expected 1, found " (count args))))
  (when-not (= :const (:op debug-string))
    (err/int-error "Must pass print-env a string literal"))
  ;DO NOT REMOVE
  (println (:val debug-string))
  (flush)
  (prs/with-unparse-ns (cu/expr-ns expr)
    (print-env*))
  ;DO NOT REMOVE
  (assoc expr
         expr-type (ret r/-nil (fo/-false-filter) obj/-empty)))

;filter printing
(add-invoke-special-method 'clojure.core.typed/print-filterset
  [{[debug-string form :as args] :args :as expr} & [expected]]
  (when-not (#{2} (count args)) 
    (err/int-error (str "Wrong arguments to print-filterset. Expected 2, found " (count args))))
  (when-not (= :const (:op debug-string)) 
    (err/int-error "Must pass print-filterset a string literal as the first argument."))
  (let [cform (check form expected)
        cargs [debug-string cform]
        t (expr-type cform)]
    ;DO NOT REMOVE
    (println (:val debug-string))
    (flush)
    ;(prn (:fl t))
    (prs/with-unparse-ns (cu/expr-ns expr)
      (if (fl/FilterSet? (:fl t))
        (do (pprint/pprint (prs/unparse-filter-set (:fl t)))
            (flush))
        (prn (:fl t)))
      (prn (prs/unparse-object (:o t)))
      (prn 'Flow (prs/unparse-filter (-> t :flow r/flow-normal))))
    ;DO NOT REMOVE
    (assoc expr
           :args cargs
           expr-type t)))

;unchecked casting
(add-invoke-special-method 'clojure.core.typed.unsafe/ignore-with-unchecked-cast*
  [{[frm quote-expr] :args, :keys [env], :as expr} & [expected]]
  (let [tsyn (quote-expr-val quote-expr)
        parsed-ty (binding [vs/*current-env* env
                            prs/*parse-type-in-ns* (cu/expr-ns expr)]
                    (prs/parse-type tsyn))]
    (assoc expr
           expr-type (ret parsed-ty))))

;pred
(add-invoke-special-method 'clojure.core.typed/pred*
  [{[tsyn-expr nsym-expr _pred-fn_ :as args] 
    :args, :keys [env], :as expr} & [expected]]
  {:pre [(#{3} (count args))]}
  (let [tsyn (quote-expr-val tsyn-expr)
        nsym (quote-expr-val nsym-expr)
        ptype 
        ; frees are not scoped when pred's are parsed at runtime,
        ; so we simulate the same here.
        (binding [tvar-env/*current-tvars* {}
                  dvar-env/*dotted-scope* {}]
          (prs/with-parse-ns nsym
            (prs/parse-type tsyn)))]
    (assoc expr
           expr-type (ret (prs/predicate-for ptype)))))

;fn literal
(add-invoke-special-method 'clojure.core.typed/fn>-ann
  [{:keys [args] :as expr} & [expected]]
  (let [[fexpr quote-expr] args
        type-syns (quote-expr-val quote-expr)
        expected
        (binding [prs/*parse-type-in-ns* (cu/expr-ns expr)]
          (apply
            r/make-FnIntersection
            (doall
              (for [{:keys [dom-syntax has-rng? rng-syntax]} type-syns]
                (r/make-Function (mapv prs/parse-type dom-syntax)
                                 (if has-rng?
                                   (prs/parse-type rng-syntax)
                                   r/-any))))))
        cfexpr (check fexpr (ret expected))
        cargs [cfexpr quote-expr]]
    (assoc expr
           :args cargs
           expr-type (expr-type cfexpr))))

;polymorphic fn literal
(add-invoke-special-method 'clojure.core.typed/pfn>-ann
  [{:keys [args] :as expr} & [expected]]
  (assert false "pfn> NYI")
         ;FIXME these are :quote exprs
  #_(let [[fexpr {poly-decl :val} {method-types-syn :val}] args
        frees-with-bounds (map prs/parse-free poly-decl)
        method-types (free-ops/with-bounded-frees frees-with-bounds
                       (binding [prs/*parse-type-in-ns* (cu/expr-ns expr)]
                         (doall 
                           (for [{:keys [dom-syntax has-rng? rng-syntax]} method-types-syn]
                             {:dom (doall (map prs/parse-type dom-syntax))
                              :rng (if has-rng?
                                     (prs/parse-type rng-syntax)
                                     r/-any)}))))
        cexpr (-> (check-anon-fn fexpr method-types :poly frees-with-bounds)
                  (update-in [expr-type :t] (fn [fin] (c/Poly* (map first frees-with-bounds) 
                                                             (map second frees-with-bounds)
                                                             fin))))]
    cexpr))

(defonce ^:dynamic *loop-bnd-anns* nil)
(set-validator! #'*loop-bnd-anns* #(or (nil? %)
                                       (every? Type? %)))

;loop
(add-invoke-special-method 'clojure.core.typed/loop>-ann
  [{:keys [args] :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)
          (vector? (:args %))]}
  (let [[loop-expr expected-quote-expr] args
        expected-bnds-syn (quote-expr-val expected-quote-expr)
        expected-bnds (binding [prs/*parse-type-in-ns* (cu/expr-ns loop-expr)]
                        (mapv prs/parse-type expected-bnds-syn))
        cloop-expr
        ;loop may be nested, type the first loop found
        (binding [*loop-bnd-anns* expected-bnds]
          (check loop-expr expected))
        cargs [cloop-expr expected-quote-expr]]
    (assoc expr
           :args cargs
           expr-type (expr-type cloop-expr))))

;seq
(add-invoke-special-method 'clojure.core/seq
  [{fexpr :fn :keys [args] :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)
          (vector? (:args %))]}
  (let [_ (assert (#{1} (count args))
                  "Wrong number of arguments to seq")
        [ccoll :as cargs] (mapv check args)]
    ;(prn "special seq: ccoll type" (prs/unparse-type (ret-t (expr-type ccoll))))
    (cond
      ; for (apply hash-map (seq kws)) macroexpansion of keyword args
      (r/KwArgsSeq? (ret-t (expr-type ccoll)))
      (assoc expr
             :args cargs
             expr-type (expr-type ccoll))

      :else (normal-invoke expr fexpr args expected :cargs cargs))))

;make vector
(add-invoke-special-method 'clojure.core/vector
  [{:keys [args] :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)
          (vector? (:args %))]}
  (let [cargs (mapv check args)]
    (-> expr
        (update-in [:fn] check)
        (assoc 
          :args cargs
          expr-type (ret (r/-hvec (mapv (comp ret-t expr-type) cargs)
                                  :filters (mapv (comp ret-f expr-type) cargs)
                                  :objects (mapv (comp ret-o expr-type) cargs)))))))

;make hash-map
(add-invoke-special-method 'clojure.core/hash-map
  [{fexpr :fn :keys [args] :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)
          (vector? (:args %))]}
  (let [cargs (mapv check args)]
    (cond
      (every? r/Value? (keys (apply hash-map (mapv (comp ret-t expr-type) cargs))))
      (-> expr
        (update-in [:fn] check)
        (assoc :args cargs
               expr-type (ret (c/-complete-hmap
                                (apply hash-map (mapv (comp ret-t expr-type) cargs))))))
      :else (normal-invoke expr fexpr args expected :cargs cargs))))

;(apply concat hmap)
(add-invoke-apply-method 'clojure.core/concat
  [{[_ & args] :args :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)
          (vector? (:args %))]}
  (let [cargs (mapv check args)
        tmap (when (#{1} (count cargs))
               (c/fully-resolve-type (ret-t (expr-type (last cargs)))))]
    (binding [vs/*current-expr* expr]
      (cond
        tmap
        (let [r (c/HMap->KwArgsSeq tmap false)
              _ (when expected
                  (when-not (sub/subtype? r (ret-t expected))
                    (cu/expected-error r (ret-t expected))))]
          (-> expr
              (update-in [:fn] check)
              (assoc expr-type (ret r))))
        :else ::not-special))))

;apply hash-map
(add-invoke-apply-method 'clojure.core/hash-map
  [{[_ & args] :args :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)
          (vector? (:args %))]}
  (let [cargs (mapv check args)]
    ;(prn "apply special (hash-map): ")
    (cond
      (and (#{1} (count cargs))
           (r/KwArgsSeq? (expr-type (last cargs))))
      (-> expr
          (update-in [:fn] check)
          (assoc :args cargs
                 expr-type (ret (c/KwArgsSeq->HMap (-> (expr-type (last cargs)) ret-t)))))

      (and (seq cargs)
           ((some-fn r/HeterogeneousVector? r/HeterogeneousList? r/HeterogeneousSeq?) 
            (ret-t (expr-type (last cargs))))
           ;; every key must be a Value
           (every? r/Value? (keys (apply hash-map (concat (map (comp ret-t expr-type) (butlast cargs))
                                                          (mapcat vector (:types (ret-t (expr-type (last cargs))))))))))
      (-> expr
          (update-in [:fn] check)
          (assoc :args cargs
                 expr-type (ret (c/-complete-hmap
                                  (apply hash-map (concat (map (comp ret-t expr-type) (butlast cargs))
                                                          (mapcat vector (:types (ret-t (expr-type (last cargs)))))))))))
      :else ::not-special)))

(defn invoke-nth [{:keys [args] :as expr} expected & {:keys [cargs]}]
  {:pre [((some-fn nil? vector?) cargs)]}
  (let [_ (assert (#{2 3} (count args)) (str "nth takes 2 or 3 arguments, actual " (count args)))
        [te ne de :as cargs] (or cargs (mapv check args))
        types (let [ts (c/fully-resolve-type (ret-t (expr-type te)))]
                (if (r/Union? ts)
                  (:types ts)
                  [ts]))
        num-t (ret-t (expr-type ne))
        default-t (when de
                    (ret-t (expr-type de)))]
    (cond
      (and (r/Value? num-t)
           (integer? (:val num-t))
           (every? (some-fn r/Nil?
                            r/HeterogeneousVector?
                            r/HeterogeneousList?
                            r/HeterogeneousSeq?)
                   types))
      (assoc expr
             :args cargs
             expr-type (ret (apply c/Un
                                   (doall
                                     (for [t types]
                                       (if-let [res-t (cond
                                                        (r/Nil? t) (or default-t r/-nil)
                                                        ; nil on out-of-bounds and no default-t
                                                        :else (nth (:types t) (:val num-t) default-t))]
                                         res-t
                                         (err/int-error (str "Cannot get index " (:val num-t)
                                                           " from type " (prs/unparse-type t)))))))
                            (let [nnth (:val num-t)
                                  target-o (ret-o (expr-type te))
                                  default-o (when de
                                              (ret-o (expr-type de)))
                                  ;; We handle filters for both arities of nth here, with and without default
                                  ;;
                                  ;;With default:
                                  ;; if this is a true value either:
                                  ;;  * target is nil or seq and default is true
                                  ;;  * target is seqable, default is false
                                  ;;    and target is at least (inc nnth) count
                                  default-fs+ (fo/-or (fo/-and (fo/-filter-at (c/Un r/-nil (c/RClass-of ISeq [r/-any])) 
                                                                              target-o)
                                                               (fo/-not-filter-at (c/Un r/-false r/-nil) 
                                                                                  default-o))
                                                      (fo/-and (fo/-filter-at (c/In (c/RClass-of Seqable [r/-any])
                                                                                    (r/make-CountRange (inc nnth)))
                                                                              target-o)
                                                               (fo/-filter-at (c/Un r/-false r/-nil) 
                                                                              default-o)))
                                  ;;Without default:
                                  ;; if this is a true value: 
                                  ;;  * target is seqable of at least nnth count
                                  nodefault-fs+ (fo/-filter-at (c/In (c/RClass-of Seqable [r/-any])
                                                                     (r/make-CountRange (inc nnth)))
                                                               target-o)]
                              (fo/-FS (if default-t
                                        default-fs+
                                        nodefault-fs+)
                                      ; not sure if there's anything worth encoding here
                                      fl/-top))))
      :else ::not-special)))

;nth
(defmethod static-method-special 'clojure.lang.RT/nth
  [{:keys [args] :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)]}
  (let [cargs (mapv check args)
        r (invoke-nth expr expected :cargs cargs)]
    (if-not (#{::not-special} r)
      r
      (check-invoke-method expr expected false
                           :cargs cargs))))

;assoc
(add-invoke-special-method 'clojure.core/assoc
  [{:keys [args] :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)]}
  (let [[target & keyvals] args

        _ (when-not (<= 3 (count args))
            (err/int-error (str "assoc accepts at least 3 arguments, found "
                                     (count args))))
        _ (when-not (even? (count keyvals))
            (err/int-error "assoc accepts an even number of keyvals"))

        ctarget (check target)
        targetun (-> ctarget expr-type ret-t)
        ckeyvals (doall (map check keyvals))
        keypair-types (partition 2 (map expr-type ckeyvals))
        cargs (vec (cons ctarget ckeyvals))]
    (if-let [new-hmaps (apply c/assoc-type-pairs targetun keypair-types)]
      (-> expr
        (update-in [:fn] check)
        (assoc
          :args cargs
          expr-type (ret new-hmaps
                         (fo/-true-filter) ;assoc never returns nil
                         obj/-empty)))
      
      ;; to do: improve this error message
      (err/tc-delayed-error (str "Cannot assoc args `"
                               (clojure.string/join " "
                                 (map (comp prs/unparse-type expr-type) ckeyvals))
                               "` on "
                               (prs/unparse-type targetun))
                          :return (-> expr
                                      (update-in [:fn] check)
                                      (assoc
                                        :args cargs
                                        expr-type (error-ret expected)))))
    ))

(add-invoke-special-method 'clojure.core/dissoc
  [{fexpr :fn :keys [args] :as expr} & [expected]]
  {:post [(or (= % :default) (-> % expr-type TCResult?))]}
  (let [_ (when-not (seq args)
            (err/int-error (str "dissoc takes at least one argument, given: " (count args))))
        [ctarget & cdissoc-args :as cargs] (mapv check args)
        ttarget (-> ctarget expr-type ret-t)
        targs (map expr-type cdissoc-args)]
    (if-let [new-t (c/dissoc-keys ttarget targs)]
      (-> expr
          (update-in [:fn] check)
          (assoc
            :args cargs
            expr-type (ret new-t)))
      (normal-invoke expr fexpr args expected
                     :cargs cargs))))

; merge
(add-invoke-special-method 'clojure.core/merge
  [{fexpr :fn :keys [args] :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)
          (vector? (:args %))]}
  (let [[ctarget & cmerge-args :as cargs] (mapv check args)
        basemap (-> ctarget expr-type ret-t c/fully-resolve-type)
        targs (map expr-type cmerge-args)]
    (if-let [merged (apply c/merge-types basemap targs)]
      (-> expr
          (update-in [:fn] check)
          (assoc :args cargs
                 expr-type (ret merged
                                (fo/-true-filter) ;assoc never returns nil
                                obj/-empty)))
      (normal-invoke expr fexpr args expected
                     :cargs cargs))))

;conj
(add-invoke-special-method 'clojure.core/conj
  [{fexpr :fn :keys [args] :as expr} & [expected]]
  (let [[ctarget & cconj-args :as cargs] (mapv check args)
        ttarget (-> ctarget expr-type ret-t)
        targs (map expr-type cconj-args)]
    (if-let [conjed (apply c/conj-types ttarget targs)]
      (-> expr
          (update-in [:fn] check)
          (assoc :args cargs
                 expr-type (ret conjed
                                (fo/-true-filter) ; conj never returns nil
                                obj/-empty)))
      (normal-invoke expr fexpr args expected
                     :cargs cargs))))

#_(add-invoke-special-method 'clojure.core/update-in
  [{:keys [args env] :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)]}
  (binding [vs/*current-expr* expr
            vs/*current-env* env]
    (let [error-expr (-> expr
                         (update-in [:fn] check)
                         (assoc expr-type (ret (r/TCError-maker))))]
      (cond
        (not (< 3 (count args))) (err/tc-delayed-error (str "update-in takes at least 3 arguments"
                                                          ", actual " (count args))
                                                     :return error-expr)

        :else
        (let [[ctarget-expr cpath-expr cfn-expr & more-exprs] (doall (map check args))
              path-type (-> cpath-expr expr-type ret-t c/fully-resolve-type)]
          (if (not (HeterogeneousVector? path-type))
            (err/tc-delayed-error (str "Can only check update-in with vector as second argument")
                                :return error-expr)
            (let [path (:types path-type)
                  follow-path (reduce (fn [t pth]
                                        (when t
                                          ))
                                      (-> ctarget-expr expr-type ret-t)
                                      path)]
              (assert nil))))))))
        

(comment
  (method-expected-type (prs/parse-type '[Any -> Any])
                        (prs/parse-type '(Value :op))
                        (prs/parse-type '(Value :if)))
  ;=> ['{:if Any} -> Any]
  )

(defn- parse-fn-return-type [parse-fn-type]
  (let [subst-in (free-ops/with-free-symbols 
                   #{'a} (prs/parse-type '[String -> a]))] 
    (-> (cgen/cs-gen #{} {'a r/no-bounds} {} parse-fn-type subst-in) 
        (cgen/subst-gen #{} subst-in)
        (get-in ['a :type]))))

(defn vector-args [expr]
  (case (:op expr)
    :constant (when (vector? (:val expr))
                (map (fn [f] [f nil]) (:val expr)))
    :vector (doall
              (map (fn [arg-expr]
                     [(ast-u/emit-form-fn arg-expr) arg-expr])
                   (:args expr)))
    nil))

; some code taken from tools.cli
; (All [x]
;   [CliSpec -> (U nil '[Value Type])])
(defn parse-cli-spec [spec-expr]
  (letfn [(opt? [^String x]
            (.startsWith x "-"))
          (name-for [k]
            (str/replace k #"^--no-|^--\[no-\]|^--|^-" "")) 
          (flag? [^String x]
              (.startsWith x "--[no-]"))]

  (let [; (U nil (Seqable '[Form (U nil Expr)]))
        raw-spec (vector-args spec-expr)]
    (cond
      (not raw-spec) (do
                       ;(prn "cli: not vector " spec-expr)
                       nil)
      :else
      (let [; each seq and map entry is a pair of [form expr]
            [switches raw-spec] (split-with (fn [[frm _]] (and (string? frm) (opt? frm))) raw-spec)
            [docs raw-spec]     (split-with (fn [[frm _]] (string? frm)) raw-spec)
            ; keys are [kw expr]
            options             (apply hash-map raw-spec)
            ; keys are keywords
            ; (Map Keyword [Form Expr])
            options             (into {}
                                      (for [[[kfrm _] v] options]
                                        [kfrm v]))
            ; (Seqable Form)
            aliases             (map (fn [[frm _]] (name-for frm)) switches)
            ; assume we fail later if there is anything ambiguous
            flag                (or (if (seq switches)
                                      (flag? (first (last switches)))
                                      :unknown)
                                    (when (contains? options :flag)
                                      (let [flg-form (first (:flag options))]
                                        (if (con/boolean? flg-form)
                                          flg-form
                                          :unknown)))
                                    false)]
        (cond
          ;not accurate enough, return nil
          (not-every? keyword? (keys options)) (do
                                                 ;(prn "cli: not every option key was keyword" options)
                                                 nil)
          (#{:unknown} flag) (do 
                               ;(prn "cli: flag unknown")
                               nil)
          (not
            (and (#{0 1} (count docs))
                 ((some-fn nil? string?) (-> docs first first)))) (do
                                                                    ;(prn "cli: docs" docs) 
                                                                    nil)
          (empty? aliases) (do
                             ;(prn "cli: empty aliases")
                             nil)
          :else
          (let [name (r/-val (keyword (last aliases)))
                default-type (when-let [[frm default-expr] (:default options)]
                               (if default-expr
                                 (-> (check default-expr)
                                     expr-type
                                     ret-t)
                                 (const/constant-type frm)))
                parse-fn-type (when-let [[pfrm parse-fn-expr] (:parse-fn options)]
                                (if parse-fn-expr
                                  (-> (check parse-fn-expr (ret (prs/parse-type
                                                                  '[String -> Any])))
                                      expr-type
                                      ret-t)
                                  (const/constant-type pfrm)))
                parse-fn-ret (when parse-fn-type
                               (parse-fn-return-type parse-fn-type))
                type (cond
                       (and parse-fn-type
                            (not parse-fn-ret)) (do
                                                  ;(prn "cli: parse-fn")
                                                  nil)
                       flag (c/RClass-of Boolean)
                       :else
                       (apply c/Un (concat (when default-type
                                             [default-type])
                                           (if parse-fn-type
                                             [parse-fn-ret]
                                             [(c/RClass-of String)]))))]
            (when type
              [name type]))))))))

; cli
;TODO add cargs to result
(add-invoke-special-method 'clojure.tools.cli/cli
  [{[args-expr & specs-exprs] :args :keys [env] :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)
          (vector? (:args %))]}
  (binding [vs/*current-env* env]
    (let [args-expected-ty (prs/parse-type '(U nil (clojure.lang.Seqable String)))
          cargs-expr (binding [vs/*current-env* (:env args-expr)]
                       (check args-expr))
          _ (when-not (sub/subtype? 
                        (-> cargs-expr expr-type ret-t)
                        args-expected-ty)
              (binding [vs/*current-env* (:env args-expr)]
                (cu/expected-error (-> cargs-expr expr-type ret-t) args-expected-ty)))
          spec-map-ty (reduce (fn [t spec-expr]
                                (if-let [[keyt valt] (parse-cli-spec spec-expr)]
                                  (-> t
                                    (assoc-in [:types keyt] valt))
                                  ; resort to a general type
                                  (do
                                    ;(prn "cli: giving up because of" (ast-u/emit-form-fn spec-expr)
                                         ;"\n" spec-expr)
                                    (reduced 
                                      (c/RClass-of IPersistentMap [(c/RClass-of clojure.lang.Keyword) r/-any])))))
                              (c/-complete-hmap {})
                              specs-exprs)

          actual (r/-hvec [spec-map-ty 
                           (prs/parse-type '(clojure.lang.Seqable String))
                           (prs/parse-type 'String)])
          _ (when expected
              (when-not (sub/subtype? actual (ret-t expected))
                (cu/expected-error 
                  actual (ret-t expected))))
          cargs (vec (cons cargs-expr specs-exprs))]
      (-> expr
        (update-in [:fn] check)
        (assoc :args cargs
               expr-type (ret actual))))))

(defonce ^:dynamic *current-mm* nil)
(set-validator! #'*current-mm* (some-fn nil? 
                                        (con/hmap-c? :dispatch-fn-type Type?
                                                   :dispatch-val-ret TCResult?)))

(defn default-defmethod? [var dispatch-val]
  {:pre [(var? var)]}
  (let [^clojure.lang.MultiFn multifn @var
        _ (assert (instance? clojure.lang.MultiFn multifn))
        default-val (.defaultDispatchVal multifn)]
    (= default-val dispatch-val)))

; FIXME this needs a line number from somewhere!
(defmethod instance-method-special 'clojure.lang.MultiFn/addMethod
  [{[dispatch-val-expr method-expr :as args] :args target :instance :keys [env] :as expr} & [expected]]
  (when-not (= 2 (count args))
    (err/int-error "Wrong arguments to clojure.lang.MultiFn/addMethod"))
  (when-not (#{:var} (:op target))
    (err/int-error "Must call addMethod with a literal var"))
  (let [var (:var target)
        _ (assert (var? var))
        mmsym (coerce/var->symbol var)
        ret-expr (assoc expr
                        expr-type (ret (c/RClass-of clojure.lang.MultiFn)))
        default? (default-defmethod? var (ast-u/emit-form-fn dispatch-val-expr))]
    (cond
      ;skip if warn-on-unannotated-vars is in effect
      (or (and (ns-opts/warn-on-unannotated-vars? (cu/expr-ns expr))
               (not (var-env/lookup-Var-nofail mmsym)))
          (not (var-env/check-var? mmsym)))
      (do (println "<NO LINE NUMBER>: Not checking defmethod" mmsym "with dispatch value" (ast-u/emit-form-fn dispatch-val-expr))
          (flush)
          ret-expr)
      :else
      (let [_ (assert (#{:var} (:op target)))
            _ (when-not (#{:fn} (:op method-expr))
                (err/int-error (str "Method must be a fn")))
            ctarget (check target)
            cdispatch-val-expr (check dispatch-val-expr)
            dispatch-type (mm/multimethod-dispatch-type mmsym)]
        (if-not dispatch-type
          (binding [vs/*current-env* env]
            (err/tc-delayed-error (str "Multimethod requires dispatch type: " mmsym
                                     "\n\nHint: defmulti must be checked before its defmethods")
                                :return (assoc ret-expr
                                               :instance ctarget)))
          (let [method-expected (var-env/type-of mmsym)
                cmethod-expr 
                (binding [*current-mm* (when-not default?
                                         {:dispatch-fn-type dispatch-type
                                          :dispatch-val-ret (expr-type cdispatch-val-expr)})]
                  (check method-expr (ret method-expected)))
                cargs [cdispatch-val-expr cmethod-expr]]
            (assoc ret-expr
                   :instance ctarget
                   :args cargs)))))))

(add-invoke-special-method :default [& args] :default)
(defmethod static-method-special :default [& args] :default)
(defmethod instance-method-special :default [& args] :default)

(defn check-apply
  [{[fexpr & args] :args :as expr} expected]
  {:post [((some-fn TCResult? #{::not-special}) %)]}
  (binding [vs/*current-expr* expr]
  (let [ftype (ret-t (expr-type (check fexpr)))
        [fixed-args tail] [(butlast args) (last args)]]
    (cond
      ;apply of a simple function
      (r/FnIntersection? ftype)
      (do 
        (when (empty? (:types ftype))
          (err/int-error (str "Empty function intersection given as argument to apply")))
        (let [arg-tres (mapv check fixed-args)
              arg-tys (mapv (comp ret-t expr-type) arg-tres)
              tail-ty (ret-t (expr-type (check tail)))]
          (loop [[{:keys [dom rng rest drest]} :as fs] (:types ftype)]
            (cond
              ;we've run out of cases to try, so error out
              (empty? fs)
              (err/tc-delayed-error (str "Bad arguments to apply: "
                                       "\n\nTarget: \t" (prs/unparse-type ftype) 
                                       "\n\nArguments:\t" (str/join " " (mapv prs/unparse-type (concat arg-tys [tail-ty]))))
                                  :return (error-ret expected))

              ;this case of the function type has a rest argument
              (and rest
                   ;; check that the tail expression is a subtype of the rest argument
                   (sub/subtype? tail-ty (c/Un r/-nil (c/RClass-of Seqable [rest])))
                   (sub/subtypes-varargs? arg-tys dom rest nil))
              (ret (r/Result-type* rng)
                   (r/Result-filter* rng)
                   (r/Result-object* rng))

              ;other cases go here

              ;next case
              :else (recur (next fs))))))

      ;; apply of a simple polymorphic function
      (r/Poly? ftype)
      (let [vars (c/Poly-fresh-symbols* ftype)
            bbnds (c/Poly-bbnds* vars ftype)
            body (c/Poly-body* vars ftype)
            _ (assert (r/FnIntersection? body))
            arg-tres (mapv check fixed-args)
            arg-tys (mapv (comp ret-t expr-type) arg-tres)
            tail-bound nil
            tail-ty (ret-t (expr-type (check tail)))]
        (loop [[{:keys [dom rng rest drest] :as ftype0} :as fs] (:types body)]
          ;          (when (seq fs)
          ;            (prn "checking fn" (prs/unparse-type (first fs))
          ;                 (mapv prs/unparse-type arg-tys)))
          (cond
            (empty? fs) (err/tc-delayed-error (str "Bad arguments to polymorphic function in apply")
                                            :return (error-ret expected))
            ;the actual work, when we have a * function and a list final argument
            :else 
            (if-let [substitution (cgen/handle-failure
                                    (and rest (not tail-bound) 
                                         (<= (count dom)
                                             (count arg-tys))
                                         (cgen/infer-vararg (zipmap vars bbnds) {}
                                                            (cons tail-ty arg-tys)
                                                            (cons (c/Un r/-nil (c/RClass-of Seqable [rest])) dom)
                                                            rest
                                                            (r/Result-type* rng))))]
              (ret (subst/subst-all substitution (r/Result-type* rng)))
              (recur (next fs))))))

      :else ::not-special))))

;TODO attach new :args etc.
;convert apply to normal function application
(add-invoke-apply-method :default 
  [expr & [expected]]
  (let [t (check-apply expr expected)]
    (if (= t ::not-special)
      t
      (assoc expr
             expr-type t))))

(defn normal-invoke [expr fexpr args expected & {:keys [cfexpr cargs]}]
  (u/p :check/normal-invoke
  (let [cfexpr (or cfexpr
                   (check fexpr))
        cargs (or cargs
                  (mapv check args))
        ftype (expr-type cfexpr)
        argtys (map expr-type cargs)
        actual (funapp/check-funapp fexpr args ftype argtys expected)]
    (assoc expr
           :fn cfexpr
           :args cargs
           expr-type actual))))

(add-check-method :invoke
  [{fexpr :fn :keys [args env] :as expr} & [expected]]
  {:post [(TCResult? (expr-type %))
          (vector? (:args %))
          #_(-> % :fn expr-type TCResult?)]}
  #_(prn "invoke:" ((some-fn :var :keyword :op) fexpr))
  (binding [vs/*current-env* env]
    (let [e (invoke-special expr expected)]
      (if (not= :default e) 
        e
        (let [cfexpr (check fexpr)]
          (cond
            (c/keyword-value? (ret-t (expr-type cfexpr)))
            (let [[ctarget cdefault :as cargs] (mapv check args)]
              (assert (<= 1 (count args) 2))
              (assoc expr
                     :fn cfexpr
                     :args cargs
                     expr-type (invoke-kw/invoke-keyword 
                                 (expr-type cfexpr)
                                 (expr-type ctarget)
                                 (when cdefault
                                   (expr-type cdefault)) 
                                 expected)))

            :else (normal-invoke expr fexpr args expected :cfexpr cfexpr)))))))

;lam-result in TR
(u/defrecord FnResult [args kws rest drest body]
  "Results of checking a fn method"
  [(every? symbol? (map first args))
   (every? Type? (map second args))
   ((some-fn nil? (con/hvector-c? symbol? r/KwArgs?)) kws)
   ((some-fn nil? (con/hvector-c? symbol? Type?)) rest)
   ((some-fn nil? (con/hvector-c? symbol? r/DottedPretype?)) drest)
   (TCResult? body)])

(defn- KwArgs-minimum-args [^KwArgs kws]
  {:pre [(r/KwArgs? kws)]
   :post [((con/hmap-c? :minimum (complement neg?)
                      :maximum (some-fn nil? (complement neg?)))
           %)]}
  {:minimum (count (.mandatory kws))
   ; if no optional parameters, must supply exactly the number of mandatory arguments
   :maximum (when (empty? (.optional kws))
              (count (.mandatory kws)))})

;[(Seqable Expr) (Option Expr) FnIntersection -> (Seqable Function)]
(defn relevant-Fns
  "Given a set of required-param exprs, rest-param expr, and a FnIntersection,
  returns a seq of Functions containing Function types
  whos arities could be a subtype to the method with the fixed and rest parameters given"
  [required-params rest-param fin]
  {:pre [(r/FnIntersection? fin)]
   :post [(every? r/Function? %)]}
  (let [nreq (count required-params)]
    ;(prn "nreq" nreq)
    ;(prn "rest-param" rest-param)
    (filter (fn [{:keys [dom rest drest kws]}]
              (let [ndom (count dom)]
                (if rest-param 
                  (or ; required parameters can flow into the rest type
                      (when (or rest drest)
                        (<= nreq ndom))
                      ; kw functions must have exact fixed domain match
                      (when kws
                        (= nreq ndom)))
                  (and (not rest) (= nreq ndom)))))
            (:types fin))))

(defonce ^:dynamic *check-fn-method1-checkfn* nil)
; [(U nil Type) (U nil DottedPretype) -> Type]
; takes the current rest or drest argument (only one is non-nil) and returns
; the type to assign the rest parameter
(defonce ^:dynamic *check-fn-method1-rest-type* nil)

(declare check-fn)

(add-check-method :fn
  [{:keys [env] :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)
          (vector? (:methods %))]}
  (binding [vs/*current-env* (if (:line env) env vs/*current-env*)
            vs/*current-expr* expr
            *check-fn-method1-checkfn* check
            *check-fn-method1-rest-type*
              (fn [remain-dom rest drest kws]
                {:pre [(or (Type? rest)
                           (r/DottedPretype? drest)
                           (r/KwArgs? kws))
                       (#{1} (count (filter identity [rest drest kws])))
                       (every? r/Type? remain-dom)]
                 :post [(Type? %)]}
                (cond
                  (or rest drest)
                  ; rest argument is always a nilable non-empty seq. Could
                  ; be slightly more clever here if we have a `rest`, but what about
                  ; `drest`?
                  (c/Un r/-nil 
                        (c/In (r/-hseq remain-dom
                                       :rest rest
                                       :drest drest)
                              (r/make-CountRange 1)))

                  :else (c/KwArgs->Type kws)))]
    (let [cexpr (check-fn expr (let [default-ret (ret (r/make-FnIntersection
                                                        (r/make-Function [] r/-any r/-any)))]
                                 (cond (and expected (not= r/-any (ret-t expected))) expected
                                       :else default-ret)))
          _ (when expected
              (let [actual (ret-t (expr-type cexpr))]
                (when-not (sub/subtype? actual (ret-t expected))
                  (cu/expected-error actual (ret-t expected)))))]
      cexpr)))

(declare abstract-object abstract-filter abstract-type abo)

; Difference from Typed Racket
;
; Here we also abstract types with abstract-type. We have types
; like HeterogeneousVector that contains Result's, but can also
; appear in arbitrary positions. The combination of these means
; we need to abstract and instantiate all types at function boundaries.

;[TCResult (Seqable t/Sym) -> Result]
(defn abstract-result [result arg-names]
  {:pre [(TCResult? result)
         (every? symbol? arg-names)]
   :post [(r/Result? %)]}
  ;(prn "abstract result" result arg-names)
  (u/p :check/abstract-result
  (let [keys (range (count arg-names))]
    (r/make-Result
      (abstract-type   arg-names keys (ret-t result))
      (abstract-filter arg-names keys (ret-f result))
      (abstract-object arg-names keys (ret-o result))))))

;[Type (Seqable t/Sym) -> Type]
(defn abstract-type [ids keys t]
  {:pre [(every? symbol? ids)
         (every? integer? keys)
         (r/AnyType? t)]
   :post [(r/AnyType? %)]}
  ;(prn "abstract type" ids keys t)
  (letfn [(sb-t [t] (abstract-type ids keys t))
          (sb-f [f] (abo ids keys f))
          (sb-o [o] (abstract-object ids keys o))]
    (fold/fold-rhs ::abo
       {:type-rec sb-t
        :filter-rec sb-f
        :object-rec sb-o}
      t)))

;[(Seqable t/Sym) (Seqable AnyInteger) RObject -> RObject]
(defn abstract-object [ids keys o]
  {:pre [(every? symbol? ids)
         (every? integer? keys)
         (obj/RObject? o)]
   :post [(obj/RObject? %)]}
  ;(prn "abstract-object" ids keys o)
  (letfn [ ; Difference from Typed Racket:
            ;   because abstract-result calls abstract-type, we could have
            ;   already-abstracted filters at this point. We relax the contract
            ;   to allow naturals.
            ;
            ; eg. (ann-form (fn [] (fn [b] b)) [-> [Any -> Any]])
            ;
            ;    In this type the (fn [b] b) is already abstracted as 
            ;      [Any -> Any :filters {:then (! (U nil false) 0), :else (is (U nil false) 0)} :object {:id 0}]
            ;    by the time we call abstract-result.
          (lookup [y]
            {:pre [((some-fn symbol? con/nat?) y)]
             :post [((some-fn nil? integer?) %)]}
            (some (fn [[x i]] (and (= x y) i))
                  (map vector ids keys)))]
    (cond
      (and (obj/Path? o)
           (lookup (:id o))) (update-in o [:id] lookup)
      :else obj/-empty)))

;[(Seqable t/Sym) (Seqable AnyInteger) (U NoFilter FilterSet) 
;  -> (U NoFilter FilterSet)]
(defn abstract-filter [ids keys fs]
  {:pre [(every? symbol? ids)
         (every? integer? keys)
         ((some-fn fl/NoFilter? fl/FilterSet?) fs)]
   :post [((some-fn fl/NoFilter? fl/FilterSet?) %)]}
  ;(prn "abstract filter" ids keys fs)
  (cond
    (fl/FilterSet? fs)
    (let [{fs+ :then fs- :else} fs]
      (fo/-FS (abo ids keys fs+)
              (abo ids keys fs-)))
    (fl/NoFilter? fs) (fo/-FS fl/-top fl/-top)))

(derive ::abo fold/fold-rhs-default)

(fold/add-fold-case ::abo
                    TypeFilter
                    (fn [{:keys [type path id] :as fl} {{:keys [lookup]} :locals}]
                      ;if variable goes out of scope, replace filter with fl/-top
                      (if-let [scoped (lookup id)]
                        (fo/-filter type scoped path)
                        fl/-top)))

(fold/add-fold-case ::abo
                    NotTypeFilter
                    (fn [{:keys [type path id] :as fl} {{:keys [lookup]} :locals}]
                      ;if variable goes out of scope, replace filter with fl/-top
                      (if-let [scoped (lookup id)]
                        (fo/-not-filter type scoped path)
                        fl/-top)))

;[(Seqable t/Sym) (Seqable AnyInteger) Filter -> Filter]
(defn abo [xs idxs f]
  {:pre [(every? symbol? xs)
         (every? integer? idxs)
         (fl/Filter? f)]
   :post [(fl/Filter? %)]}
  ;(prn "abo" xs idxs f)
  (letfn [(lookup [y]
            ; Difference from Typed Racket:
            ;   because abstract-result calls abstract-type, we could have
            ;   already-abstracted filters at this point. We relax the contract
            ;   to allow naturals.
            ;
            ; eg. (ann-form (fn [] (fn [b] b)) [-> [Any -> Any]])
            ;
            ;    In this type the (fn [b] b) is already abstracted as 
            ;      [Any -> Any :filters {:then (! (U nil false) 0), :else (is (U nil false) 0)} :object {:id 0}]
            ;    by the time we call abstract-result.
            {:pre [((some-fn symbol? con/nat?) y)]
             :post [((some-fn nil? integer?) %)]}
            (some (fn [[x i]] (and (= x y) i))
                  (map vector xs idxs)))
          (rec [f] (abo xs idxs f))
          (sb-t [t] (abstract-type xs idxs t))]
    (fold/fold-rhs ::abo
      {:type-rec sb-t 
       :filter-rec rec
       :locals {:lookup lookup}}
      f)))

;[FnResult -> Function]
(defn FnResult->Function [{:keys [args kws rest drest body] :as fres}]
  {:pre [(FnResult? fres)]
   :post [(r/Function? %)]}
  (u/p :check/FnResult->Function
  (let [; names of formal parameters to abstract from result type
        rest-param-name (or (first rest)
                            (first drest)
                            (first kws))
        arg-names (concat (map first args)
                          (when rest-param-name
                            [rest-param-name]))]
    (r/Function-maker
      (map second args)
      (abstract-result body arg-names)
      (when rest
        (second rest))
      (when drest
        (second drest))
      (when kws
        (second kws))))))

;TODO eliminate, only used in pfn>, not needed.
;FIXME not updated for tools.analyzer
#_(defn check-anon-fn
  "Check anonymous function, with annotated methods. methods-types
  is a (Seqable (HMap {:dom (Seqable Type) :rng (U nil Type)}))"
  [{:keys [methods] :as expr} methods-types & {:keys [poly]}]
  {:pre [(every? (con/hmap-c? :dom (con/every-c? Type?)
                              :rng (some-fn nil? Type?)
                              :rest nil? ;TODO
                              :drest nil?) ;TODO
                 methods-types)
         ((some-fn nil? 
                   (con/every-c? (con/hvector-c? symbol? r/Bounds?)))
          poly)]
   :post [(TCResult? (expr-type %))]}
  (cond
    ; named fns must be fully annotated, and are checked with normal check
    (:name expr) (let [ftype (apply r/make-FnIntersection 
                                    (doall (for [{:keys [dom rng]} methods-types]
                                             (if rng
                                               (r/make-Function dom rng)
                                               (throw (Exception. "Named anonymous functions require return type annotation"))))))
                       ftype (if poly
                               (c/Poly* (map first poly)
                                      (map second poly)
                                      ftype)
                               ftype)]

                   (check expr (ret ftype)))
    :else
    (let [;_ (prn methods methods-types expr)
          ftype (apply r/make-FnIntersection (mapv FnResult->Function 
                                                   (mapv (fn [m {:keys [dom rng]}]
                                                           (check-anon-fn-method m dom rng))
                                                         methods methods-types)))]
      (assoc expr
             expr-type (ret ftype (fo/-true-filter) obj/-empty)))))

;[Type -> '[Type (Option (Seqable t/Sym)) (Option (Seqable F)) (Option (Seqable Bounds)) (Option (U :Poly :PolyDots))]
; -> Type]
(defn unwrap-poly
  "Return a pair vector of the instantiated body of the possibly polymorphic
  type and the names used"
  [t]
  {:pre [(Type? t)]
   :post [((con/hvector-c? Type? 
                         (some-fn nil? (con/every-c? r/F?))
                         (some-fn nil? (con/every-c? r/Bounds?))
                         (some-fn nil? #{:Poly :PolyDots})) %)]}
  (cond
    (r/Poly? t) (let [new-nmes (c/Poly-fresh-symbols* t)
                      new-frees (map r/make-F new-nmes)]
                  [(c/Poly-body* new-nmes t) new-frees (c/Poly-bbnds* new-nmes t) :Poly])
    (r/PolyDots? t) (let [new-nmes (c/PolyDots-fresh-symbols* t)
                          new-frees (map r/make-F new-nmes)]
                      [(c/PolyDots-body* new-nmes t) new-frees (c/PolyDots-bbnds* new-nmes t) :PolyDots])
    :else [t nil nil nil]))

;[Type (Seqable t/Sym) (Seqable F) (U :Poly :Polydots nil) -> Type]
(defn rewrap-poly [body inst-frees bnds poly?]
  {:pre [(Type? body)
         (every? r/F? inst-frees)
         ((some-fn nil? #{:Poly :PolyDots}) poly?)]
   :post [(Type? %)]}
  (case poly?
    :Poly (c/Poly* (map :name inst-frees) bnds body)
    :PolyDots (c/PolyDots* (map :name inst-frees) bnds body)
    body))

(declare check-fn-method check-fn-method1)

; Check a sequence of methods against a (possibly polymorphic) function type.
;
; If this is a deftype method, provide a recur-target-fn to handle recur behaviour
; and validate-expected-fn to prevent expected types that include a rest argument.
;
; (ann check-fn-methods [Expr Type & :optional {:recur-target-fn (Nilable [Function -> RecurTarget])
;                                               :validate-expected-fn (Nilable [FnIntersection -> Any])}])
(defn check-fn-methods [methods expected
                        & {:keys [recur-target-fn
                                  validate-expected-fn
                                  self-name]}]
  {:pre [(r/Type? expected)
         ((some-fn nil? symbol?) self-name)]
   :post [(-> % :fni r/Type?)]}
  ; FIXME Unions of functions are not supported yet
  (let [;; FIXME This is trying to be too smart, should be a simple cond with Poly/PolyDots cases

        ; try and unwrap type enough to find function types
        exp (c/fully-resolve-type expected)
        ; unwrap polymorphic expected types
        [fin inst-frees bnds poly?] (unwrap-poly exp)
        ; once more to make sure (FIXME is this needed?)
        fin (c/fully-resolve-type fin)
        ;ensure a function type
        _ (when-not (r/FnIntersection? fin)
            (err/int-error
              (str (pr-str (prs/unparse-type fin)) " is not a function type")))
        _ (when validate-expected-fn
            (validate-expected-fn fin))
        ;collect all inferred Functions
        {:keys [inferred-fni cmethods]}
                     (lex/with-locals (when-let [name self-name] ;self calls
                                        (when-not expected 
                                          (err/int-error (str "Recursive functions require full annotation")))
                                        (assert (symbol? name) name)
                                        {name expected})
                       ;scope type variables from polymorphic type in body
                       (free-ops/with-free-mappings (case poly?
                                                      :Poly (zipmap (map r/F-original-name inst-frees)
                                                                    (map #(hash-map :F %1 :bnds %2) inst-frees bnds))
                                                      :PolyDots (zipmap (map r/F-original-name (next inst-frees))
                                                                        (map #(hash-map :F %1 :bnds %2) (next inst-frees) (next bnds)))
                                                      {})
                         (dvar-env/with-dotted-mappings (case poly?
                                                          :PolyDots {(-> inst-frees last r/F-original-name) (last inst-frees)}
                                                          {})
                           (let [method-infos (mapv (fn [method]
                                                      {:post [(seq %)]}
                                                      (check-fn-method method fin
                                                                       :recur-target-fn recur-target-fn))
                                                    methods)]
                             {:cmethods (vec (mapcat #(map :cmethod %) method-infos))
                              :inferred-fni (apply r/make-FnIntersection (mapcat #(map :ftype %) method-infos))}))))
        _ (assert (r/Type? inferred-fni))
        ;rewrap in Poly or PolyDots if needed
        pfni (rewrap-poly inferred-fni inst-frees bnds poly?)]
    {:cmethods cmethods
     :fni pfni}))

; Can take a CLJ or CLJS function expression.
;
;[FnExpr (Option Type) -> Expr]
(defn check-fn 
  "Check a fn to be under expected and annotate the inferred type"
  [{:keys [methods] :as fexpr} expected]
  {:pre [(TCResult? expected)]
   :post [(-> % expr-type TCResult?)
          (vector? (::t/cmethods %))]}
  (let [{:keys [cmethods fni]} (check-fn-methods methods (ret-t expected)
                                                 :self-name (cu/fn-self-name fexpr))]
    (assoc fexpr
           ::t/cmethods cmethods
           expr-type  (ret fni
                           (fo/-FS fl/-top fl/-bot) 
                           obj/-empty))))

(defn reconstruct-arglist [method required-params rest-param]
  (impl/impl-case
    :clojure (case (:op method) 
               :fn-method (assoc method
                                 :params (vec (concat required-params
                                                      (when rest-param
                                                        [rest-param]))))
               :method (do (assert (nil? rest-param))
                           (assert (seq required-params))
                           (assoc method
                                  :this (first required-params)
                                  :params (vec (rest required-params)))))
    :cljs (assoc method
                 :params (vec (concat required-params
                                      (when rest-param
                                        [rest-param]))))))


(defn method-required-params [method]
  (impl/impl-case
    ; :variadic? in tools.analyzer
    :clojure (case (:op method)
               (:fn-method) ((if (:variadic? method) butlast identity)
                             (:params method))
               ;include deftype's 'this' param
               (:method) (concat [(:this method)] (:params method)))
    ; :variadic in CLJS
    :cljs ((if (:variadic method) butlast identity)
           (:params method))))

(defn method-rest-param [method]
  (impl/impl-case
    ; :variadic? in tools.analyzer
    :clojure (case (:op method)
               ;deftype methods are never variadic
               (:method) nil
               (:fn-method) ((if (:variadic? method) last (constantly nil))
                             (:params method)))
    ; :variadic in CLJS
    :cljs ((if (:variadic method) last (constantly nil))
           (:params method))))

;[MethodExpr FnIntersection & :optional {:recur-target-fn (U nil [Function -> RecurTarget])}
;   -> (Seq {:ftype Function :cmethod Expr})]
(defn check-fn-method [method fin & {:keys [recur-target-fn]}]
  {:pre [(r/FnIntersection? fin)]
   :post [(seq %)
          (every? (comp r/Function? :ftype) %)
          (every? :cmethod %)]}
  (u/p :check/check-fn-method
  (let [required-params (method-required-params method)
        rest-param (method-rest-param method)
        mfns (relevant-Fns required-params rest-param fin)]
    #_(prn "relevant-Fns" (map prs/unparse-type mfns))
    (cond
      ;If no matching cases, assign parameters to Any
      (empty? mfns) [(check-fn-method1 method 
                                       (r/make-Function (repeat (count required-params) r/-any) ;doms
                                                        r/-any  ;rng 
                                                        (when rest-param ;rest
                                                          r/-any))
                                       :recur-target-fn recur-target-fn)]
      :else (vec
              (for [f mfns]
                (check-fn-method1 method f
                                  :recur-target-fn recur-target-fn)))))))

(declare ^:dynamic *recur-target*)

(defmacro with-recur-target [tgt & body]
  `(binding [*recur-target* ~tgt]
     ~@body))

(declare ->RecurTarget RecurTarget?)

(defn method-body-kw []
  (impl/impl-case
    :clojure :body
    :cljs :expr))

;check method is under a particular Function, and return inferred Function
;
; check-fn-method1 exposes enough wiring to support the differences in deftype
; methods and normal methods via `fn`.
;
; # Differences in recur behaviour
;
; deftype methods do *not* pass the first parameter (usually `this`) when calling `recur`.
;
; eg. (my-method [this a b c] (recur a b c))
;
; The behaviour of generating a RecurTarget type for recurs is exposed via the :recur-target-fn
;
;
;[MethodExpr Function -> {:ftype Function :cmethod Expr}]
(defn check-fn-method1 [method {:keys [dom rest drest kws] :as expected}
                        & {:keys [recur-target-fn]}]
  {:pre [(r/Function? expected)]
   :post [(r/Function? (:ftype %))
          (-> % :cmethod ::t/ftype r/Function?)
          (:cmethod %)]}
  (impl/impl-case
    :clojure (assert (#{:fn-method :method} (:op method))
                     (:op method))
    ; is there a better :op check here?
    :cljs (assert method))
  #_(prn "checking syntax:" (ast-u/emit-form-fn method))
  (u/p :check/check-fn-method1
  (let [body ((method-body-kw) method)
        required-params (method-required-params method)
        rest-param (method-rest-param method)

        param-obj (comp #(obj/->Path nil %)
                        :name)
        ; Difference from Typed Racket:
        ;
        ; Because types can contain abstracted names, we instantiate
        ; the expected type in the range before using it.
        ;
        ; eg. Checking against this function type:
        ;      [Any Any
        ;       -> (HVec [(U nil Class) (U nil Class)]
        ;                :objects [{:path [Class], :id 0} {:path [Class], :id 1}])]
        ;     means we need to instantiate the HVec type to the actual argument
        ;     names with open-Result.
        ;
        ;     If the actual function method is (fn [a b] ...) we check against:
        ;
        ;       (HVec [(U nil Class) (U nil Class)]
        ;              :objects [{:path [Class], :id a} {:path [Class], :id b}])
        expected-rng (apply ret
                            (open-result/open-Result 
                              (:rng expected)
                              (map param-obj
                                   (concat required-params 
                                           (when rest-param [rest-param])))))
        ;ensure Function fits method
        _ (when-not ((if (or rest drest kws) <= =) (count required-params) (count dom))
            (err/int-error (str "Checking method with incorrect number of expected parameters"
                              ", expected " (count dom) " required parameter(s) with"
                              (if rest " a " " no ") "rest parameter, found " (count required-params)
                              " required parameter(s) and" (if rest-param " a " " no ")
                              "rest parameter.")))

        _ (when-not (or (not rest-param)
                        (some identity [drest rest kws]))
            (err/int-error (str "No type for rest parameter")))

        ;;unhygienic version
        ;        ; Update filters that reference bindings that the params shadow.
        ;        ; Abstracting references to parameters is handled later in abstract-result, but
        ;        ; suffers from bugs due to un-hygienic macroexpansion (see `abstract-result`).
        ;        ; c/In short, don't shadow parameters if you want meaningful filters.
        ;        props (mapv (fn [oldp]
        ;                      (reduce (fn [p sym]
        ;                                {:pre [(fl/Filter? p)
        ;                                       (symbol? sym)]}
        ;                                (subst-filter p sym obj/-empty true))
        ;                              oldp (map :sym required-params)))
        ;                    (:props lex/*lexical-env*))

        props (:props lex/*lexical-env*)
        crequired-params (map (fn [p t] (assoc p expr-type (ret t)))
                              required-params
                              (concat dom 
                                      (repeat (or rest (:pre-type drest)))))
        _ (assert (every? (comp TCResult? expr-type) crequired-params))
        fixed-entry (map (juxt :name (comp ret-t expr-type)) crequired-params)
        ;_ (prn "checking function:" (prs/unparse-type expected))
        check-fn-method1-rest-type *check-fn-method1-rest-type*
        _ (assert check-fn-method1-rest-type "No check-fn bound for rest type")
        crest-param (when rest-param
                      (assoc rest-param
                             expr-type (ret (check-fn-method1-rest-type (drop (count crequired-params) dom) rest drest kws))))
        rest-entry (when crest-param
                     [[(:name crest-param) (ret-t (expr-type crest-param))]])
        ;_ (prn "rest entry" rest-entry)
        _ (assert ((con/hash-c? symbol? Type?) (into {} fixed-entry))
                  (into {} fixed-entry))
        _ (assert ((some-fn nil? (con/hash-c? symbol? Type?)) (when rest-entry
                                                              (into {} rest-entry))))

        ; if this fn method is a multimethod dispatch method, then infer
        ; a new filter that results from being dispatched "here"
        mm-filter (when-let [{:keys [dispatch-fn-type dispatch-val-ret]} *current-mm*]
                    (u/p :check/check-fn-method1-inner-mm-filter-calc
                    (assert (and dispatch-fn-type dispatch-val-ret))
                    (assert (not (or drest rest rest-param)))
                    (let [disp-app-ret (funapp/check-funapp nil nil 
                                                     (ret dispatch-fn-type)
                                                     (map ret dom (repeat (fo/-FS fl/-top fl/-top)) 
                                                          (map param-obj required-params))
                                                     nil)
                          ;_ (prn "disp-app-ret" disp-app-ret)
                          ;_ (prn "disp-fn-type" (prs/unparse-type dispatch-fn-type))
                          ;_ (prn "dom" dom)
                          isa-ret (tc-isa? disp-app-ret dispatch-val-ret)
                          then-filter (-> isa-ret ret-f :then)
                          _ (assert then-filter)]
                      then-filter)))
        ;_ (prn "^^^ mm-filter")

        ;_ (prn "funapp1: inferred mm-filter" mm-filter)

        env (let [env (-> lex/*lexical-env*
                          ;add mm-filter
                          (assoc-in [:props] (set (concat props (when mm-filter [mm-filter]))))
                          ;add parameters to scope
                          ;IF UNHYGIENIC order important, (fn [a a & a]) prefers rightmost name
                          (update-in [:l] merge (into {} fixed-entry) (into {} rest-entry)))
                  flag (atom true :validator con/boolean?)
                  env (if mm-filter
                        (let [t (update/env+ env [mm-filter] flag)]
                          t)
                        env)]
              (when-not @flag
                (err/int-error "Unreachable method: Local inferred to be bottom when applying multimethod filter"))
              env)

        check-fn-method1-checkfn *check-fn-method1-checkfn*
        _ (assert check-fn-method1-checkfn "No check-fn bound for method1")
        ; rng before adding new filters
        crng-nopass
        (u/p :check/check-fn-method1-chk-rng-pass1
        (binding [*current-mm* nil]
          (var-env/with-lexical-env env
            (let [rec (or ; if there's a custom recur behaviour, use the provided
                          ; keyword argument to generate the RecurTarget.
                          (when recur-target-fn
                            (recur-target-fn expected))
                          ; Otherwise, assume we are checking a regular `fn` method
                          (->RecurTarget dom rest drest nil))
                  _ (assert (RecurTarget? rec))]
              (with-recur-target rec
                (check-fn-method1-checkfn body expected-rng))))))

        ; Apply the filters of computed rng to the environment and express
        ; changes to the lexical env as new filters, and conjoin with existing filters.

        ;_ (prn "crng-nopass" crng-nopass)
        {:keys [then]} (-> crng-nopass expr-type ret-f)
        then-env (u/p :check/check-fn-method1-env+-rng
                   (update/env+ env [then] (atom true)))
        new-then-props (reduce (fn [fs [sym t]]
                                 {:pre [((con/set-c? fl/Filter?) fs)]}
                                 (if (= t (get-in env [:l sym]))
                                   ;type hasn't changed, no new propositions
                                   fs
                                   ;new type, add positive proposition
                                   (conj fs (fo/-filter t sym))))
                               #{}
                               (:l then-env))

        crng (u/p :check/check-fn-method1-add-rng-filters
               (update-in crng-nopass [expr-type :fl :then] 
                          (fn [f]
                            (apply fo/-and f new-then-props))))
        _ (binding [vs/*current-expr* body
                    ; don't override the env because :do node don't have line numbers
                    ; The :fn that contains this arity rebinds current-env.
                    #_vs/*current-env* #_(:env body)]
            (when (not (sub/subtype? (-> crng expr-type ret-t) (ret-t expected-rng)))
              (cu/expected-error (-> crng expr-type ret-t) (ret-t expected-rng))))
        rest-param-name (when rest-param
                          (:name rest-param))
        
        ftype (FnResult->Function 
                (->FnResult fixed-entry 
                            (when (and kws rest-param)
                              [rest-param-name kws])
                            (when (and rest rest-param)
                              [rest-param-name rest])
                            (when (and drest rest-param) 
                              [rest-param-name drest])
                            (expr-type crng)))
        cmethod (-> (assoc method
                           (method-body-kw) crng
                           ::t/ftype ftype)
                    (reconstruct-arglist crequired-params crest-param))
        _ (assert (vector? (:params cmethod)))
        _ (assert (every? (comp TCResult? expr-type) (:params cmethod)))]
     {:ftype ftype
      :cmethod cmethod})))

;(ann internal-special-form [Expr (U nil TCResult) -> Expr])
(u/special-do-op ::t/special-form internal-special-form)

(defmethod internal-special-form ::t/tc-ignore
  [expr expected]
  (assoc expr
         ::t/tc-ignore true
         expr-type (ret r/-any)))

(defmethod internal-special-form ::t/fn
  [{[_ _ {{fn-anns :ann} :val} :as statements] :statements fexpr :ret :as expr} expected]
  {:pre [(#{3} (count statements))]}
  (let [ann-expected
        (binding [prs/*parse-type-in-ns* (cu/expr-ns expr)]
          (apply
            r/make-FnIntersection
            (doall
              (for [{:keys [dom rest drest ret-type]} fn-anns]
                (r/make-Function (mapv (comp prs/parse-type :type) dom)
                                 (prs/parse-type (:type ret-type))
                                 (when rest
                                   (prs/parse-type (:type rest)))
                                 (when drest
                                   (r/DottedPretype1-maker
                                     (prs/parse-type (:pretype drest))
                                     (:bound drest))))))))

        ; if the t/fn statement looks unannotated, use the expected type if possible
        use-expected (if (every? (fn [{:keys [dom rest drest rng] :as f}]
                                   {:pre [(r/Function? f)]}
                                   (and (every? #{r/-any} dom)
                                        ((some-fn nil? #{r/-any}) rest)
                                        (#{r/-any} (:t rng))))
                                 (:types ann-expected))
                       (or (when expected (ret-t expected)) ann-expected)
                       ann-expected)
        cfexpr (check fexpr (ret use-expected))
        _ (when expected
            (let [actual (-> cfexpr expr-type ret-t)]
              (when-not (sub/subtype? actual (ret-t expected))
                (cu/expected-error actual (ret-t expected)))))]
    (assoc expr
           :ret cfexpr
           expr-type (expr-type cfexpr))))

(defmethod internal-special-form ::t/ann-form
  [{[_ _ {{tsyn :type} :val} :as statements] :statements frm :ret, :keys [env], :as expr} expected]
  {:pre [(#{3} (count statements))]}
  (let [parsed-ty (binding [vs/*current-env* env
                            prs/*parse-type-in-ns* (cu/expr-ns expr)]
                    (prs/parse-type tsyn))
        cty (check frm (ret parsed-ty))
        checked-type (ret-t (expr-type cty))
        _ (binding [vs/*current-expr* frm]
            (when (not (sub/subtype? checked-type parsed-ty))
              (cu/expected-error checked-type parsed-ty)))
        _ (when (and expected (not (sub/subtype? checked-type (ret-t expected))))
            (binding [vs/*current-expr* frm
                      vs/*current-env* env]
              (cu/expected-error checked-type (ret-t expected))))]
    (assoc expr
           :ret cty
           expr-type (ret parsed-ty))))

(defmethod internal-special-form ::t/loop
  [{[_ _ {{tsyns :ann} :val} :as statements] :statements frm :ret, :keys [env], :as expr} expected]
  {:pre [(#{3} (count statements))]}
  (let [tbindings (binding [prs/*parse-type-in-ns* (cu/expr-ns expr)]
                    (mapv (comp prs/parse-type :type) (:params tsyns)))
        cfrm ;loop may be nested, type the first loop found
        (binding [*loop-bnd-anns* tbindings]
          (check frm expected))]
    (assoc expr
           :ret cfrm
           expr-type (expr-type cfrm))))

(defmethod internal-special-form :default
  [expr expected]
  (err/int-error (str "No such internal form: " (ast-u/emit-form-fn expr))))

(defn internal-form? [expr]
  (u/internal-form? expr ::t/special-form))

(add-check-method :do
  [expr & [expected]]
  {:post [(TCResult? (expr-type %))
          (vector? (:statements %))]}
  (u/enforce-do-folding expr ::t/special-form)
  (cond
    (internal-form? expr)
    (internal-special-form expr expected)

    :else
    (let [exprs (vec (concat (:statements expr) [(:ret expr)]))
          nexprs (count exprs)
          [env cexprs]
          (reduce (fn [[env cexprs] [^long n expr]]
                    {:pre [(lex/PropEnv? env)
                           (integer? n)
                           (< n nexprs)]
                     ; :post checked after the reduce
                     }
                    (let [cexpr (binding [; always prefer envs with :line information, even if inaccurate
                                          vs/*current-env* (if (:line (:env expr))
                                                             (:env expr)
                                                             vs/*current-env*)
                                          vs/*current-expr* expr]
                                  (var-env/with-lexical-env env
                                    (check expr 
                                           ;propagate expected type only to final expression
                                           (when (= (inc n) nexprs)
                                             expected))))
                          res (expr-type cexpr)
                          flow (-> res r/ret-flow r/flow-normal)
                          flow-atom (atom true)
                          ;_ (prn flow)
                          ;add normal flow filter
                          nenv (update/env+ env [flow] flow-atom)
                          ;_ (prn nenv)
                          ]
  ;                        _ (when-not @flow-atom 
  ;                            (binding [; always prefer envs with :line information, even if inaccurate
  ;                                                  vs/*current-env* (if (:line (:env expr))
  ;                                                                     (:env expr)
  ;                                                                     vs/*current-env*)
  ;                                      vs/*current-expr* expr]
  ;                              (err/int-error (str "Applying flow filter resulted in local being bottom"
  ;                                                "\n"
  ;                                                (with-out-str (print-env* nenv))
  ;                                                "\nOld: "
  ;                                                (with-out-str (print-env* env))))))]
                      (if @flow-atom
                        ;reachable
                        [nenv (conj cexprs cexpr)]
                        ;unreachable
                        (do ;(prn "Detected unreachable code")
                          (reduced [nenv (conj cexprs 
                                               (assoc cexpr 
                                                      expr-type (ret (r/Bottom))))])))))
                  [lex/*lexical-env* []] (map-indexed vector exprs))
          actual-types (map expr-type cexprs)
          _ (assert (lex/PropEnv? env))
          _ (assert ((every-pred vector? seq) cexprs)) ; make sure we conj'ed in the right order
          _ (assert ((every-pred (con/every-c? r/TCResult?) seq) actual-types))]
      (assoc expr
             :statements (vec (butlast cexprs))
             :ret (last cexprs)
             expr-type (last actual-types))))) ;should be a ret already

(add-check-method :local
  [{sym :name :as expr} & [expected]]
  (binding [vs/*current-env* (:env expr)]
    (let [t (var-env/type-of sym)
          _ (when (and expected
                       (not (sub/subtype? t (ret-t expected))))
              (prs/with-unparse-ns (cu/expr-ns expr)
                (err/tc-delayed-error 
                  (str "Local binding " sym " expected type " (pr-str (prs/unparse-type (ret-t expected)))
                       ", but actual type " (pr-str (prs/unparse-type t)))
                  :form (ast-u/emit-form-fn expr))))]
      (assoc expr
             expr-type (ret t 
                            (fo/-FS (fo/-not-filter (c/Un r/-nil r/-false) sym)
                                    (fo/-filter (c/Un r/-nil r/-false) sym))
                            (obj/->Path nil sym))))))


;[Method -> t/Sym]
(defn Method->symbol [{name-sym :name :keys [declaring-class] :as method}]
  {:pre [(instance? clojure.reflect.Method method)]
   :post [((every-pred namespace symbol?) %)]}
  (symbol (name declaring-class) (name name-sym)))

(declare Java-symbol->Type)

;[t/Sym Boolean -> (Option Type)]
(defn symbol->PArray [sym nilable?]
  {:pre [(symbol? sym)
         (con/boolean? nilable?)]
   :post [((some-fn nil? r/PrimitiveArray?) %)]}
  (let [s (str sym)]
    (when (.endsWith s "<>")
      (let [^String s-nosuffix (apply str (drop-last 2 s))]
        (assert (not (.contains s-nosuffix "<>")))
        ;Nullable elements
        (let [t (Java-symbol->Type (symbol s-nosuffix) nilable?)
              c (let [c (or (when-let [rclass ((prs/clj-primitives-fn) (symbol s-nosuffix))]
                              (r/RClass->Class rclass))
                            (resolve (symbol s-nosuffix)))
                      _ (assert (class? c) s-nosuffix)]
                  c)]
          (r/PrimitiveArray-maker c t t))))))

;[t/Sym Boolean -> Type]
(defn Java-symbol->Type [sym nilable?]
  {:pre [(symbol? sym)
         (con/boolean? nilable?)]
   :post [(Type? %)]}
  (if-let [typ (or ((prs/clj-primitives-fn) sym)
                   (symbol->PArray sym nilable?)
                   (when-let [cls (resolve sym)]
                     (apply c/Un (c/RClass-of-with-unknown-params cls)
                            (when nilable?
                              [r/-nil]))))]
    typ
    (err/tc-delayed-error (str "Method symbol " sym " does not resolve to a type"))))

;[clojure.reflect.Method -> Type]
(defn- instance-method->Function [{:keys [parameter-types declaring-class return-type] :as method}]
  {:pre [(instance? clojure.reflect.Method method)]
   :post [(r/FnIntersection? %)]}
  (assert (class? (resolve declaring-class)))
  (r/make-FnIntersection (r/make-Function (concat [(c/RClass-of-with-unknown-params declaring-class)]
                                                  (doall (map #(Java-symbol->Type % false) parameter-types)))
                                          (Java-symbol->Type return-type true))))

;[clojure.reflect.Field - Type]
(defn- Field->Type [{:keys [type flags] :as field}]
  {:pre [(instance? clojure.reflect.Field field)
         flags]
   :post [(Type? %)]}
  (cond
    (:enum flags) (Java-symbol->Type type false)
    :else (Java-symbol->Type type true)))

;[clojure.reflect.Method -> Type]
(defn Method->Type [{:keys [parameter-types return-type flags] :as method}]
  {:pre [(instance? clojure.reflect.Method method)]
   :post [(r/FnIntersection? %)]}
  (let [msym (Method->symbol method)
        nparams (count parameter-types)]
    (r/make-FnIntersection (r/make-Function (doall (map (fn [[n tsym]] (Java-symbol->Type 
                                                                       tsym (mtd-param-nil/nilable-param? msym nparams n)))
                                                      (map-indexed vector
                                                                   (if (:varargs flags)
                                                                     (butlast parameter-types)
                                                                     parameter-types))))
                                          (Java-symbol->Type return-type (not (mtd-ret-nil/nonnilable-return? msym nparams)))
                                          (when (:varargs flags)
                                            (Java-symbol->Type (last parameter-types) 
                                                               (mtd-param-nil/nilable-param? msym nparams (dec nparams))))))))

;[clojure.reflect.Constructor -> Type]
(defn- Constructor->Function [{:keys [declaring-class parameter-types] :as ctor}]
  {:pre [(instance? clojure.reflect.Constructor ctor)]
   :post [(r/FnIntersection? %)]}
  (let [cls (resolve declaring-class)
        _ (when-not (class? cls)
            (err/tc-delayed-error (str "Constructor for unresolvable class " (:class ctor))))]
    (r/make-FnIntersection (r/make-Function (doall (map #(Java-symbol->Type % false) parameter-types))
                                            (c/RClass-of-with-unknown-params cls)
                                            nil nil 
                                            ;always a true value. Cannot construct nil
                                            ; or primitive false
                                            :filter (fo/-true-filter)))))

(defn Type->Classes [t]
  {:post [(every? (some-fn class? nil?) %)]}
  (let [t (c/fully-resolve-type t)]
    (cond
      (r/RClass? t) [(r/RClass->Class t)]
      (r/DataType? t) [(r/DataType->Class t)]
      (r/Value? t) [(class (.val ^Value t))]
      (r/Union? t) (mapcat Type->Classes (.types ^Union t))
      :else [Object])))

(defn possible-methods [t method-name arg-tys static?]
  {:pre [(Type? t)
         (every? Type? arg-tys)]
   :post [(every? (partial instance? clojure.reflect.Method) %)]}
  (let [cs (remove nil? (Type->Classes t))]
    (apply concat 
           (for [c cs]
             (let [{:keys [members]} (u/reflect c)]
               (filter (fn [{:keys [flags parameter-types name] :as m}]
                         (and (instance? clojure.reflect.Method m)
                              (= (contains? flags :static)
                                 (boolean static?))
                              (= (count parameter-types)
                                 (count arg-tys))
                              (= (str name)
                                 (str method-name))
                              (every? identity
                                      (map sub/subtype?
                                           arg-tys
                                           (map Java-symbol->Type parameter-types)))))
                       members))))))

(defn MethodExpr->Method [{c :class method-name :method :keys [op args] :as expr}]
  {:pre []
   :post [(or (nil? %) (instance? clojure.reflect.Method %))]}
  (when (and c 
             (#{:static-call :instance-call} op))
    (let [ms (->> (u/reflect c)
                  :members
                  (filter #(instance? clojure.reflect.Method %))
                  (filter #(#{method-name} (:name %)))
                  (filter (fn [{:keys [parameter-types]}]
                            (#{(map (comp u/reflect-friendly-sym :tag) args)} parameter-types))))]
      ;(prn "MethodExpr->Method" c ms (map :tag args))
      (first ms))))

(defn suggest-type-hints [m-or-f targett argtys & {:keys [constructor-call]}]
  {:pre [((some-fn nil? Type?) targett)
         (every? Type? argtys)]}
  (let [targett (when targett
                  (c/fully-resolve-type targett))
        cls (cond
              constructor-call (coerce/symbol->Class constructor-call)
              (r/RClass? targett) (r/RClass->Class targett))]
    (when cls
      (let [r (u/reflect cls)
            {methods clojure.reflect.Method
             fields clojure.reflect.Field
             ctors clojure.reflect.Constructor
             :as members}
            (group-by
              class
              (filter (fn [{:keys [name] :as m}] 
                        (if constructor-call
                          (instance? clojure.reflect.Constructor m)
                          (= m-or-f name)))
                      (:members r)))]
      (cond
        (empty? members) (str "\n\nTarget " (coerce/Class->symbol cls) " has no member " m-or-f)
        (seq members) (str "\n\nAdd type hints to resolve the host call."
                           (when (seq ctors)
                             (str "\n\nSuggested constructors:\n"
                                  (apply str
                                           (map 
                                             (fn [{ctor-name :name 
                                                   :keys [parameter-types flags] :as field}]
                                               (str "\n  "
                                                    (apply str (interpose " " (map name flags)))
                                                    (when (seq flags) " ")
                                                    (u/pprint-reflection-sym ctor-name) " "
                                                    "("
                                                    (apply str (interpose ", " (map u/pprint-reflection-sym parameter-types)))
                                                    ")"))
                                             ctors))))
                             (when (seq fields)
                               (str "\n\nSuggested fields:\n"
                                    (apply str
                                           (map 
                                             (fn [[clssym cls-fields]]
                                               (apply str
                                                      "\n " (u/pprint-reflection-sym clssym)
                                                      "\n \\"
                                                      (map
                                                        (fn [{field-name :name 
                                                              :keys [flags type] :as field}]
                                                          (str "\n  "
                                                               (apply str (interpose " " (map name flags)))
                                                               (when (seq flags) " ")
                                                               (u/pprint-reflection-sym type) " "
                                                               field-name))
                                                        cls-fields)))
                                             (group-by :declaring-class fields)))))
                             (when (seq methods)
                               (let [methods-by-class (group-by :declaring-class methods)]
                                 (str "\n\nSuggested methods:\n"
                                      (apply str
                                             (map
                                               (fn [[clsym cls-methods]]
                                                 (apply str
                                                        "\n " (u/pprint-reflection-sym clsym)
                                                        "\n \\"
                                                        (map 
                                                          (fn [{method-name :name 
                                                                :keys [return-type parameter-types flags] :as method}] 
                                                            (str 
                                                              "\n  "
                                                              (apply str (interpose " " (map name flags)))
                                                              (when (seq flags) " ")
                                                              (u/pprint-reflection-sym return-type) " "
                                                              method-name 
                                                              "(" 
                                                              (apply str (interpose ", " (map u/pprint-reflection-sym parameter-types))) 
                                                              ")"))
                                                          cls-methods)))
                                               methods-by-class)))))))))))

;[MethodExpr Type Any -> Expr]
(defn check-invoke-method [{c :class method-name :method :keys [args env] :as expr} expected inst?
                           & {:keys [ctarget cargs]}]
  {:pre [((some-fn nil? TCResult?) expected)]
   :post [(-> % expr-type TCResult?)
          (vector? (:args %))]}
  (binding [vs/*current-env* env]
    (let [method (MethodExpr->Method expr)
          msym (cu/MethodExpr->qualsym expr)
          rfin-type (or (when msym
                          (@mth-override/METHOD-OVERRIDE-ENV msym))
                        (when method
                          (Method->Type method)))
          ctarget (when inst?
                    (assert (:instance expr))
                    (or ctarget (check (:instance expr))))
          cargs (or cargs (mapv check args))]
      (if-not rfin-type
        (err/tc-delayed-error (str "Unresolved " (if inst? "instance" "static") 
                                 " method invocation " 
                                 (suggest-type-hints method-name 
                                                     (when ctarget
                                                       (-> ctarget expr-type ret-t))
                                                     (map (comp ret-t expr-type) cargs))
                                 ".\n\nHint: use *warn-on-reflection* to identify reflective calls")
                            :form (ast-u/emit-form-fn expr)
                            :return (merge
                                      (assoc expr 
                                             :args cargs
                                             expr-type (error-ret expected))
                                      (when ctarget {:instance ctarget})))
        (let [_ (when inst?
                  (let [target-class (resolve (:declaring-class method))
                        _ (assert (class? target-class))]
                    ;                (prn "check target" (prs/unparse-type (ret-t (expr-type ctarget)))
                    ;                     (prs/unparse-type (c/RClass-of (coerce/Class->symbol (resolve (:declaring-class method))) nil)))
                    (when-not (sub/subtype? (ret-t (expr-type ctarget)) (c/RClass-of-with-unknown-params target-class))
                      (err/tc-delayed-error (str "Cannot call instance method " (Method->symbol method)
                                               " on type " (pr-str (prs/unparse-type (ret-t (expr-type ctarget)))))
                                          :form (ast-u/emit-form-fn expr)))))
              result-type (funapp/check-funapp expr args (ret rfin-type) (map expr-type cargs) expected)
              _ (when expected
                  (when-not (sub/subtype? (ret-t result-type) (ret-t expected))
                    (err/tc-delayed-error (str "Return type of " (if inst? "instance" "static")
                                             " method " (Method->symbol method)
                                             " is " (prs/unparse-type (ret-t result-type))
                                             ", expected " (prs/unparse-type (ret-t expected)) "."
                                             (when (sub/subtype? r/-nil (ret-t result-type))
                                               (str "\n\nHint: Use `non-nil-return` and `nilable-param` to configure "
                                                    "where `nil` is allowed in a Java method call. `method-type` "
                                                    "prints the current type of a method.")))
                                        :form (ast-u/emit-form-fn expr))))]
          (merge
            (assoc expr
                   :args cargs
                   expr-type result-type)
            (when ctarget {:instance ctarget})))))))

(add-check-method :host-interop
  [{:keys [m-or-f target] :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)]}
  (let [ctarget (check target)]
    (err/tc-delayed-error (str "Unresolved host interop: " m-or-f
                             (suggest-type-hints m-or-f (-> ctarget expr-type ret-t) [])
                             "\n\nHint: use *warn-on-reflection* to identify reflective calls"
                             "\n\nin: " (ast-u/emit-form-fn expr)))
    (assoc expr 
           :target ctarget
           expr-type (error-ret expected))))

(add-check-method :static-call
  [expr & [expected]]
  {:post [(-> % expr-type TCResult?)]}
  #_(prn "static-method")
  (let [spec (static-method-special expr expected)]
    (if (not= :default spec)
      spec
      (check-invoke-method expr expected false))))

(add-check-method :instance-call
  [expr & [expected]]
  {:post [(-> % expr-type TCResult?)
          (if (contains? % :args)
            (vector? (:args %))
            true)]}
  (let [spec (instance-method-special expr expected)]
    (if (not= :default spec)
      spec
      (check-invoke-method expr expected true))))

(defn FieldExpr->Field [{c :class field-name :field :keys [op] :as expr}]
  {:pre []
   :post [(instance? clojure.reflect.Field %)]}
  (when (and c 
             (#{:static-field :instance-field} op))
    (let [fs (->> (u/reflect c)
                  :members
                  (filter #(instance? clojure.reflect.Field %))
                  (filter #(#{field-name} (:name %))))]
      (assert (#{1} (count fs)))
      (first fs))))

(add-check-method :static-field
  [expr & [expected]]
  {:post [(-> % expr-type TCResult?)]}
  (let [field (FieldExpr->Field expr)]
    (assert field)
    (assoc expr
           expr-type (ret (Field->Type field)))))

(declare unwrap-datatype)

(add-check-method :instance-field
  [{target :instance target-class :class field-name :field :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)]}
  #_(prn "instance-field:" expr)
  (binding [vs/*current-expr* expr]
   (let [field (FieldExpr->Field expr)
         cexpr (check target)]
    (if-not target-class
      ; I think target-class will never be false
      (err/tc-delayed-error (str "Call to instance field "
                               (symbol field-name)
                               " requires type hints."
                               (suggest-type-hints field-name (-> cexpr expr-type ret-t)
                                                   []))
                          :form (ast-u/emit-form-fn expr)
                          :return (assoc expr
                                         :instance cexpr
                                         expr-type (error-ret expected)))
      (let [_ (assert (class? target-class))
            fsym (symbol field-name)
            ; check that the hinted class at least matches the runtime class we expect
            _ (let [expr-ty (c/fully-resolve-type (-> cexpr expr-type ret-t))
                    cls (cond
                          (r/DataType? expr-ty) (coerce/symbol->Class (:the-class expr-ty))
                          (r/RClass? expr-ty) (coerce/symbol->Class (:the-class expr-ty)))]
                (when-not (and cls
                               ; in case target-class has been redefined
                               (sub/class-isa? cls (-> target-class coerce/Class->symbol coerce/symbol->Class)))
                  (err/tc-delayed-error (str "Instance field " fsym " expected "
                                           (pr-str target-class)
                                           ", actual " (pr-str (prs/unparse-type expr-ty)))
                                      :form (ast-u/emit-form-fn expr))))

            ; datatype fields are special
            result-t (if-let [override (when-let [dtp (dt-env/get-datatype (coerce/Class->symbol target-class))]
                                         (let [dt (if (r/Poly? dtp)
                                                    ;generate new names
                                                    (unwrap-datatype dtp (repeatedly (:nbound dtp) gensym))
                                                    dtp)
                                               _ (assert ((some-fn r/DataType? r/Record?) dt))
                                               demunged (symbol (repl/demunge (str fsym)))]
                                           (-> (c/DataType-fields* dt) (get demunged))))]
                       override
                       ; if not a datatype field, convert as normal
                       (if field
                         (Field->Type field)
                         (err/tc-delayed-error (str "Instance field " fsym " needs type hints")
                                             :form (ast-u/emit-form-fn expr)
                                             :return (r/TCError-maker))))] 
        (assoc expr
               :instance cexpr
               expr-type (ret result-t)))))))

;[t/Sym -> Type]
(defn DataType-ctor-type [sym]
  (letfn [(resolve-ctor [dtp]
            (cond
              ((some-fn r/DataType? r/Record?) dtp) 
              (let [dt dtp]
                (r/make-FnIntersection 
                  (r/make-Function (-> (c/DataType-fields* dt) vals) dt)))

              (r/TypeFn? dtp) (let [nms (c/TypeFn-fresh-symbols* dtp)
                                    bbnds (c/TypeFn-bbnds* nms dtp)
                                    body (c/TypeFn-body* nms dtp)]
                                (c/Poly* nms
                                         bbnds
                                         (free-ops/with-bounded-frees (zipmap (map r/make-F nms) bbnds)
                                           (resolve-ctor body))))

              :else (err/tc-delayed-error (str "Cannot generate constructor type for: " sym)
                                        :return r/Err)))]
    (resolve-ctor (dt-env/get-datatype sym))))

(add-check-method :instance?
  [{cls :class the-expr :target :as expr} & [expected]]
  (let [inst-of (c/RClass-of-with-unknown-params cls)
        cexpr (check the-expr)
        expr-tr (expr-type cexpr)]
    (assoc expr
           :target cexpr
           expr-type (ret (c/Un r/-true r/-false)
                          (fo/-FS (fo/-filter-at inst-of (ret-o expr-tr))
                                  (fo/-not-filter-at inst-of (ret-o expr-tr)))
                          obj/-empty))))

(defmulti new-special (fn [{:keys [class] :as expr} & [expected]] (coerce/ctor-Class->symbol class)))

;; Multimethod definition

(derive ::expected-dispatch-type fold/fold-rhs-default)

(fold/add-fold-case ::expected-dispatch-type
                    Function
                    (fn [ty _]
                      (assoc ty :rng (r/make-Result r/-any))))

;return the expected type for the dispatch fn of the given multimethod's expected type
;[Type -> Type]
(defn expected-dispatch-type [mm-type]
  {:pre [(r/AnyType? mm-type)]
   :post [(r/AnyType? %)]}
  (fold/fold-rhs ::expected-dispatch-type
                 {:type-rec expected-dispatch-type}
                 mm-type))

(defmethod new-special 'clojure.lang.MultiFn
  [{[nme-expr dispatch-expr default-expr hierarchy-expr :as args] :args :as expr} & [expected]]
  (when-not expected
    (err/int-error "clojure.lang.MultiFn constructor requires an expected type"))
  (when-not (== 4 (count args))
    (err/int-error "Wrong arguments to clojure.lang.MultiFn constructor"))
  (when-not (= (:val hierarchy-expr) #'clojure.core/global-hierarchy)
    (err/int-error "Multimethod hierarchy cannot be customised"))
  (when-not (= (:val default-expr) :default)
    (err/int-error "Non :default default dispatch value NYI"))
  (let [mm-name (:val nme-expr)
        _ (when-not (string? mm-name)
            (err/int-error "MultiFn name must be a literal string"))
        mm-qual (symbol (str (cu/expr-ns expr)) mm-name)
        ;_ (prn "mm-qual" mm-qual)
        ;_ (prn "expected ret-t" (prs/unparse-type (ret-t expected)))
        ;_ (prn "expected ret-t class" (class (ret-t expected)))
        expected-mm-disp (expected-dispatch-type (ret-t expected))
        cdisp (check dispatch-expr (ret expected-mm-disp))
        cargs [(check nme-expr)
               cdisp
               (check default-expr)
               (check hierarchy-expr)]
        _ (assert (== (count cargs) (count args)))
        _ (mm/add-multimethod-dispatch-type mm-qual (ret-t (expr-type cdisp)))]
    (assoc expr
           :args cargs
           expr-type (ret (c/In (c/RClass-of clojure.lang.MultiFn) (ret-t expected))))))

(defmethod new-special :default [expr & [expected]] ::not-special)

(defn NewExpr->Ctor [{c :class :keys [op args] :as expr}]
  {:pre [(#{:new} op)]
   :post [(or (instance? clojure.reflect.Constructor %)
              (nil? %))]}
  (let [cs (->> (u/reflect c)
                :members
                (filter #(instance? clojure.reflect.Constructor %))
                (filter #(#{(map (comp u/reflect-friendly-sym :tag) args)} (:parameter-types %))))]
    ;(prn "NewExpr->Ctor" cs)
    (first cs)))

(add-check-method :new
  [{cls :class :keys [args env] :as expr} & [expected]]
  {:post [(vector? (:args %))
          (-> % expr-type r/TCResult?)]}
  (binding [vs/*current-expr* expr
            vs/*current-env* env]
    (let [ctor (NewExpr->Ctor expr)
          spec (new-special expr expected)]
      (cond
        (not= ::not-special spec) spec
        :else
        (let [inst-types *inst-ctor-types*
              clssym (coerce/ctor-Class->symbol cls)
              cargs (mapv check args)
              ctor-fn (or (@ctor-override/CONSTRUCTOR-OVERRIDE-ENV clssym)
                          (and (dt-env/get-datatype clssym)
                               (DataType-ctor-type clssym))
                          (when ctor
                            (Constructor->Function ctor)))]
          (if-not ctor-fn
            (err/tc-delayed-error (str "Unresolved constructor invocation " 
                                     (suggest-type-hints nil nil (map (comp ret-t expr-type) cargs)
                                                         :constructor-call clssym)
                                     ".\n\nHint: add type hints"
                                     "\n\nin: " (ast-u/emit-form-fn expr))
                                :form (ast-u/emit-form-fn expr)
                                :return (assoc expr
                                               :args cargs
                                               expr-type (error-ret expected)))
            (let [ctor-fn (if inst-types
                            (inst/manual-inst ctor-fn inst-types)
                            ctor-fn)
                  ifn (ret ctor-fn)
                  ;_ (prn "Expected constructor" (prs/unparse-type (ret-t ifn)))
                  res-type (funapp/check-funapp expr args ifn (map expr-type cargs) nil)
                  _ (when (and expected (not (sub/subtype? (ret-t res-type) (ret-t expected))))
                      (cu/expected-error (ret-t res-type) (ret-t expected)))]
              (assoc expr
                     :args cargs
                     expr-type res-type))))))))

(add-check-method :throw
  [{:keys [exception] :as expr} & [expected]]
  (let [cexception (check exception)
        _ (when-not (sub/subtype? (ret-t (expr-type cexception))
                                  (c/RClass-of Throwable))
            (err/tc-delayed-error (str "Cannot throw: "
                                     (prs/unparse-type (ret-t (expr-type cexception))))))]
    (assoc expr
           :exception cexception
           expr-type (ret (c/Un)
                          (fo/-FS fl/-top fl/-top) 
                          obj/-empty
                          ;never returns normally
                          (r/-flow fl/-bot)))))

(u/defrecord RecurTarget [dom rest drest kws]
  "A target for recur"
  [(every? Type? dom)
   ((some-fn nil? Type?) rest)
   ((some-fn nil? r/DottedPretype?) drest)
   (nil? kws)]) ;TODO

(defmacro set-validator-doc! [var val-fn]
  `(set-validator! ~var (fn [a#] (assert (~val-fn a#)
                                         (str "Invalid reference state: " ~var
                                              " with value: "
                                              (pr-str a#)))
                          true)))

(defonce ^:dynamic *recur-target* nil)
(set-validator-doc! #'*recur-target* (some-fn nil? RecurTarget?))

(defn check-recur [args env recur-expr expected check]
  (binding [vs/*current-env* env]
    (let [{:keys [dom rest] :as recur-target} (if-let [r *recur-target*]
                                                r
                                                (err/int-error (str "No recur target")))
          _ (assert (not ((some-fn :drest :kw) recur-target)) "NYI")
          fixed-args (if rest
                       (butlast args)
                       args)
          rest-arg (when rest
                     (last args))
          rest-arg-type (when rest-arg
                          (impl/impl-case
                            :clojure (c/Un r/-nil (c/In (c/RClass-of clojure.lang.ISeq [rest])
                                                        (r/make-CountRange 1)))
                             :cljs (c/Un r/-nil (c/In (c/Protocol-of 'cljs.core/ISeq [rest])
                                                      (r/make-CountRange 1)))))
          cargs (mapv check args (map ret
                                      (concat dom 
                                              (when rest-arg-type
                                                [rest-arg-type]))))
          _ (when-not (and (= (count fixed-args) (count dom))
                           (= (boolean rest) (boolean rest-arg)))
              (err/tc-delayed-error 
                (str "Wrong number of arguments to recur:"
                     " Expected: " ((if rest inc identity) 
                                    (count dom))
                     " Given: " ((if rest-arg inc identity)
                                 (count fixed-args)))))]
      (assoc recur-expr
             :exprs cargs
             expr-type (ret (c/Un))))))

;Arguments passed to recur must match recur target exactly. Rest parameter
;equals 1 extra argument, either a Seqable or nil.
(add-check-method :recur
  [{args :exprs :keys [env] :as expr} & [expected]]
  {:post [(vector? (:exprs %))]}
  (check-recur args env expr expected check))

(defn check-let [bindings body expr is-loop expected & {:keys [expected-bnds check-let-checkfn]}]
  (assert check-let-checkfn "No checkfn bound for let")
  (u/p :check/check-let
       (cond
         (and is-loop (seq bindings) (not expected-bnds) )
         (do
           (err/tc-delayed-error "Loop requires more annotations")
           (assoc expr
                  expr-type (ret (c/Un))))
         :else
         (let [[env cbindings] 
               (reduce 
                 (fn [[env cexprs] [{sym :name :keys [init] :as expr} expected-bnd]]
                   {:pre [(lex/PropEnv? env)
                          init
                          sym
                          ((some-fn nil? r/Type?) expected-bnd)
                          (identical? (boolean expected-bnd) (boolean is-loop))]
                    :post [((con/hvector-c? lex/PropEnv? vector?) %)]}
                   (let [; check rhs
                         cinit (binding [vs/*current-expr* init]
                                 (var-env/with-lexical-env env
                                   (check-let-checkfn init (when is-loop
                                                             (ret expected-bnd)))))
                         cexpr (assoc expr
                                      :init cinit
                                      expr-type (expr-type cinit))
                         {:keys [t fl flow]} (expr-type cinit)
                         _ (when (and expected-bnd
                                      (not (sub/subtype? t expected-bnd)))
                             (err/tc-delayed-error 
                               (str "Loop variable " sym " initialised to "
                                    (pr-str (prs/unparse-type t))
                                    ", expected " (pr-str (prs/unparse-type expected-bnd))
                                    "\n\nForm:\n\t" (ast-u/emit-form-fn init))))
                         t (or expected-bnd t)]
                     (cond
                       (fl/FilterSet? fl)
                       (let [{:keys [then else]} fl
                             p* [(fo/-imp (fo/-not-filter (c/Un r/-nil r/-false) sym) then)
                                 (fo/-imp (fo/-filter (c/Un r/-nil r/-false) sym) else)]
                             flow-f (r/flow-normal flow)
                             flow-atom (atom true)
                             new-env (-> env
                                         ;update binding type
                                         (assoc-in [:l sym] t)
                                         ;update props
                                         (update-in [:props] #(set 
                                                                (apply concat 
                                                                       (update/combine-props p* % (atom true)))))
                                         (update/env+ [(if (= fl/-bot flow-f) fl/-top flow-f)] flow-atom))
                             _ (when-not @flow-atom 
                                 (binding [vs/*current-expr* init]
                                   (err/int-error
                                     (str "Applying flow filter resulted in local being bottom"
                                          "\n"
                                          (with-out-str (print-env* new-env))
                                          "\nOld: "
                                          (with-out-str (print-env* env))))))]
                         [new-env (conj cexprs cexpr)])

                       (fl/NoFilter? fl) (do
                                           (assert (= (r/-flow fl/-top) flow))
                                           [(-> env
                                                ;no propositions to add, just update binding type
                                                (assoc-in [:l sym] t))
                                            (conj cexprs cexpr)])
                       :else (err/int-error (str "What is this?" fl)))))
                 [lex/*lexical-env* []] (map vector bindings (or expected-bnds
                                                                 (repeat nil))))

               cbody (var-env/with-lexical-env env
                       (if is-loop
                         (binding [*recur-target* (->RecurTarget expected-bnds nil nil nil)]
                           (check-let-checkfn body expected))
                         (binding [vs/*current-expr* body]
                           (check-let-checkfn body expected))))
               ;now we return a result to the enclosing scope, so we
               ;erase references to any bindings this scope introduces
               unshadowed-ret
               (reduce (fn [ty sym]
                         {:pre [(TCResult? ty)
                                (symbol? sym)]}
                         (-> ty
                             (update-in [:t] subst-obj/subst-type sym obj/-empty true)
                             (update-in [:fl] subst-obj/subst-filter-set sym obj/-empty true)
                             (update-in [:o] subst-obj/subst-object sym obj/-empty true)
                             (update-in [:flow :normal] subst-obj/subst-filter sym obj/-empty true)))
                       (expr-type cbody)
                       (map :name bindings))]
           (assoc expr
                  :body cbody
                  :bindings cbindings
                  expr-type unshadowed-ret)))))

;unhygienic version
;(defn check-let [binding-inits body expr is-loop expected & {:keys [expected-bnds]}]
;  (assert (or (not is-loop) expected-bnds) "Loop requires more annotations")
;  (let [check-let-checkfn *check-let-checkfn*
;        env (reduce (fn [env [{{:keys [sym init]} :local-binding} expected-bnd]]
;                      {:pre [(lex/PropEnv? env)]
;                       :post [(lex/PropEnv? env)]}
;                        (let [;TODO optimisation: this should be false when aliasing like (let [a a] ...)
;                              shadows-local? (-> env :l (find sym))
;                              ; check rhs
;                              {:keys [t fl o]} (let [noshadow-ret 
;                                                     (time
;                                                     (->
;                                                       (expr-type
;                                                         (binding [vs/*current-expr* init]
;                                                           (var-env/with-lexical-env env
;                                                             (check-let-checkfn init (when is-loop
;                                                                                       (ret expected-bnd)))))))
;                                                       )
;                                                     _ (prn "^^ noshadow-ret")
;                                                     
;                                                     ;substitute previous references to sym with an empty object,
;                                                     ;as old binding is shadowed
;                                                     ; Rather expensive, only perform when necessary (if shadowing actually occurs).
;                                                     shadow-ret
;                                                     (time 
;                                                       (if shadows-local?
;                                                         (-> noshadow-ret
;                                                           (update-in [:t] subst-type sym obj/-empty true)
;                                                           (update-in [:fl] subst-filter-set sym obj/-empty true)
;                                                           (update-in [:o] subst-object sym obj/-empty true))
;                                                         noshadow-ret))
;                                                     _ (prn "^^ shadow-ret")]
;                                                 shadow-ret)
;
;                            ; update old env and new result with previous references of sym (which is now shadowed)
;                            ; replaced with an empty object
;                            ;
;                            ; This is rather expensive with large types, so only perform when another local binding
;                            ; is actually shadowed.
;                            
;                            env (time
;                                  (if shadows-local?
;                                  (-> env
;                                    (update-in [:l] #(let [sc (into {} (for [[oldsym ty] %]
;                                                                         [oldsym (subst-type ty sym obj/-empty true)]))]
;                                                       sc))
;                                    (update-in [:props] (fn [props]
;                                                          (mapv #(subst-filter % sym obj/-empty true) props))))
;                                  env))
;                              _ (prn "^^ calc shadow")]
;                        (cond
;                          (fl/FilterSet? fl)
;                          (let [{:keys [then else]} fl
;                                p* [(fo/-imp (fo/-not-filter (c/Un r/-nil r/-false) sym) then)
;                                    (fo/-imp (fo/-filter (c/Un r/-nil r/-false) sym) else)]
;                                new-env (-> env
;                                          ;update binding type
;                                          (assoc-in [:l sym] t)
;                                          ;update props
;                                          (update-in [:props] #(apply concat 
;                                                                      (update/combine-props p* % (atom true)))))]
;                            new-env)
;
;                          (fl/NoFilter? fl) (-> env
;                                           ;no propositions to add, just update binding type
;                                           (assoc-in [:l sym] t)))))
;                    lex/*lexical-env* (map vector binding-inits (or expected-bnds
;                                                                (repeat nil))))
;
;        cbody (var-env/with-lexical-env env
;                (if is-loop
;                  (binding [*recur-target* (->RecurTarget expected-bnds nil nil nil)]
;                    (check-let-checkfn body expected))
;                  (binding [vs/*current-expr* body]
;                    (check-let-checkfn body expected))))
;
;        ;now we return a result to the enclosing scope, so we
;        ;erase references to any bindings this scope introduces
;        unshadowed-type 
;        (reduce (fn [ty sym]
;                  {:pre [(TCResult? ty)
;                         (symbol? sym)]}
;                  (-> ty
;                    (update-in [:t] subst-type sym obj/-empty true)
;                    (update-in [:fl] subst-filter-set sym obj/-empty true)
;                    (update-in [:o] subst-object sym obj/-empty true)))
;                (expr-type cbody)
;                (map (comp :sym :local-binding) binding-inits))]
;    (assoc expr
;           expr-type unshadowed-type)))

(add-check-method :loop
  [{binding-inits :bindings :keys [body] :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)
          (vector? (:bindings %))]}
  (let [loop-bnd-anns *loop-bnd-anns*]
    (binding [*loop-bnd-anns* nil]
      (check-let binding-inits body expr true expected :expected-bnds loop-bnd-anns
                 :check-let-checkfn check))))

(add-check-method :let
  [{bindings :bindings :keys [body] :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)
          (vector? (:bindings %))]}
  (check-let bindings body expr false expected :check-let-checkfn check))

(defn check-letfn [bindings body letfn-expr expected check-fn-letfn]
  (let [inits-expected
        ;try and find annotations, and throw a delayed error if not found
        ;(this expression returns nil)
        (when (#{:map} (-> body :statements first :op))
          (into {}
                (for [[lb-expr type-syn-expr] 
                      (map vector 
                           (-> body :statements first :keys)
                           (-> body :statements first :vals))]
                  (impl/impl-case
                    :clojure (do
                               (assert (#{:local} (:op lb-expr)))
                               [(-> lb-expr :name)
                                (binding [prs/*parse-type-in-ns* (cu/expr-ns letfn-expr)]
                                  (prs/parse-type (ast-u/constant-expr type-syn-expr)))])
                    :cljs [(-> lb-expr :info :name)
                           (binding [prs/*parse-type-in-ns* (cu/expr-ns letfn-expr)]
                             (prs/parse-type (:form type-syn-expr)))]))))]
    (if-not inits-expected
      (err/tc-delayed-error (str "letfn requires annotation, see: "
                               (impl/impl-case :clojure 'clojure :cljs 'cljs) ".core.typed/letfn>")
                          :return (assoc letfn-expr
                                         expr-type (error-ret expected)))

      (let [cbinding-inits
            (lex/with-locals inits-expected
              (vec
                (for [{:keys [name init] :as b} bindings]
                  (let [expected-fn (inits-expected name)
                        _ (assert expected-fn (str "No expected type for " name))
                        cinit (check-fn-letfn init (ret expected-fn))]
                    (assoc b
                           :init cinit
                           expr-type (expr-type cinit))))))

            cbody (lex/with-locals inits-expected
                    (check-fn-letfn body expected))]
        (assoc letfn-expr
               :bindings cbinding-inits
               :body cbody
               expr-type (expr-type cbody))))))

; annotations are in the first expression of the body (a :do)
(add-check-method :letfn
  [{bindings :bindings :keys [body] :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)
          (vector? (:bindings %))]}
  (check-letfn bindings body expr expected check))

(add-check-method :with-meta
  [{:keys [expr meta] :as with-meta-expr} & [expected]]
  {:post [(-> % expr-type TCResult?)]}
  (let [cexpr (check expr expected)
        cmeta (check meta)]
    (assoc with-meta-expr 
           :expr cexpr
           :meta cmeta
           expr-type (expr-type cexpr))))


(add-check-method :if
  [{:keys [test then else] :as expr} & [expected]]
  {:post [(-> % expr-type TCResult?)]}
  (let [ctest (binding [vs/*current-expr* test]
                (check test))]
    (if/check-if check expr ctest then else)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Multimethods

;[Expr (Option TCResult) -> Expr]
(defn check-normal-def [{:keys [var init env] :as expr} & [expected]]
  {:post [(:init %)]}
  (let [init-provided (contains? expr :init)
        _ (assert init-provided)
        vsym (coerce/var->symbol var)
        warn-if-unannotated? (ns-opts/warn-on-unannotated-vars? (cu/expr-ns expr))
        t (var-env/lookup-Var-nofail vsym)
        check? (var-env/check-var? vsym)]
    (cond
      ; check against an expected type
      (and check? t)
      (let [cinit (when init-provided
                    (check init (ret t)))
            _ (when cinit
                (when-not (sub/subtype? (ret-t (expr-type cinit)) t)
                  (cu/expected-error (ret-t (expr-type cinit)) t))
                ; now consider this var as checked
                (var-env/add-checked-var-def vsym))]
        (assoc expr
               :init cinit
               expr-type (ret (c/RClass-of Var [t t]))))

      ; if warn-if-unannotated?, don't try and infer this var,
      ; just skip it
      (or (not check?) 
          (and warn-if-unannotated?
               (not t)))
      (do (println (when-let [line (-> expr :env :line)] 
                     (str line ": ")) 
                   "Not checking" vsym "definition")
          (flush)
          (assoc expr
                 expr-type (ret (c/RClass-of Var [(or t r/-nothing) (or t r/-any)]))))

      ;otherwise try and infer a type
      :else
      (let [_ (assert (not t))
            cinit (when init-provided
                    (check init))
            inferred (ret-t (expr-type cinit))
            _ (assert (r/Type? inferred))
            _ (when cinit
                ; now consider this var as checked
                (var-env/add-checked-var-def vsym)
                ; and add the inferred static type (might be Error)
                (var-env/add-var-type vsym inferred))]
        (assoc expr
               :init cinit
               expr-type (ret (c/RClass-of Var [inferred inferred])))))))

;TODO print a hint that `ann` forms must be wrapping in `cf` at the REPL
(add-check-method :def
  [{:keys [var init init-provided env] :as expr} & [expected]]
  ;(prn "Checking def" var)
  (let [init-provided (contains? expr :init)]
    (binding [vs/*current-env* (if (:line env) env vs/*current-env*)
              vs/*current-expr* expr]
      (cond 
        ;ignore macro definitions and declare
        (or (.isMacro ^Var var)
            (not init-provided))
        (let [actual-t (c/RClass-of Var [(r/Bottom) r/-any])
              _ (when (and expected
                           (not (sub/subtype? actual-t (ret-t expected))))
                  (cu/expected-error actual-t (ret-t expected)))]
          (assoc expr
                 expr-type (ret actual-t)))

        :else (check-normal-def expr expected)))))

;FIXME I think this hurts more than it helps
;[Type (Seqable t/Sym) -> Type]
;[Type -> Type]
(defn unwrap-datatype
  "Takes a DataType that might be wrapped in a TypeFn and returns the 
  DataType after instantiating it"
  ([dt nms]
   {:pre [((some-fn r/DataType? r/TypeFn?) dt)
          (every? symbol? nms)]
    :post [(r/DataType? %)]}
   (if (r/TypeFn? dt)
     (c/TypeFn-body* nms dt)
     dt))
  ([dt] (let [nms (when (r/TypeFn? dt)
                    (c/TypeFn-fresh-symbols* dt))]
          (unwrap-datatype dt nms))))

; don't check these implicit methods in a record
(def record-implicits
  '#{entrySet values keySet clear putAll remove put get containsValue isEmpty size without
     assoc iterator seq entryAt containsKey equiv cons empty count getLookupThunk valAt
     withMeta meta equals hashCode hasheq})

(defn get-demunged-protocol-method [unwrapped-p mungedsym]
  {:pre [(symbol? mungedsym)
         (r/Protocol? unwrapped-p)]
   :post [(Type? %)]}
  (let [munged-methods (zipmap 
                         (->> (keys (:methods unwrapped-p))
                              (map munge))
                         (vals (:methods unwrapped-p)))
        mth (get munged-methods mungedsym)
        _ (when-not mth
            (err/int-error (str "No matching annotation for protocol method implementation: "
                              mungedsym)))]
    mth))

(defn protocol-implementation-type [datatype {:keys [declaring-class] :as method-sig}]
  (let [pvar (c/Protocol-interface->on-var declaring-class)
        ptype (pcl-env/get-protocol pvar)
        mungedsym (symbol (:name method-sig))
        ans (doall (map c/fully-resolve-type (sub/datatype-ancestors datatype)))
        ;_ (prn "datatype" datatype)
        ;_ (prn "ancestors" (pr-str ans))
        ]
    (when ptype
      (let [pancestor (if (r/Protocol? ptype)
                        ptype
                        (let [[an :as relevant-ancestors] 
                              (filter 
                                (fn [a] 
                                  (and (r/Protocol? a)
                                       (= (:the-var a) pvar)))
                                ans)
                              _ (when (empty? relevant-ancestors)
                                  (err/int-error (str "Must provide instantiated ancestor for datatype "
                                                    (:the-class datatype) " to check protocol implementation: "
                                                    pvar)))
                              _ (when (< 1 (count relevant-ancestors))
                                  (err/int-error (str "Ambiguous ancestors for datatype when checking protocol implementation: "
                                                    (pr-str (vec relevant-ancestors)))))]
                          an))
            _ (assert (r/Protocol? pancestor) (pr-str pancestor))
            ;_ (prn "pancestor" pancestor)
            pargs (seq (:poly? pancestor))
            unwrapped-p (if (r/Protocol? ptype)
                          ptype
                          (c/instantiate-typefn ptype pargs))
            _ (assert (r/Protocol? unwrapped-p))
            mth (get-demunged-protocol-method unwrapped-p mungedsym)]
        (extend-method-expected datatype mth)))))

(defn datatype-method-expected [datatype method-sig]
  {:post [(r/Type? %)]}
  (or (protocol-implementation-type datatype method-sig)
      (extend-method-expected datatype (instance-method->Function method-sig))))

(defn deftype-method-members [cls]
  {:pre [(class? cls)]
   :post [(every? (fn [m] (instance? clojure.reflect.Method m)) %)]}
  (->> (u/reflect cls)
       :members
       (filter #(instance? clojure.reflect.Method %))))

(add-check-method :deftype
  [{expired-class :class-name :keys [fields methods env] :as expr} & [expected]]
  {:pre [(class? expired-class)]
   :post [(-> % expr-type TCResult?)]}
  ;TODO check fields match, handle extra fields in records
  #_(prn "Checking deftype definition:" nme)
  (binding [vs/*current-env* env]
    (let [compiled-class 
          (-> expired-class coerce/Class->symbol coerce/symbol->Class)
          _ (assert (class? compiled-class))
          nme (coerce/Class->symbol compiled-class)
          reflect-methods (deftype-method-members compiled-class)
          ;_ (prn "reflect-methods" reflect-methods)
          field-syms (map :name fields)
          _ (assert (every? symbol? field-syms))
          ; unannotated datatypes are handled below
          dtp (dt-env/get-datatype nme)
          [nms bbnds dt] (if (r/TypeFn? dtp)
                           (let [nms (c/TypeFn-fresh-symbols* dtp)
                                 bbnds (c/TypeFn-bbnds* nms dtp)
                                 body (c/TypeFn-body* nms dtp)]
                             [nms bbnds body])
                           [nil nil dtp])
          expected-fields (when dt
                            (c/DataType-fields* dt))
          expected-field-syms (vec (keys expected-fields))
          ret-expr (assoc expr
                          expr-type (ret (c/RClass-of Class)))]

      (cond
        (not dtp)
        (err/tc-delayed-error (str "deftype " nme " must have corresponding annotation. "
                                 "See ann-datatype and ann-record")
                            :return ret-expr)

        (not ((some-fn r/DataType? r/Record?) dt))
        (err/tc-delayed-error (str "deftype " nme " cannot be checked against: " (prs/unparse-type dt))
                            :return ret-expr)

        (if (r/Record? dt)
          (c/isa-DataType? compiled-class)
          (c/isa-Record? compiled-class))
        (let [datatype? (c/isa-DataType? compiled-class)]
          #_(prn (c/isa-DataType? compiled-class)
               (c/isa-Record? compiled-class)
               (r/DataType? dt)
               (r/Record? dt))
          (err/tc-delayed-error (str (if datatype? "Datatype " "Record ") nme 
                                   " is annotated as a " (if datatype? "record" "datatype") 
                                   ", should be a " (if datatype? "datatype" "record") ". "
                                   "See ann-datatype and ann-record")
                              :return ret-expr))

        (not= expected-field-syms 
              ; remove implicit __meta and __extmap fields
              (if (c/isa-Record? compiled-class)
                (drop-last 2 field-syms)
                field-syms))
        (err/tc-delayed-error (str "deftype " nme " fields do not match annotation. "
                                 " Expected: " (vec expected-field-syms)
                                 ", Actual: " (vec field-syms))
                            :return ret-expr)

        :else
        (let [check-method? (fn [inst-method]
                              (not (and (r/Record? dt)
                                        (record-implicits (symbol (:name inst-method))))))
              _ (binding [*check-fn-method1-checkfn* check
                          *check-fn-method1-rest-type* 
                          (fn [& args] 
                            (err/int-error "deftype method cannot have rest parameter"))]
                  (doseq [{:keys [env] :as inst-method} methods
                          :when (check-method? inst-method)]
                    (assert (#{:method} (:op inst-method)))
                    (when t/*trace-checker*
                      (println "Checking deftype* method: " (:name inst-method))
                      (flush))
                    (binding [vs/*current-env* env]
                      (let [method-nme (:name inst-method)
                            _ (assert (symbol? method-nme))
                            ;_ (prn "method-nme" method-nme)
                            ;_ (prn "inst-method" inst-method)
                            ;_ (prn "reflect names" (map :name reflect-methods))
                            _ (assert (:this inst-method))
                            _ (assert (:params inst-method))
                            ; minus the target arg
                            method-sig (first (filter 
                                                (fn [{:keys [name required-params]}]
                                                  (and (= (count (:parameter-types inst-method))
                                                          (count required-params))
                                                       (#{(munge method-nme)} name)))
                                                reflect-methods))]
                        (if-not (instance? clojure.reflect.Method method-sig)
                          (err/tc-delayed-error (str "Internal error checking deftype " nme " method: " method-nme
                                                   ". Available methods: " (pr-str (map :name reflect-methods))
                                                   " Method sig: " method-sig))
                          (let [expected-ifn (datatype-method-expected dt method-sig)]
                            ;(prn "method expected type" (prs/unparse-type expected-ifn))
                            ;(prn "names" nms)
                            (lex/with-locals expected-fields
                              (free-ops/with-free-mappings 
                                (zipmap (map (comp r/F-original-name r/make-F) nms) 
                                        (map (fn [nm bnd] {:F (r/make-F nm) :bnds bnd}) nms bbnds))
                                ;(prn "lexical env when checking method" method-nme lex/*lexical-env*)
                                ;(prn "frees when checking method" 
                                ;     (into {} (for [[k {:keys [name]}] clojure.core.typed.tvar-env/*current-tvars*]
                                ;                [k name])))
                                ;(prn "bnds when checking method" 
                                ;     clojure.core.typed.tvar-bnds/*current-tvar-bnds*)
                                ;(prn "expected-ifn" expected-ifn)
                                (check-fn-methods
                                  [inst-method]
                                  expected-ifn
                                  :recur-target-fn
                                  (fn [{:keys [dom] :as f}]
                                    {:pre [(r/Function? f)]
                                     :post [(RecurTarget? %)]}
                                    (->RecurTarget (rest dom) nil nil nil))
                                  :validate-expected-fn
                                  (fn [fin]
                                    {:pre [(r/FnIntersection? fin)]}
                                    (when (some #{:rest :drest :kws} (:types fin))
                                      (err/int-error
                                        (str "Cannot provide rest arguments to deftype method: "
                                             (prs/unparse-type fin))))))))))))))]
          ret-expr)))))

(add-check-method :import
  [expr & [expected]]
  (assoc expr
         expr-type (ret r/-nil)))

(add-check-method :case-test
  [{:keys [test] :as expr} & [expected]]
  (let [ctest (check test expected)]
    (assoc expr
           :test ctest
           expr-type (expr-type ctest))))


(add-check-method :case
  [{target :test :keys [tests thens default] :as expr} & [expected]]
  {:post [((every-pred vector?
                       (con/every-c? (every-pred
                                       (comp #{:case-test} :op)
                                       :test)))
           (:tests %))
          ((every-pred vector?
                       (con/every-c? (every-pred
                                       (comp #{:case-then} :op)
                                       :then)))
           (:thens %))
          (-> % expr-type r/TCResult?)]}
  ; tests have no duplicates
  (binding [vs/*current-expr* expr
            vs/*current-env* (:env expr)]
    (let [ctarget (check target)
          target-ret (expr-type ctarget)
          _ (assert (r/TCResult? target-ret))
          ctests (mapv check tests)
          tests-rets (map expr-type ctests)
          ; Can we derive extra information from 'failed'
          ; tests? Delegate to check-case-thens for future enhancements.
          cthens (case/check-case-thens check target-ret tests-rets thens expected)
          cdefault (let [flag+ (atom true :validator con/boolean?)
                         neg-tst-fl (let [val-ts (map (comp c/fully-resolve-type ret-t) tests-rets)]
                                      (if (every? r/Value? val-ts)
                                        (fo/-not-filter-at (apply c/Un val-ts)
                                                           (ret-o target-ret))
                                        fl/-top))
                         env-default (update/env+ lex/*lexical-env* [neg-tst-fl] flag+)
                         _ (when-not @flag+
                             ;; FIXME should we ignore this branch?
                             (u/tc-warning "Local became bottom when checking case default"))]
                     ;(prn "neg-tst-fl" neg-tst-fl)
                     ;(prn "env-default" env-default)
                     (var-env/with-lexical-env env-default
                       (check default expected)))
          case-result (let [type (apply c/Un (map (comp :t expr-type) (cons cdefault cthens)))
                            ; TODO
                            filter (fo/-FS fl/-top fl/-top)
                            ; TODO
                            object obj/-empty]
                        (ret type filter object))]
      (assoc expr
             :test ctarget
             :tests ctests
             :thens cthens
             :default cdefault
             expr-type case-result))))

(add-check-method :catch
  [{ecls :class, handler :body :keys [local] :as expr} & [expected]]
  (let [local-sym (:name local)
        local-type (c/RClass-of-with-unknown-params ecls)
        chandler (lex/with-locals {local-sym local-type}
                   (check handler expected))]
    (assoc expr
           expr-type (expr-type chandler))))

; filters don't propagate between components of a `try`, nor outside of it.
(add-check-method :try
  [{:keys [body catches finally] :as expr} & [expected]]
  {:post [(vector? (:catches %))
          (-> % expr-type r/TCResult?)]}
  (let [chk #(check % expected)
        cbody (chk body)
        ccatches (mapv chk catches)
        ; finally result is thrown away
        cfinally (when finally
                   (check finally))]
    (assoc expr
           :body cbody
           :catches ccatches
           :finally cfinally
           expr-type (ret (apply c/Un (-> cbody expr-type ret-t) 
                                 (map (comp ret-t expr-type) ccatches))))))

(add-check-method :set!
  [{:keys [target val env] :as expr} & [expected]]
  (binding [vs/*current-expr* expr
            vs/*current-env* env]
    (let [ctarget (check target)
          cval (check val (expr-type ctarget))
          _ (when-not (sub/subtype? 
                        (-> cval expr-type ret-t)
                        (-> ctarget expr-type ret-t))
              (err/tc-delayed-error (str "Cannot set! " (-> ctarget expr-type ret-t prs/unparse-type pr-str)
                                       " to " (-> cval expr-type ret-t prs/unparse-type pr-str))))]
      (assoc expr
             expr-type (expr-type cval)
             :target ctarget
             :val cval))))

(comment
  ;; error checking
  (cf (if 1 'a 'b) Number)
  )
