(ns kaigi.app
  "The browser half: real media over a real PeerConnection.

  Responsibilities, and nothing else:
  1. hold the WebSocket to the room and speak the EDN frame protocol,
  2. capture local media and keep one `RTCPeerConnection` per peer that
     `kaigi.plan` says to hold,
  3. re-render `kaigi.ui/console` from each new roster and swap it in,
  4. translate `data-act` clicks into control frames.

  Every decision it acts on comes from the pure layer: who to connect to and
  which side offers is `kaigi.plan/mesh-plan`, what a control is allowed to do
  is `kaigi.signal`, and the roster is a `kaigi.model` value. This namespace
  contributes no policy.

  ## Offer direction is taken from the plan, not negotiated

  The room tells each participant whether it is the `:offerer` or the
  `:answerer` for each peer, derived from a total order over participant ids.
  Both sides compute the same answer independently, so there is no
  \"perfect negotiation\" rollback logic here and no state in which both peers
  hold a local offer. That failure — signaling glare — presents as a call that
  never connects, with no error on either side.

  ## Video elements outlive renders

  The console markup is re-rendered on every roster change and swapped in via
  `innerHTML`. A `<video>` inside that subtree would be destroyed and rebuilt,
  losing `srcObject` and restarting playback — so a mute toggle would flicker
  everyone's video. Media elements are therefore created once, kept in
  `video-elements`, and re-parented into the freshly rendered tiles after each
  swap."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [kaigi.model :as m]
            [kaigi.plan :as plan]
            [kaigi.ui :as kui]
            [kotoba-ui.core :as ui]))

;; ---------------------------------------------------------------------------
;; state
;; ---------------------------------------------------------------------------

(defonce state
  (atom {:socket nil
         :me nil
         :meeting nil
         :transport :mesh
         :ice-servers []
         :warning nil
         :local-stream nil
         :peers {}            ;; peer-id -> RTCPeerConnection
         :remote-streams {}}))  ;; peer-id -> MediaStream

(defonce video-elements (atom {}))

