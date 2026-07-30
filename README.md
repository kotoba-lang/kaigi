# kaigi 会議

Online meetings (Zoom / Google Meet 相当) for this workspace: a portable CLJC
**meeting** model, a media-topology planner, a Cloudflare Realtime SFU binding,
and a Durable Object signaling room.

Sibling of the other workspace surfaces — `kotoba-lang/kaisha` (chat),
`calendar` (events), `mail` / `contacts` (correspondence), `slides` (decks).
Like those, the core stays pure data and pure functions; the host renders it,
persists it, and moves the bytes.

> **What this is not.** `kotoba-lang/gijiroku` is the *record* side — it reads
> Zoom / Meet / Teams recording APIs and drafts minutes. `kaigi` is the
> *hosting* side. They are complementary: gijiroku consumes other vendors'
> meetings, kaigi holds our own.

## Status

| Layer | State | Evidence |
|---|---|---|
| `kaigi.model` / `validate` / `plan` / `sfu` / `signal` | **implemented** | 52 tests / 184 assertions green on **both** JVM and nbb |
| DO signaling room (`kaigi.worker.*`) | **implemented, deployed** | 22/22 end-to-end checks against the live Worker, 4 consecutive clean runs |
| Cloudflare Realtime SFU path | **implemented, not exercised** | no Realtime app is provisioned for this account — see below |
| Browser console (`kaigi.ui` + `kaigi.app`) | **implemented, deployed** | rendered page scores **100.00 / 0 findings** on the deterministic HIG/WCAG audit |
| Real call (mesh) | **works** | two headless Chromium instances, ICE connected, **~190 KB of inbound RTP measured on each side**, 3 consecutive clean production runs |

So: **a real two-party call works in production over mesh.** The SFU path is
written and untested against the live service; see "What is missing" below.

## What it composes rather than reimplements

- `kotoba-lang/webrtc` — `kotoba.webrtc.room` holds participants, published
  tracks and subscriptions; `kotoba.webrtc.session` is the offer/answer
  reducer; `kotoba.webrtc` is ICE/SDP as data.
- `kotoba-lang/org-w3-webrtc-signaling` — `kotoba.rt.core/route-message`
  decides who receives a relayed frame.

`kaigi` adds the layer a *meeting* has and a *room* does not.

## The model

```clojure
(require '[kaigi.model :as k])

(def m
  (-> (k/meeting "m-1" "jun" {:title "週次" :lobby :approval-required})
      (k/start)
      (k/request-admission (k/participant "rin" {:name "Rin"}))))

(k/waiting-ids m)                      ;; => #{"rin"}  — the host's lobby queue
(k/admitted? m "rin")                  ;; => false
(-> m (k/admit "jun" "rin") (k/join "rin") (k/admitted? "rin"))  ;; => true
```

Four invariants are worth stating because they are the ones that are easy to
get wrong and expensive to get wrong:

**Admission is a path, not a boolean.** `:requested -> :admitted | :denied`,
and onward to `:removed`. A removed participant is kept (not deleted) so a
re-join is distinguishable from a first knock — and re-knocking lands at
`:requested` even under an `:open` lobby, because otherwise ejecting someone
accomplishes nothing.

**Recording consent is unanimous and explicit.** `recording-allowed?` is true
only when *every* admitted participant has consented. Silence is not consent, a
majority is not consent, and an empty meeting is not consent. Revoking mid-call
stops an in-progress recording. This is `gijiroku`'s charter rule G3, enforced
here rather than in the host, because the host is the layer under pressure to
skip it. Only an `:asset-ref` is ever stored — never media (G4).

**Screen share is exclusive by construction.** `start-share` clears the
previous sharer in the same update, so no state exists in which two
participants are sharing and the grid has to guess.

**Mute is asymmetric.** A moderator may mute someone; only its owner may
unmute. Opening a live microphone in someone else's room is not a moderation
action.

## Media topology

`kaigi.plan` decides who subscribes to whom, and returns *diffs* — re-pulling
tracks a participant already has renegotiates their PeerConnection on every
roster change, which everyone sees as a video freeze.

```clojure
(plan/plan-for meeting sessions "jun" subscribed config)
;; :sfu  => {:kaigi.plan/pull [{:session-id … :track-name …}] :kaigi.plan/close ["3"]}
;; :mesh => {:kaigi.plan/peers [{:kaigi.plan/peer-id "rin" :kaigi.plan/role :offerer}]}
```

