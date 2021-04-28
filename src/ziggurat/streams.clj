(ns ziggurat.streams
  (:require [clojure.tools.logging :as log]
            [mount.core :as mount :refer [defstate]]
            [ziggurat.channel :as chl]
            [ziggurat.config :refer [get-in-config ziggurat-config build-streams-config-properties]]
            [ziggurat.header-transformer :as header-transformer]
            [ziggurat.mapper :refer [mapper-func]]
            [ziggurat.message-payload :refer [->MessagePayload]]
            [ziggurat.metrics :as metrics]
            [ziggurat.timestamp-transformer :as timestamp-transformer]
            [ziggurat.tracer :refer [tracer]]
            [ziggurat.util.map :as umap])
  (:import [io.opentracing.contrib.kafka TracingKafkaUtils]
           [io.opentracing.contrib.kafka.streams TracingKafkaClientSupplier]
           [io.opentracing.tag Tags]
           [java.time Duration]
           [java.util Properties]
           [java.util.regex Pattern]
           [org.apache.kafka.common.serialization Serdes]
           [org.apache.kafka.common.utils SystemTime]
           [org.apache.kafka.streams KafkaStreams KafkaStreams$State StreamsConfig StreamsBuilder Topology]
           [org.apache.kafka.streams.kstream JoinWindows ValueMapper TransformerSupplier ValueJoiner ValueTransformerSupplier]
           [org.apache.kafka.streams.state.internals KeyValueStoreBuilder RocksDbKeyValueBytesStoreSupplier]
           [ziggurat.timestamp_transformer IngestionTimeExtractor]))

(def default-config-for-stream
  {:buffered-records-per-partition     10000
   :commit-interval-ms                 15000
   :auto-offset-reset-config           "latest"
   :oldest-processed-message-in-s      604800
   :changelog-topic-replication-factor 3
   :default-api-timeout-ms-config      60000
   :session-timeout-ms-config          60000
   :consumer-type                      :default
   :default-key-serde                  "org.apache.kafka.common.serialization.Serdes$ByteArraySerde"
   :default-value-serde                "org.apache.kafka.common.serialization.Serdes$ByteArraySerde"})