(defn- meeting-id
  "Room id from `?meeting=` or the hash, defaulting to `lobby`. Read from the
  URL so a meeting is a shareable link and nothing else."
  []
  (let [p (.get (.-searchParams (js/URL. (.. js/window -location -href))) "meeting")
        h (str/replace (or (.. js/window -location -hash) "") #"^#" "")]
    (or (not-empty p) (not-empty h) "lobby")))

(defn- my-id
  "A per-tab identity. `?me=` when given (the end-to-end harness needs to name
  its tabs); otherwise a random id, because two tabs sharing an id would
  appear as one participant fighting over one roster slot."
  []
  (or (not-empty (.get (.-searchParams (js/URL. (.. js/window -location -href))) "me"))
      (str "u-" (.slice (.toString (js/Math.random) 36) 2 8))))

;; ---------------------------------------------------------------------------
;; wire
;; ---------------------------------------------------------------------------

(defn- send!
  [frame]
  (when-let [ws (:socket @state)]
    (when (= 1 (.-readyState ws))
      (.send ws (pr-str frame)))))

(defn- signal!
  [to payload]
  (send! {:t :signal :to to :payload payload}))

(defn- control!
  ([type] (control! type nil nil))
  ([type target] (control! type target nil))
  ([type target payload]
   (send! (cond-> {:t :control :type type}
            target  (assoc :target target)
            (some? payload) (assoc :payload payload)))))

;; ---------------------------------------------------------------------------
;; media elements
;; ---------------------------------------------------------------------------

(defn- video-for
  "The (stable) `<video>` for `id`, created on first use.

  Local video is muted and mirrored: an unmuted local element feeds the
  microphone back through the speakers, which is the loudest possible bug."
  [id self?]
  (or (get @video-elements id)
      (let [el (js/document.createElement "video")]
        (set! (.-autoplay el) true)
        (set! (.-playsInline el) true)
        (set! (.-muted el) self?)
        (when self? (set! (.. el -style -transform) "scaleX(-1)"))
        (swap! video-elements assoc id el)
        el)))

(defn- attach-videos!
  "Move each participant's video element into its freshly rendered tile.

  Called after every render. `appendChild` on an element that is already
  elsewhere moves it rather than copying, so this is idempotent and does not
  interrupt playback."
  []
  (let [me (:me @state)]
    (doseq [[id el] @video-elements]
      (when-let [slot (js/document.querySelector
                       (str "[data-kaigi-participant=\"" id "\"]"))]
        (when-not (identical? (.-parentNode el) slot)
          (.appendChild slot el))))
    ;; local stream may be captured before the first roster arrives
    (when-let [s (:local-stream @state)]
      (let [el (video-for me true)]
        (when-not (identical? (.-srcObject el) s)
          (set! (.-srcObject el) s))))))

;; ---------------------------------------------------------------------------
;; render
;; ---------------------------------------------------------------------------

(defn render!
  []
  (let [{:keys [meeting me transport ice-servers warning]} @state]
    (when meeting
      (let [html (ui/->html (kui/console {:meeting meeting :me me
                                          :transport transport
                                          :ice-servers ice-servers
                                          :warning warning}))]
        ;; Replace the shell's content, not the whole document: the SSR page
        ;; already carries the theme CSS in <head>, and re-writing <head>
        ;; would re-parse the stylesheet on every roster change.
        (when-let [root (js/document.getElementById "kaigi-console")]
          (set! (.-outerHTML root) html))
        (attach-videos!)))))

;; ---------------------------------------------------------------------------
;; peer connections
;; ---------------------------------------------------------------------------

(defn- ice-transport-policy
  "`\"relay\"` when the URL says `?ice=relay`, otherwise `\"all\"`.

  A test seam, and a deliberate one: with both browsers on the same machine a
  call connects over host candidates and never touches the relay, so a TURN
  deployment can be completely broken and every local test still passes.
  Forcing relay-only is the only way to prove the relay path actually carries
  media. It doubles as a field diagnostic — `?ice=relay` answers \"is TURN
  working from here?\" without guessing."
  []
  (if (= "relay" (.get (.-searchParams (js/URL. (.. js/window -location -href))) "ice"))
    "relay"
    "all"))

(defn- ice-config
  []
  #js {:iceServers (clj->js (mapv (fn [s]
                                    (cond-> {:urls (:urls s)}
                                      (:username s) (assoc :username (:username s))
                                      (:credential s) (assoc :credential (:credential s))))
                                  (:ice-servers @state)))
       :iceTransportPolicy (ice-transport-policy)})

(defn- ensure-peer!
  "The `RTCPeerConnection` for `peer-id`, created on first use with the local
  tracks already attached."
  [peer-id]
  (or (get-in @state [:peers peer-id])
      (let [pc (js/RTCPeerConnection. (ice-config))
            stream (js/MediaStream.)]
        ;; ICE gathering failures are otherwise completely silent: a TURN
        ;; server that is unreachable, or that rejects the credential, produces
        ;; no candidate and no exception — the call just never connects. This
        ;; event carries the STUN/TURN error code and the URL that produced it,
        ;; which is the difference between "TURN is broken" and "the network is
        ;; slow".
        (set! (.-onicecandidateerror pc)
              (fn [ev]
                (js/console.warn "[kaigi] ICE candidate error"
                                 (.-errorCode ev) (.-errorText ev)
                                 "url=" (.-url ev)
                                 "address=" (.-address ev))))
        (set! (.-onicegatheringstatechange pc)
              (fn [_] (js/console.info "[kaigi] ICE gathering"
                                       (.-iceGatheringState pc))))
        (set! (.-onicecandidate pc)
              (fn [ev]
                (when-let [c (.-candidate ev)]
                  (signal! peer-id {:kind :ice :candidate (js/JSON.stringify (.toJSON c))}))))
        (set! (.-ontrack pc)
              (fn [ev]
                (.addTrack stream (.-track ev))
                (let [el (video-for peer-id false)]
                  (when-not (identical? (.-srcObject el) stream)
                    (set! (.-srcObject el) stream)))
                (swap! state assoc-in [:remote-streams peer-id] stream)
                (attach-videos!)))
        (when-let [local (:local-stream @state)]
          (doseq [t (array-seq (.getTracks local))]
            (.addTrack pc t local)))
        (swap! state assoc-in [:peers peer-id] pc)
        pc)))

