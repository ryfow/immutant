(ns tx.core
  (:use clojure.test)
  (:require [immutant.xa :as ixa]
            [immutant.cache :as ic]
            [immutant.messaging :as imsg]
            [clojure.java.jdbc :as sql]
            [tx.scope]))

;;; Create a JMS queue
(imsg/start "/queue/test")
(imsg/start "/queue/remote-test")
(imsg/start "/queue/trigger")

;;; And an Infinispan cache
(def cache (ic/cache "test"))

;;; And some transactional databases
(defonce h2 (ixa/datasource "h2" {:adapter "h2" :database "mem:foo"}))
(defonce oracle (ixa/datasource "oracle" {:adapter "oracle"
                                          :host "oracle.cpct4icp7nye.us-east-1.rds.amazonaws.com"
                                          :username "myuser"
                                          :password "mypassword"
                                          :database "mydb"}))
(defonce mysql (ixa/datasource "mysql" {:adapter "mysql"
                                        :host "mysql.cpct4icp7nye.us-east-1.rds.amazonaws.com"
                                        :username "myuser"
                                        :password "mypassword"
                                        :database "mydb"}))
(defonce postgres (ixa/datasource "postgres" {:adapter "postgresql"
                                              :username "myuser"
                                              :password "mypassword"
                                              :database "mydb"}))

;;; Helper methods to verify database activity
(defn write-thing-to-db [spec name]
  (sql/with-connection spec
    (sql/insert-records :things {:name name})))
(defn read-thing-from-db [spec name]
  (sql/with-connection spec
    (sql/with-query-results rows ["select name from things where name = ?" name]
      (first rows))))
(defn count-things-in-db [spec]
  (sql/with-connection spec
    (sql/with-query-results rows ["select count(*) c from things"]
      (int ((first rows) :c)))))

(defn write-the-data [db]
  (write-thing-to-db {:datasource (var-get db)} "kiwi")
  (imsg/publish "/queue/test" "kiwi")
  (imsg/publish "/queue/remote-test" "starfruit" :host "localhost" :port 5445)
  (ic/put cache :a 1))

(defn attempt-transaction [db & [f]]
  (try
    (ixa/transaction
     (write-the-data db)
     (if f (f)))
    (catch Exception _)))

(defn listener [m]
  (tx.core/write-the-data (:db m))
  (if (:f m) ((:f m))))

(imsg/listen "/queue/trigger" listener)

(defn trigger-listener
  [db & [f]]
  (imsg/publish "/queue/trigger" {:db db :f f}))

(defn verify-transaction-success [db]
  (is (= "kiwi" (imsg/receive "/queue/test" :timeout 2000)))
  (is (= "starfruit" (imsg/receive "/queue/remote-test")))
  (let [ds (var-get db)]
    (is (= "kiwi" (:name (read-thing-from-db {:datasource ds} "kiwi"))))
    (is (= 1 (count-things-in-db {:datasource ds}))))
  (is (= 1 (:a cache))))

(defn verify-transaction-failure [db]
  (is (nil? (imsg/receive "/queue/test" :timeout 2000)))
  (is (nil? (imsg/receive "/queue/remote-test" :timeout 2000)))
  (let [ds (var-get db)]
    (is (nil? (read-thing-from-db {:datasource ds} "kiwi")))
    (is (= 0 (count-things-in-db {:datasource ds}))))
  (is (nil? (:a cache))))

(defn define-tests [db f]
  (eval `(let [ds-sym# (resolve (symbol ~db))]
           (deftest ~(gensym (str "commit-" db)) 
             (~f ds-sym#)
             (verify-transaction-success ds-sym#))
           (deftest ~(gensym (str "rollback-" db))
             (~f ds-sym# #(throw (Exception. "rollback")))
             (verify-transaction-failure ds-sym#)))))

(defn db-fixture [db]
  (fn [f]
    (try
      (sql/with-connection {:datasource db}
        (try (sql/drop-table :things) (catch Exception _))
        (sql/create-table :things [:name "varchar(50)"]))
      (catch Exception _))
    (f)))

(defn cache-fixture [f]
  (ic/delete-all cache)
  (f))

(defn testes [nss f & dbs]
  (binding [*ns* *ns*]
    (in-ns 'tx.core)
    (apply use-fixtures :each cache-fixture (map #(db-fixture (var-get (resolve (symbol %)))) dbs))
    (doseq [db dbs]
      (define-tests db f))
    (apply run-tests nss)))

(def explicit-transaction-testes (partial testes ['tx.core 'tx.scope] attempt-transaction))
(def listen-testes (partial testes ['tx.core] trigger-listener))