(defn- validate-auto-offset-reset-config
  [auto-offset-reset-config]
  (when-not (contains? #{"latest" "earliest" nil} auto-offset-reset-config)
    (throw (ex-info "Stream offset can only be latest or earliest" {:offset auto-offset-reset-config}))))

(defn- properties [{:keys
                    [auto-offset-reset-config] :as m}]
  (validate-auto-offset-reset-config auto-offset-reset-config)
  (doto (build-streams-config-properties m)
    (.put StreamsConfig/DEFAULT_TIMESTAMP_EXTRACTOR_CLASS_CONFIG IngestionTimeExtractor)))

(defn- log-and-report-metrics [topic-entity message]
  (let [service-name                  (:app-name (ziggurat-config))
        topic-entity-name             (name topic-entity)
        additional-tags               {:topic_name topic-entity-name}
        message-read-metric-namespace "message"
        metric-namespaces             [service-name topic-entity-name message-read-metric-namespace]
        multi-namespaces              [metric-namespaces [message-read-metric-namespace]]
        metric                        "read"]
    (metrics/multi-ns-increment-count multi-namespaces metric additional-tags))
  message)

(defn- stream-joins-log-and-report-metrics [topic topic-entity message]
  (let [service-name                  (:app-name (ziggurat-config))
        topic-entity-name             (name topic-entity)
        additional-tags               {:topic-name topic-entity-name :input-topic topic :app-name service-name}
        message-read-metric-namespace "stream-joins-message"
        multi-namespaces              [[message-read-metric-namespace]]
        metric                        "read"]
    (metrics/multi-ns-increment-count multi-namespaces metric additional-tags))
  message)

(defn- value-mapper [f]
  (reify ValueMapper
    (apply [_ v] (f v))))

(defn- map-values [mapper-fn stream-builder]
  (.mapValues stream-builder (value-mapper mapper-fn)))

(defn- timestamp-transformer-supplier
  [metric-namespaces oldest-processed-message-in-s additional-tags]
  (reify TransformerSupplier
    (get [_] (timestamp-transformer/create metric-namespaces oldest-processed-message-in-s additional-tags))))

(defn- header-transformer-supplier
  []
  (reify ValueTransformerSupplier
    (get [_] (header-transformer/create))))

(defn- timestamp-transform-values [topic-entity-name oldest-processed-message-in-s stream-builder]
  (let [service-name           (:app-name (ziggurat-config))
        delay-metric-namespace "message-received-delay-histogram"
        metric-namespaces      [service-name topic-entity-name delay-metric-namespace]
        additional-tags        {:topic_name topic-entity-name}]
    (.transform stream-builder (timestamp-transformer-supplier metric-namespaces oldest-processed-message-in-s additional-tags) (into-array String []))))

(defn- stream-joins-delay-metric [topic topic-entity-name oldest-processed-message-in-s stream-builder]
  (let [service-name           (:app-name (ziggurat-config))
        delay-metric-namespace "stream-joins-message-received-delay-histogram"
        metric-namespaces      [service-name topic-entity-name delay-metric-namespace]
        additional-tags        {:topic-name topic-entity-name :input-topic topic :app-name service-name}]
    (.transform stream-builder (timestamp-transformer-supplier metric-namespaces oldest-processed-message-in-s additional-tags) (into-array String []))))

(defn- header-transform-values [stream-builder]
  (.transformValues stream-builder (header-transformer-supplier) (into-array String [])))

(declare stream)

(defn close-stream
  [topic-entity stream]
  (let [stream-state (.state stream)]
    (if (= stream-state KafkaStreams$State/NOT_RUNNING)
      (log/error
       (str
        "Kafka stream cannot be stopped at the moment, current state is "
        stream-state))
      (do
        (.close stream)
        (log/info
         (str "Stopping Kafka stream with topic-entity " topic-entity))))))

(defn stop-stream [topic-entity]
  (let [stream (get stream topic-entity)]
    (if stream
      (close-stream topic-entity stream)
      (log/error (str "No Kafka stream with provided topic-entity: " topic-entity " exists.")))))

(defn stop-streams [streams]
  (log/debug "Stopping Kafka streams")
  (doseq [[topic-entity stream] streams]
    (close-stream topic-entity stream)))

(defn- traced-handler-fn [handler-fn channels message topic-entity]
  (let [parent-ctx (TracingKafkaUtils/extractSpanContext (:headers message) tracer)
        span       (as-> tracer t
                     (.buildSpan t "Message-Handler")
                     (.withTag t (.getKey Tags/SPAN_KIND) Tags/SPAN_KIND_CONSUMER)
                     (.withTag t (.getKey Tags/COMPONENT) "ziggurat")
                     (if (nil? parent-ctx)
                       t
                       (.asChildOf t parent-ctx))
                     (.start t))]
    (try
      ((mapper-func handler-fn channels) (assoc (->MessagePayload (:value message) topic-entity) :headers (:headers message)))
      (finally
        (.finish span)))))

(defn- join-streams
  [oldest-processed-message-in-s topic-entity stream-1 stream-2]
  (let [topic-entity-name (name topic-entity)
        topic-name-1      (:topic-name stream-1)
        topic-name-2      (:topic-name stream-2)
        topic-key-1       (:topic-key stream-1)
        topic-key-2       (:topic-key stream-2)
        cfg-1             (:cfg stream-1)
        cfg-2             (:cfg stream-2)
        strm-1            (->> (:stream stream-1)
                               (stream-joins-delay-metric topic-name-1 topic-entity-name oldest-processed-message-in-s)
                               (map-values #(stream-joins-log-and-report-metrics topic-name-1 topic-entity-name %)))
        strm-2            (->> (:stream stream-2)
                               (stream-joins-delay-metric topic-name-2 topic-entity-name oldest-processed-message-in-s)
                               (map-values #(stream-joins-log-and-report-metrics topic-name-2 topic-entity-name %)))
        join-window-ms    (JoinWindows/of (Duration/ofMillis (:join-window-ms cfg-1)))
        join-type         (:join-type cfg-1)
        value-joiner      (reify ValueJoiner
                            (apply [_ left right]
                              {topic-key-1 left topic-key-2 right}))
        out-strm          (if cfg-1
                            (case join-type
                              :left  (.leftJoin strm-1 strm-2 value-joiner join-window-ms)
                              :outer (.outerJoin strm-1 strm-2 value-joiner join-window-ms)
                              (.join strm-1 strm-2 value-joiner join-window-ms))
                            strm-1)]
    {:stream out-strm
     :cfg    cfg-2}))

(defn- stream-joins-topology [handler-fn {:keys [input-topics join-cfg oldest-processed-message-in-s]} topic-entity channels]
  (log/warn "Stream joins config found for topic entity " topic-entity "."
            "Please refer to the README doc on how to enable alpha features.")
  (when (get-in-config [:alpha-features :stream-joins])
    (log/warn "[Alpha Feature]: Stream joins is an alpha feature."
              "Please use it only after understanding its risks and implications."
              "Its contract can change in the future releases of Ziggurat."
              "Please refer to the README doc for more information.")
    (let [builder          (StreamsBuilder.)
          stream-map       (map (fn [[topic-key topic-value] [_ cfg]]
                                  (let [topic-name (:name topic-value)]
                                    {:stream (.stream builder topic-name) :cfg cfg :topic-name topic-name :topic-key topic-key})) input-topics (assoc join-cfg :end nil))
          {stream :stream} (reduce (partial join-streams oldest-processed-message-in-s topic-entity) stream-map)]
      (->> stream
           (header-transform-values)
           (map-values #(traced-handler-fn handler-fn channels % topic-entity)))
      (.build builder))))

(defn- topology [handler-fn {:keys [origin-topic oldest-processed-message-in-s]} topic-entity channels]
  (let [builder           (StreamsBuilder.)
        topic-entity-name (name topic-entity)
        topic-pattern     (Pattern/compile origin-topic)]
    (->> (.stream builder topic-pattern)
         (timestamp-transform-values topic-entity-name oldest-processed-message-in-s)
         (header-transform-values)
         (map-values #(log-and-report-metrics topic-entity-name %))
         (map-values #(traced-handler-fn handler-fn channels % topic-entity)))
    (.build builder)))

(defn- start-stream* [handler-fn stream-config topic-entity channels]
  (let [topology-fn (case (:consumer-type stream-config)
                      :stream-joins stream-joins-topology
                      topology)
        top         (topology-fn handler-fn stream-config topic-entity channels)]

    (when-not (nil? top)
      (KafkaStreams. ^Topology top
                     ^Properties (properties stream-config)
                     (new TracingKafkaClientSupplier tracer)))))

(defn- merge-consumer-type-config
  [config]
  (case (:consumer-type config)
    :stream-joins (assoc config :consumer-type (:consumer-type config))
    (assoc config :consumer-type :default)))

(defn- handle-uncaught-exception
  [^Thread thread ^Throwable error]
  (log/infof "Ziggurat Uncaught Handler Invoked for Thread: [%s] because of this exception: [%s]"
             (.getName thread) (.getMessage error)))

(defn- uncaught-exception-handling-enabled? []
  (or (:enable-streams-uncaught-exception-handling (ziggurat-config))
      false))

(defn start-streams
  ([stream-routes]
   (start-streams stream-routes (ziggurat-config)))
  ([stream-routes stream-configs]
   (reduce (fn [streams stream]
             (let [topic-entity     (first stream)
                   snd              (second stream)
                   topic-handler-fn (:handler-fn snd)
                   channels         (chl/get-keys-for-topic stream-routes topic-entity)
                   stream-config    (-> stream-configs
                                        (get-in [:stream-router topic-entity])
                                        (merge-consumer-type-config)
                                        (umap/deep-merge default-config-for-stream))
                   stream           (start-stream* topic-handler-fn stream-config topic-entity channels)]
               (when-not (nil? stream)
                 (when (uncaught-exception-handling-enabled?)
                   (.setUncaughtExceptionHandler stream
                                                 (reify Thread$UncaughtExceptionHandler
                                                   (^void uncaughtException [_ ^Thread thread, ^Throwable error]
                                                     (handle-uncaught-exception thread error topic-entity)))))
                 (.start stream)
                 (assoc streams topic-entity stream))))
           {}
           stream-routes)))

(defn- stream-object-evaluator
  [new-stream-map stream-object topic-entity]
  (let [stream-topic-entity (first stream-object)
        stream              (second stream-object)]
    (if (and (= (.state stream) KafkaStreams$State/NOT_RUNNING)
             (= stream-topic-entity topic-entity))
      (let [new-stream-object-map (start-streams
                                   (hash-map stream-topic-entity
                                             (get (:stream-routes (mount/args))
                                                  stream-topic-entity)))]
        (assoc new-stream-map
               stream-topic-entity
               (get new-stream-object-map stream-topic-entity)))
      (assoc new-stream-map stream-topic-entity stream))))

(defn start-stream
  [topic-entity]
  (if (seq (select-keys ziggurat.streams/stream [topic-entity]))
    (mount/start-with-states {#'ziggurat.streams/stream
                              {:start (fn []
                                        (reduce (fn
                                                  [new-stream-map stream-object]
                                                  (stream-object-evaluator new-stream-map stream-object topic-entity))
                                                {}
                                                stream))
                               :stop  (fn [] (stop-streams stream))}})
    (log/error (str "No Kafka stream with provided topic-entity: " topic-entity " exists."))))

(defstate stream
  :start (do (log/info "Starting Kafka streams")
             (start-streams (:stream-routes (mount/args)) (ziggurat-config)))
  :stop (do (log/info "Stopping Kafka streams")
            (stop-streams stream)))