(defn- drop-peer!
  [peer-id]
  (when-let [pc (get-in @state [:peers peer-id])]
    (try (.close pc) (catch :default _ nil)))
  (swap! state update :peers dissoc peer-id)
  (swap! state update :remote-streams dissoc peer-id)
  (when-let [el (get @video-elements peer-id)]
    (when-let [p (.-parentNode el)] (.removeChild p el)))
  (swap! video-elements dissoc peer-id))

(defn- offer-to!
  [peer-id]
  (let [pc (ensure-peer! peer-id)]
    (-> (.createOffer pc)
        (.then (fn [offer]
                 (-> (.setLocalDescription pc offer)
                     (.then (fn [_]
                              (signal! peer-id {:kind :offer :sdp (.-sdp offer)}))))))
        (.catch (fn [e] (js/console.error "[kaigi] offer failed" e))))))

(defn- apply-plan!
  "Open a connection to every peer the plan names, close the rest.

  Only the `:offerer` side initiates. The `:answerer` creates its
  PeerConnection lazily when the offer arrives, so it does not race ahead and
  produce a second, competing offer."
  [plan-map]
  (when (= :mesh (:kaigi.plan/transport plan-map))
    (let [peers (:kaigi.plan/peers plan-map)
          wanted (set (map :kaigi.plan/peer-id peers))]
      (doseq [existing (keys (:peers @state))
              :when (not (contains? wanted existing))]
        (drop-peer! existing))
      (doseq [{:kaigi.plan/keys [peer-id role]} peers]
        (when (and (= :offerer role) (not (get-in @state [:peers peer-id])))
          (offer-to! peer-id))))))

;; ---------------------------------------------------------------------------
;; inbound signaling
;; ---------------------------------------------------------------------------

