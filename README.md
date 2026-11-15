# SURYA-50 SMART Android app (v5 source)

Open this directory in Android Studio and build the app. The app uses Android NSD discovery for `_surya._tcp` and connects to the host/controller WebSocket endpoint.

Before connecting, enter the pairing token stored in `data/pairing.token` on the server. The token is a bearer credential; do not share it. The demo stores it in WebView local storage. Production must migrate it to Android Keystore and require WSS with certificate validation.

The Home screen's Energy Intelligence panel uses only validated live telemetry. It is advisory; it cannot change MPPT or grid control. History is server-authoritative. When telemetry is missing or stale, the UI suppresses live analytics instead of inventing values.

The supplied ESP32 bridge sketch is a development starting point, not a secure production gateway. R&D must implement the authenticated protocol, TLS or an authenticated BLE channel, device provisioning, replay protection and the approved dsPIC communication interface before product deployment.
