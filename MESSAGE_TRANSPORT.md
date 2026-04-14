# Message transport — three steps (in order)

**VERY IMPORTANT**
firstly still assume that its a multi hop mesh. i will add that later.

## End-to-end payload encryption (v1)

- **User-visible content** in the HTTP JSON `content` field, in `RelayPacket.content`, and in BLE `BlePacket` type `msg` bodies is **ciphertext** only: wire prefix `E2EE_MSG1:` plus Base64 (AES-256-GCM with random IV). Metadata (`messageId`, `from`, `to`, BLE routing, scan data) stays plaintext.
- **Key announce** (plaintext, in-band): server and relay use `content` = `E2EE_KEY1:` + JSON `{"appId":"<hex>","publicKey":"<SPKI Base64>"}`. Ingestion is handled by `ChatInboundDispatch` / `ChatPayloadCrypto.tryConsumeKeyAnnounce` so **no chat row** is created for that packet.
- **BLE** uses the existing `handshake` packet with JSON `{"appId":"…","publicKey":"…"}` (legacy body of exactly 8 hex chars is still accepted for app-id-only). After the peripheral handles the central’s handshake, it **notifies** the central with the same JSON shape so the central learns the peripheral’s public key.
- **Session crypto**: P-256 ECDH (local private key in Android Keystore) + **Tink** `Hkdf` + AES-256-GCM; the same derived key encrypts both directions for that peer pair. **Room** stores ciphertext in `conversation_messages.text` (schema version 2; upgrade clears prior plaintext rows).

When the user sends a message to a **known peer** (stable `appId`), the app picks **one** path. Check in this order; **stop at the first step that applies**.

## 1. Wi‑Fi / internet (server)

**When:** The device has validated internet (e.g. `NetworkUtils.hasInternet`).

**What:** Talk to the **destination `appId`** through your **HTTP API** (e.g. `ServerClient.postMessage`). No BLE scan is required for this step.

**Session:** The chat is a logical “server route” to that `appId` (e.g. `WE_ARE_INTERNET` in `MessagingConnectionState`), not a direct BLE link.

---

## 2. BLE direct (same peer, in radio range)

**When:** Step 1 does **not** apply (offline), **and** a targeted BLE scan finds a peripheral whose **advertised service data** matches the **destination `appId`**.

**What:** Open a normal BLE chat: central writes / peripheral notifies on your messaging characteristics, handshake carries ids, etc.

**Session:** `WE_ARE_CENTRAL` or `WE_ARE_PERIPHERAL` with that peer.

---

## 3. Gateway / relay (offline, peer not on BLE)

**When:** Step 1 does **not** apply, **and** step 2 fails (timeout: target `appId` never seen on BLE).

**What:** Still open a **logical session to the same destination `appId`** (again e.g. `WE_ARE_INTERNET`), but **sends** must **not** assume HTTP works. Use **BLE relay**: after a short relay scan, **`RelayManager.flood`** connects to **each visible neighbor one after another** (sequential GATT connect → write `TYPE_RELAY` → disconnect → next neighbor). Each neighbor that **has internet** handles `onReceived` and **`ServerClient.postMessage`** so the message reaches the real destination on the server.

**Important:** The gateway’s BLE `appId` **does not** have to match the chat destination; only the **payload inside the relay** carries the real `destinationAppId`.

### Side note: A → C through B (and others)

Example: **Device A** has no Wi‑Fi and chats with **C** (far away). **B** is in BLE range of A and has internet.

1. A’s coordinator fails to find C on BLE, then calls **`setConnectedAsInternet(C’s id)`** anyway.
2. When A sends, **`BleMessaging`** sees offline + `WE_ARE_INTERNET` and calls **`RelayManager.flood`**.
3. **Flood** discovers **all** nearby peripherals advertising your service (B, and any others), then **walks that list sequentially**, delivering the same relay payload to each. **B** receives the packet, sees the destination is not itself, and **POSTs to the server** for C. Other neighbors without internet drop or ignore as implemented.

So “gateway” is **not** a dedicated pairing to B; it is **try every neighbor in range** until one bridges to the server (dedupe on the server / message id reduces duplicates if several forward).

---

## Rules of thumb for code changes

- **`BleMessaging` (or equivalent)** must handle the **server / relay** role: if online → POST; if offline in that role → **flood**, not “do nothing.”
- **After a failed BLE peer scan**, offline devices still need **`setConnectedAsInternet(destAppId, …)`** (or equivalent) so step 3 can run; gating that on `hasInternet` **breaks** relay for A.
- **Do not cancel** an in‑progress `scanForPeer` from **`MainActivity.onPause`** when opening chat; otherwise **`onNotFound` never runs** and step 3 never activates.
- **Relay** writes must use the same GATT **write characteristic** the peripheral expects; **notify** stays on the measurement / notify characteristic. Mixing UUIDs breaks both chat and gateway.

---

## Reverse direction (server → offline device)

A device **with internet** polls for pending relay deliveries, scans BLE for the **recipient `appId`**, delivers a relay packet, then **confirms** to the server so messages are not stuck. That is the mirror of step 3, not a fourth “user send” step.