(defn- handle-signal!
  [from {:keys [kind sdp candidate]}]
  (let [pc (ensure-peer! from)]
    (case kind
      :offer
      (-> (.setRemoteDescription pc #js {:type "offer" :sdp sdp})
          (.then (fn [_] (.createAnswer pc)))
          (.then (fn [answer]
                   (-> (.setLocalDescription pc answer)
                       (.then (fn [_] (signal! from {:kind :answer :sdp (.-sdp answer)}))))))
          (.catch (fn [e] (js/console.error "[kaigi] answer failed" e))))

      :answer
      (-> (.setRemoteDescription pc #js {:type "answer" :sdp sdp})
          (.catch (fn [e] (js/console.error "[kaigi] setRemoteDescription(answer) failed" e))))

      :ice
      (-> (.addIceCandidate pc (js/JSON.parse candidate))
          (.catch (fn [e]
                    ;; A candidate that arrives before the remote description
                    ;; is a normal race, not a fault; the connection still
                    ;; forms from the candidates that do land.
                    (js/console.debug "[kaigi] addIceCandidate" (.-message e)))))

      (js/console.warn "[kaigi] unknown signal kind" (pr-str kind)))))

(defn- handle-frame!
  [frame]
  (case (:t frame)
    :state (do (swap! state assoc
                      :meeting (:kaigi/meeting frame)
                      :me (or (:kaigi/you frame) (:me @state))
                      :transport (:kaigi/transport frame)
                      :ice-servers (:kaigi/ice-servers frame))
               (render!))
    :plan  (let [p (:kaigi/plan frame)]
             (swap! state assoc :warning (:kaigi.plan/warning p))
             (render!)
             (apply-plan! p))
    :signal (handle-signal! (:kaigi/from frame) (:kaigi/payload frame))
    :error (js/console.warn "[kaigi]" (str (:kaigi/code frame)) (:kaigi/message frame))
    (js/console.warn "[kaigi] unknown frame" (pr-str (:t frame)))))

;; ---------------------------------------------------------------------------
;; local media
;; ---------------------------------------------------------------------------

(defn- capture!
  "Acquire camera and microphone.

  Failure is not fatal: a participant who declines (or has no camera) should
  still be able to see and hear the others. Joining audio-only is a normal
  outcome, not an error state, so the console renders either way."
  []
  (-> (.getUserMedia (.-mediaDevices js/navigator) #js {:audio true :video true})
      (.then (fn [stream]
               (js/console.info "[kaigi] captured"
                                (.-length (.getTracks stream)) "local track(s)")
               (swap! state assoc :local-stream stream)
               (attach-videos!)
               stream))
      (.catch (fn [e]
                (js/console.warn "[kaigi] getUserMedia failed:" (.-message e))
                nil))))

;; ---------------------------------------------------------------------------
;; controls
;; ---------------------------------------------------------------------------

(defn- local-tracks
  [kind]
  (when-let [s (:local-stream @state)]
    (array-seq (if (= :audio kind) (.getAudioTracks s) (.getVideoTracks s)))))

(defn- set-track-enabled!
  "Enable/disable local tracks. `enabled = false` stops transmitting while
  keeping the transceiver, so muting does not renegotiate the connection."
  [kind enabled?]
  (doseq [t (local-tracks kind)]
    (set! (.-enabled t) enabled?)))

(defn- dispatch!
  "Act on a `data-act` value.

  Targeted acts arrive as `\"admit:rin\"` — the target is encoded in the act
  string because the design system's `button` cannot carry an arbitrary data
  attribute (see `kaigi.ui/lobby-queue`). Splitting on the first colon keeps
  ids containing colons intact, which matters because a `did:key:…` is a legal
  participant id."
  [act-str]
  (let [i (.indexOf act-str ":")
        act (if (neg? i) act-str (.slice act-str 0 i))
        target (when-not (neg? i) (.slice act-str (inc i)))
        mtg (:meeting @state)
        me (:me @state)
        p (m/participant-by-id mtg me)]
    (case act
      "admit"  (control! :admit target)
      "deny"   (control! :deny target)
      "remove" (control! :remove target)
      "toggle-mute"
      (let [now (not (:kaigi.participant/muted? p))]
        (set-track-enabled! :audio (not now))
        (control! :mute nil now))
      "toggle-camera"
      (let [now (not (:kaigi.participant/camera-off? p))]
        (set-track-enabled! :video (not now))
        (control! :camera-off nil now))
      "toggle-share"
      (if (= me (m/sharing-id mtg))
        (control! :stop-share)
        (control! :start-share))
      "end" (control! :end)
      "request-recording" (control! :request-recording)
      "consent-recording" (control! :consent-recording)
      "revoke-consent" (control! :revoke-consent)
      "start-recording" (control! :start-recording)
      "stop-recording" (control! :stop-recording nil "")
      (js/console.debug "[kaigi] unhandled act" act))))

(defn- install-click-handler!
  "One delegated listener on the document.

  Delegated rather than per-button because the console subtree is replaced
  wholesale on every render — listeners bound to individual buttons would be
  discarded with them, and the UI would go dead after the first roster
  change (silently: the markup still looks right)."
  []
  (.addEventListener
   js/document "click"
   (fn [ev]
     (when-let [el (.closest (.-target ev) "[data-act]")]
       (.preventDefault ev)
       (dispatch! (.getAttribute el "data-act"))))))

;; ---------------------------------------------------------------------------
;; boot
;; ---------------------------------------------------------------------------

(defn- connect!
  []
  (let [proto (if (= "https:" (.. js/window -location -protocol)) "wss:" "ws:")
        url (str proto "//" (.. js/window -location -host)
                 "/api/kaigi/ws?meeting=" (js/encodeURIComponent (meeting-id)))
        ws (js/WebSocket. url)
        me (my-id)]
    (js/console.info "[kaigi] connecting" url "as" me)
    (swap! state assoc :socket ws :me me)
    (set! (.-onopen ws)
          (fn [_] (send! {:t :hello :kaigi/participant-id me
                          :kaigi/name me})))
    (set! (.-onmessage ws)
          (fn [ev]
            (try (handle-frame! (reader/read-string (.-data ev)))
                 (catch :default e
                   (js/console.error "[kaigi] bad frame" (.-message e))))))
    (set! (.-onerror ws) (fn [_] (js/console.error "[kaigi] socket error for" url)))
    (set! (.-onclose ws)
          (fn [ev] (js/console.warn "[kaigi] socket closed" (.-code ev) (.-reason ev))))
    ws))

(defn ^:export init
  []
  ;; Lifecycle logging is not debug scaffolding left behind: joining a call has
  ;; four steps that can each stall silently (permission, socket, roster,
  ;; negotiation), and without a trace the symptom for all four is the same
  ;; blank grid. These lines are what tell an operator which step stopped.
  (js/console.info "[kaigi] init")
  (install-click-handler!)
  ;; Media first, then connect: a peer that answers an offer before its local
  ;; tracks exist negotiates an audio/video-less session and stays silent even
  ;; though the connection reports `connected`.
  (-> (capture!)
      (.then (fn [_] (connect!)))))

;; Introspection seam for the end-to-end harness. Exposed deliberately and
;; narrowly: the browser test needs to read connection state and RTP counters,
;; which are the only evidence that media actually flowed rather than that the
;; UI merely looks right.
(set! (.-kaigi js/window)
      #js {:state (fn [] (clj->js (dissoc @state :socket :local-stream
                                          :peers :remote-streams)))
           :participantCount (fn [] (count (m/admitted-ids (:meeting @state))))
           :peerIds (fn [] (clj->js (vec (keys (:peers @state)))))
           :iceTransportPolicy (fn [] (ice-transport-policy))
           ;; The selected candidate pair's type is the only direct evidence
           ;; that media went through a relay rather than around it.
           :selectedCandidateTypes
           (fn []
             (js/Promise.all
              (clj->js
               (for [[_ pc] (:peers @state)]
                 (-> (.getStats pc)
                     (.then (fn [report]
                              (let [found (atom nil)]
                                (.forEach report
                                          (fn [s]
                                            (when (and (= "candidate-pair" (.-type s))
                                                       (.-selected s))
                                              (reset! found (.-remoteCandidateId s)))))
                                ;; fall back to any succeeded pair — Chromium
                                ;; does not always set `selected`
                                (when-not @found
                                  (.forEach report
                                            (fn [s]
                                              (when (and (= "candidate-pair" (.-type s))
                                                         (= "succeeded" (.-state s)))
                                                (reset! found (.-remoteCandidateId s))))))
                                (let [t (atom nil)]
                                  (.forEach report
                                            (fn [s]
                                              (when (and (= "remote-candidate" (.-type s))
                                                         (= (.-id s) @found))
                                                (reset! t (.-candidateType s)))))
                                  @t))))))))) 
           :connectionStates (fn []
                               (clj->js (into {} (map (fn [[id pc]]
                                                        [id (.-connectionState pc)]))
                                              (:peers @state))))
           :inboundBytes (fn []
                           (js/Promise.all
                            (clj->js
                             (for [[_ pc] (:peers @state)]
                               (-> (.getStats pc)
                                   (.then (fn [report]
                                            (let [total (atom 0)]
                                              (.forEach report
                                                        (fn [s]
                                                          (when (= "inbound-rtp" (.-type s))
                                                            (swap! total + (or (.-bytesReceived s) 0)))))
                                              @total))))))))})

(init)