`transport` picks `:sfu` when an app id and token are configured and `:mesh`
otherwise — a function of configuration, not of headcount, so a deployment
does not switch topology mid-meeting.

In mesh mode `mesh-initiator` gives a total order over participant ids so
**exactly one side offers**. Both peers computing it independently arrive at
the same answer with no extra message, which is the fix for signaling glare —
both sides offering, neither able to answer, failing in a way that looks like a
network problem.

Above `mesh-participant-ceiling` (4) the plan carries a `:kaigi.plan/warning`
**as data**, so the UI can say the meeting outgrew the transport. A limit the
operator only learns from server logs is a limit participants experience as
"the app is broken".

## SFU binding

`kaigi.sfu` shapes requests and parses responses; it holds no HTTP client and
no secret. Field names are transcribed from Cloudflare's published OpenAPI
document (`realtime-api-2024-05-21.yaml`, `servers: https://rtc.live.cloudflare.com/v1`),
and the tests assert those literal shapes — a misspelled `trackName` would
otherwise round-trip through the constructor and back unnoticed.

Two details that are load-bearing:

- **The app secret is not in a request descriptor.** `authorize` is the only
  function that attaches it, so descriptors can be logged and asserted on.
- **A per-track `errorCode` fails the whole response.** The SFU reports partial
  failures inside a 200; a caller that checks only the HTTP status records a
  subscription that was refused, and the participant sees a black tile with no
  error anywhere.

## Signaling room

`worker/` is a Cloudflare Worker whose `KaigiRoom` Durable Object serializes
one meeting. A meeting needs exactly one writer, and a DO is globally unique
and single-threaded per id — so "one writer" is a property of where the code
runs, not something the code implements with leases or fencing tokens. Live
room state lives in the DO; anything cross-meeting belongs in D1, because each
DO's storage is private and would split a cross-meeting query plane into one
island per room.

The class is a ClojureScript `deftype`, not a JavaScript shim: `deftype` emits
a positional constructor and writes `Object` protocol methods to the prototype
un-munged, which is exactly what the DO runtime calls by name.

The wire format is EDN. The model uses sets (`consents`, `tracks`) and keywords
(admission, roles); JSON round-trips neither, so every frame would need a
translation layer whose only job is undoing the format.

```
GET /api/kaigi/ws?meeting=<id>   WebSocket into the room
GET /api/kaigi/health            liveness + which transport is active
```

`health` reports the transport because "is the SFU configured" is the one
operational question whose wrong answer is invisible: a deployment missing
`REALTIME_APP_ID` works fine, in mesh, until the fifth participant joins.

## Build and test

```bash
# pure core, both runtimes
clojure -M:test
nbb --classpath "src:test:../webrtc/src:../org-w3-webrtc-signaling/src" run-tests.cljs

# worker
cd worker
npm install
node ../../../../scripts/resource-guard.mjs run build -- npx shadow-cljs release worker
nbb verify-bundle.cljs                      # actually import()s the artifact
npx wrangler dev --port 8799 --local &
nbb e2e.cljs ws://127.0.0.1:8799            # two real sockets against a real DO

# console: SSR the shell, score it, build the browser bundle
nbb --classpath "../src:../../webrtc/src:../../org-w3-webrtc-signaling/src:\
../../kotoba-ui/src:../../liquid-glass-ui/src:../../shitsuke/src:\
../../css/src:../../html/src:../../appkit/src" generate-page.cljs
(cd ../../design-quality && nbb -m design-quality.cli score ../kaigi/worker/public/index.html --min 100)
node ../../../../scripts/resource-guard.mjs run build -- npx shadow-cljs release app

# a real call: two headless Chromium instances with fake devices, asserting
# inbound RTP bytes > 0 on both sides
nbb e2e-media.cljs http://127.0.0.1:8799

npx wrangler deploy
```

`release`, never `compile`: a `compile` build exits 0, emits every expected
export name, and passes `node --check`, while importing a machine-local
`/tmp/cljs-runtime` path that cannot load standalone. `verify-bundle.cljs`
refuses to run against an artifact older than the newest source — added after a
failed build left the previous bundle in place and the verifier reported six
green checks against code that did not compile.

