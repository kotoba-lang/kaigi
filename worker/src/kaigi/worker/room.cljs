(ns kaigi.worker.room
  "KaigiRoom — the Durable Object that serializes one meeting.

  A meeting needs exactly one writer. Two participants admitting the same
  knock, or two joins racing to publish a track, produce a roster that
  disagrees with itself; and every fix for that from the outside is a lease,
  a fencing token, or a compare-and-swap loop. A Durable Object is globally
  unique and single-threaded per id, so `one writer` is a property of where
  the code runs rather than something this file has to implement. That is the
  use CLAUDE.md sanctions: **DO as serializer and realtime room, D1 for
  storage.**

  Accordingly the authoritative *live* meeting value lives here (in memory,
  rehydrated from DO storage on wake), and anything that has to be queryable
  across meetings goes to D1 through `kaigi.worker.journal`. Putting the
  cross-meeting plane in DO storage would give one private SQLite per room —
  as many islands as there are meetings, and no way to ask a question that
  spans them.

  ## Hibernation

  Sockets are accepted with `ctx.acceptWebSocket`, so the room survives
  isolate eviction without staying warm. The participant id is carried in the
  socket's serialized attachment rather than an in-memory map, because that
  map does not survive the eviction the hibernation API exists to allow.

  ## Wire format is EDN, not JSON

  Both ends of this socket are ClojureScript, and the meeting model is
  EDN-native: `:kaigi.recording/consents` is a set, `:webrtc.room/tracks` is a
  set, admission and roles are keywords. JSON round-trips none of those
  faithfully — a set becomes an array and comes back a vector, a keyword
  becomes a string — so every message would need a translation layer whose
  only job is undoing the format. `cljs.reader/read-string` reads data only
  (no evaluation), which is what makes this safe on untrusted input."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [kaigi.model :as m]
            [kaigi.plan :as plan]
            [kaigi.signal :as sig]
            [kaigi.validate :as v]))

(def ^:const max-frame-bytes
  "Frames larger than this are dropped. An SDP offer with a dozen codecs and
  ICE candidates runs a few kilobytes; 64 KiB is generous for that and still
  far below anything that could be used to make the room allocate."
  65536)

(def ^:const id-pattern
  "Participant and meeting ids are opaque to this file but must be safe to
  embed in storage keys and echo back to other participants."
  #"^[A-Za-z0-9_.:-]{1,128}$")

(defn- valid-id? [s]
  (and (string? s) (some? (re-matches id-pattern s))))

;; ---------------------------------------------------------------------------
;; framing
;; ---------------------------------------------------------------------------

(defn- encode [m] (pr-str m))

(defn- decode
  "Parse a frame, returning nil rather than throwing on anything malformed.
  A participant sending garbage must not be able to fault the room for
  everyone else in it."
  [s]
  (try
    (let [v (reader/read-string s)]
      (when (map? v) v))
    (catch :default _ nil)))

;; ---------------------------------------------------------------------------
;; socket helpers
;; ---------------------------------------------------------------------------

(defn- attach-id! [ws id]
  (.serializeAttachment ws (encode {:kaigi/participant-id id})))

(defn- id-of
  "The participant id bound to `ws`, or nil before `hello`."
  [ws]
  (some-> (.deserializeAttachment ws) decode :kaigi/participant-id))

(defn- send!
  [ws msg]
  (try (.send ws (encode msg))
       (catch :default _ nil)))

(defn- sockets [ctx] (array-seq (.getWebSockets ctx)))

(defn- socket-for
  [ctx id]
  (first (filter #(= id (id-of %)) (sockets ctx))))

;; ---------------------------------------------------------------------------
;; the object
;; ---------------------------------------------------------------------------

(defn- config-of
  "SFU configuration read off the Worker env. Absent bindings yield an empty
  map, which `kaigi.plan/transport` reads as `:mesh` — the deployment
  degrades to a working mesh instead of failing to start."
  [env]
  {:app-id    (some-> (aget env "REALTIME_APP_ID") str)
   :app-token (some-> (aget env "REALTIME_APP_TOKEN") str)})

(defn- ice-servers
  "ICE servers for the browser. STUN alone suffices for the majority of
  networks; a symmetric-NAT or corporate-firewall pair needs TURN, and
  without `TURN_URL` those participants will fail to connect. Reported to the
  client as data so the UI can say so rather than showing a spinner forever."
  [env]
  (let [turn-url  (some-> (aget env "TURN_URL") str)
        turn-user (some-> (aget env "TURN_USERNAME") str)
        turn-cred (some-> (aget env "TURN_CREDENTIAL") str)]
    (cond-> [{:urls ["stun:stun.cloudflare.com:3478"]}]
      (and turn-url (seq turn-url))
      (conj (cond-> {:urls [turn-url]}
              (seq (str turn-user)) (assoc :username turn-user)
              (seq (str turn-cred)) (assoc :credential turn-cred))))))

(defn- persist!
  "Write the live meeting value to DO storage so a woken room resumes instead
  of dropping everyone into an empty meeting."
  [ctx mtg]
  (.put (.-storage ctx) "meeting" (encode mtg)))

(defn- restore
  [ctx]
  (-> (.get (.-storage ctx) "meeting")
      (.then (fn [s] (when s (decode s))))))

(defn- broadcast-state!
  "Push the roster to everyone, and each participant's own media plan.

  The plan is per-participant (who *you* should pull or offer to), so it
  cannot ride along with the shared roster frame — sending one participant's
  plan to another is how you end up with two peers both trying to be the
  offerer."
  [ctx env mtg sessions]
  (let [cfg (config-of env)]
    (doseq [ws (sockets ctx)]
      (let [id (id-of ws)]
        (send! ws {:t :state
                   :kaigi/meeting mtg
                   :kaigi/you id
                   :kaigi/transport (plan/transport cfg)
                   :kaigi/ice-servers (ice-servers env)})
        (when (and id (m/admitted? mtg id) (m/in-room? mtg id))
          (send! ws {:t :plan
                     :kaigi/plan (plan/plan-for mtg sessions id {} cfg)}))))))

(defn- handle-hello
  "First frame on a socket: bind an identity and knock."
  [state ctx env ws {:keys [kaigi/participant-id kaigi/name kaigi/did]}]
  (if-not (valid-id? participant-id)
    (do (send! ws {:t :error :kaigi/code :bad-id
                   :kaigi/message "participant id must match [A-Za-z0-9_.:-]{1,128}"})
        state)
    (let [mtg  (:meeting state)
          mtg' (-> mtg
                   (m/request-admission (m/participant participant-id
                                                       {:name name :did did}))
                   (m/join participant-id))]
      (attach-id! ws participant-id)
      (persist! ctx mtg')
      (broadcast-state! ctx env mtg' (:sessions state))
      (assoc state :meeting mtg'))))

(defn- handle-signal
  [state ctx ws frame]
  (let [from (id-of ws)
        mtg  (:meeting state)
        out  (sig/route mtg (assoc frame :from from :type :signal))]
    (if (sig/error? out)
      (send! ws {:t :error
                 :kaigi/code (:kaigi.signal/error out)
                 :kaigi/message (:kaigi.signal/message out)})
      (doseq [{:keys [to payload]} out]
        (when-let [peer (socket-for ctx to)]
          (send! peer {:t :signal :kaigi/from from :kaigi/payload payload}))))
    state))

(defn- handle-control
  [state ctx env ws frame]
  (let [from (id-of ws)
        mtg  (:meeting state)
        out  (sig/apply-control mtg (assoc frame :from from))]
    (if (sig/error? out)
      (do (send! ws {:t :error
                     :kaigi/code (:kaigi.signal/error out)
                     :kaigi/message (:kaigi.signal/message out)})
          state)
      (let [mtg' (:kaigi.signal/meeting out)]
        ;; A control that would corrupt the meeting is refused rather than
        ;; persisted. The model's own guards make this unreachable for the
        ;; control types routed here, which is exactly why it is checked:
        ;; the day a new control type is added, this is what catches it.
        (if (v/valid? mtg')
          (do (persist! ctx mtg')
              (broadcast-state! ctx env mtg' (:sessions state))
              (assoc state :meeting mtg'))
          (do (send! ws {:t :error :kaigi/code :would-corrupt
                         :kaigi/message (pr-str (mapv :kaigi/code (v/errors mtg')))})
              state))))))

(defn- handle-publish
  "A participant reports the track names it is publishing, so every other
  participant's plan can include them."
  [state ctx env ws {:keys [kaigi/tracks kaigi/session-id]}]
  (let [from (id-of ws)
        mtg  (reduce (fn [acc t] (m/publish-track acc from t))
                     (:meeting state)
                     (filter string? tracks))
        state' (cond-> (assoc state :meeting mtg)
                 (valid-id? session-id) (assoc-in [:sessions from] session-id))]
    (persist! ctx mtg)
    (broadcast-state! ctx env mtg (:sessions state'))
    state'))

(defn- handle-frame
  [state ctx env ws frame]
  (case (:t frame)
    :hello   (handle-hello state ctx env ws frame)
    :signal  (handle-signal state ctx ws frame)
    :control (handle-control state ctx env ws frame)
    :publish (handle-publish state ctx env ws frame)
    (do (send! ws {:t :error :kaigi/code :unknown-frame
                   :kaigi/message (str "unknown frame type " (pr-str (:t frame)))})
        state)))

;; Live state per room, keyed by the Durable Object's own id.
;;
;; NOT a single module-level cell. Distinct DO instances of the same class can
;; share an isolate, so one cell would let two different meetings overwrite
;; each other's roster — a bug that only appears under the load that puts two
;; rooms on one isolate, which is to say in production and not in a test. The
;; DO id is unique per object and stable across hibernation, so it is the
;; correct key.
(defonce ^:private room-states (atom {}))

(defn- room-key [ctx] (str (.-id ctx)))

(defn- current-state [ctx] (get @room-states (room-key ctx)))

(defn- put-state!
  [ctx state]
  (swap! room-states assoc (room-key ctx) state)
  state)

(defn- ensure-state!
  [ctx meeting-id]
  (if-let [s (current-state ctx)]
    (js/Promise.resolve s)
    (-> (restore ctx)
        (.then (fn [stored]
                 (put-state! ctx
                             {:meeting (or stored
                                           ;; A room woken with no stored
                                           ;; meeting has an open lobby and no
                                           ;; host yet: the first participant
                                           ;; to say hello becomes the host.
                                           (m/meeting meeting-id "" {:lobby :open}))
                              :sessions {}}))))))

(defn- adopt-first-host
  "The first participant to arrive in a room with no host becomes it.

  A meeting created out of band (from a calendar entry, from a kaisha
  channel) knows its host up front and this never fires. A bare room link
  does not, and the alternative is a meeting nobody can moderate.

  A room with no host yet is constructed by `ensure-state!` with a blank host
  id, which `kaigi.model/meeting` faithfully turns into a participant keyed by
  the empty string. That placeholder MUST be dropped here: leaving it behind
  puts a nameless ghost in every roster and in every participant grid, and it
  is invisible to both the unit suite (which never builds a hostless meeting)
  and `kaigi.validate` (the placeholder is a structurally valid participant).
  The end-to-end run against a real Durable Object is what surfaced it."
  [state id]
  (let [mtg (:meeting state)]
    (if (str/blank? (:kaigi/host mtg))
      (assoc state :meeting
             (-> mtg
                 (assoc :kaigi/host id)
                 (m/leave "")
                 (update :kaigi/participants dissoc "")
                 (assoc-in [:kaigi/participants id]
                           (assoc (m/participant id {:role :host})
                                  :kaigi.participant/admission :admitted))))
      state)))

(defn on-fetch
  "WebSocket upgrade for one room."
  [ctx env request]
  (if (not= "websocket" (some-> (.get (.-headers request) "Upgrade") str/lower-case))
    (js/Promise.resolve (js/Response. "expected websocket" #js {:status 426}))
    (let [url (js/URL. (.-url request))
          meeting-id (or (.get (.-searchParams url) "meeting") "kaigi")]
      (-> (ensure-state! ctx meeting-id)
          (.then (fn [_]
                   (let [pair (js/WebSocketPair.)
                         client (aget pair "0")
                         server (aget pair "1")]
                     (.acceptWebSocket ctx server)
                     (js/Response. nil #js {:status 101 :webSocket client}))))))))

(defn on-message
  [ctx env ws message]
  (when (and (string? message) (<= (count message) max-frame-bytes))
    (when-let [frame (decode message)]
      (-> (ensure-state! ctx "kaigi")
          (.then (fn [state]
                   (let [state (if (and (= :hello (:t frame))
                                        (valid-id? (:kaigi/participant-id frame)))
                                 (adopt-first-host state (:kaigi/participant-id frame))
                                 state)]
                     (put-state! ctx (handle-frame state ctx env ws frame)))))))))

(defn on-close
  "A closed socket leaves the media room but keeps its admission — a dropped
  connection is not an ejection (`kaigi.model/leave`)."
  [ctx env ws]
  (when-let [id (id-of ws)]
    (when-let [s (current-state ctx)]
      (let [state (put-state! ctx (-> s
                                      (update :meeting m/leave id)
                                      (update :sessions dissoc id)))]
        (persist! ctx (:meeting state))
        (broadcast-state! ctx env (:meeting state) (:sessions state)))))
  (try (.close ws) (catch :default _ nil)))