## The console

`kaigi.ui` is pure `.cljc` hiccup on `kotoba-ui.core`; `generate-page.cljs` SSRs
it to `public/index.html` and `kaigi.app` re-invokes the same fn on every roster
change and swaps it in. One markup source for both hosts.

Two structural details that are load-bearing:

**Video elements are not part of the re-rendered tree.** Tiles emit an empty
`data-kaigi-participant` container; `kaigi.app` creates one `<video>` per
participant and re-parents it after each render. A `<video>` that gets replaced
loses `srcObject` and restarts playback, so a mute toggle would flicker
everyone's video.

**Offer direction comes from the plan, not from negotiation.** The room tells
each side whether it is `:offerer` or `:answerer`, so there is no
perfect-negotiation rollback logic and no state where both peers hold a local
offer.

### Design-system findings worth knowing before extending this

The console is built on `kotoba-ui.core` + shell only, and the rendered page
scores **100.00** with zero findings. Two upstream gaps were hit and are
recorded here rather than worked around:

- **`panel` / `button` silently drop unknown opts.** They resolve to
  `shitsuke.components/card` / `button`, which read only `:class` (a **string**)
  and `:id` / `:act`. Passing `:attrs {:data-kaigi-tile id}` rendered markup
  that looked correct and carried no data attributes at all, so the browser
  found zero tiles to attach video to — with no error anywhere. Data hooks
  therefore live on plain wrapper elements, and targeted actions encode their
  target in the `:act` string (`"admit:rin"`).
- **A toggle cannot expose `aria-pressed`** for the same reason, and
  hand-rolling a `<button>` is the failure the agent-guide names explicitly. The
  labels state the current state instead. Adding `:attrs` support to the
  liquid-glass components would close both.

## What is missing, precisely

1. **The SFU path is untested against the real service.** No Cloudflare
   Realtime app exists for this account — the wrangler OAuth session carries no
   `calls`/`realtime` scope, and minting an app token needs the dashboard.
   Provisioning is one owner action and no code change:

   ```bash
   wrangler secret put REALTIME_APP_ID   --name kaigi
   wrangler secret put REALTIME_APP_TOKEN --name kaigi
   ```

   Until then `transport` reads `:mesh` and small meetings work.
2. **TURN is wired but the relay path is NOT verified end to end.** What is
   done: `kaigi.turn` mints coturn-style ephemeral credentials
   (`username = "<expiry>:<participant-id>"`, HMAC-SHA1 under a server-only
   secret) so no standing relay password is ever handed to a browser; the room
   sends a fresh one per participant; `/api/kaigi/health` reports whether
   minting actually works (`"turn":"ok"|"absent"|"error: …"`); the console warns
   on screen when no relay is present; `?ice=relay` forces relay-only ICE and
   `e2e-media.cljs --relay` asserts the selected candidate type is `relay`.

   What is NOT done: **Chromium never contacts the relay.** Measured
   2026-07-30 — with a valid minted credential, under both
   `iceTransportPolicy: "relay"` and `"all"`, against both a loopback-bound and
   a LAN-bound relay, and with both the headless shell and the full
   Chrome-for-Testing build, Chromium gathered **zero** candidates from the
   TURN server and emitted **zero** `icecandidateerror`, and the relay received
   no datagrams at all.

   The relay is not the problem: `org-ietf-turn`'s own `listener_demo.cljs`
   passes 3/3 over real UDP (Allocate, ChannelBind + bidirectional ChannelData,
   wrong-credential rejection), and `kaigi.turn`'s tests show the minted form is
   exactly what that listener's verifier accepts. One real upstream bug was
   found and fixed on the way (`org-ietf-turn` `ede5921`: the listener dropped
   STUN Binding requests, which an ICE agent sends *before* it will attempt
   Allocate) — necessary, but not sufficient. The remaining blocker is in the
   browser's acceptance of the ICE-server configuration and is unresolved.
3. **No recording pipeline.** The model tracks consent and an asset ref; nothing
   captures or stores media. The handoff to `gijiroku` is unbuilt.
4. **Not wired to `kaisha` or `calendar`.** `:kaigi/channel` exists so a meeting
   can name the chat channel it was called from; nothing reads it yet.
