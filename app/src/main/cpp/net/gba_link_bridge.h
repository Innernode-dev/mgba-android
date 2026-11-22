/*
 * mGBA Android — GBA Link Cable TCP/IP Network Bridge
 * Licensed under the Mozilla Public License, v. 2.0 (MPL-2.0).
 *
 * Implements a native GBASIODriver bridging GBA Serial I/O (SIO_MULTI and
 * SIO_NORMAL_8/32) over standard TCP/IP sockets with TCP_NODELAY so players
 * can link over local Wi-Fi, mobile hotspot, VPN (Tailscale/ZeroTier), or
 * the internet.
 */

#ifndef GBA_LINK_BRIDGE_H
#define GBA_LINK_BRIDGE_H

#include <stdbool.h>
#include <stdint.h>
#include <mgba/core/core.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    LINK_STATE_IDLE = 0,
    LINK_STATE_HOSTING = 1,    // Server listening, waiting for peers (1/4)
    LINK_STATE_CONNECTING = 2, // Client connecting to host
    LINK_STATE_CONNECTED = 3,  // Active link with >= 2 players (or client connected)
    LINK_STATE_ERROR = 4       // Connection failed or dropped
} GbaLinkState;

typedef struct GbaLinkBridge GbaLinkBridge;

GbaLinkBridge *gba_link_bridge_create(void);
void gba_link_bridge_destroy(GbaLinkBridge *bridge);

/**
 * Attaches this bridge's GBASIODriver to a GBA mCore.
 * Must be called while holding the core mutex.
 * Returns false if core is NULL or not a GBA core.
 */
bool gba_link_bridge_attach_core(GbaLinkBridge *bridge, struct mCore *core);

/**
 * Detaches this bridge's GBASIODriver from the currently attached GBA mCore
 * and disconnects any active network session.
 * Must be called while holding the core mutex BEFORE unloading/destroying core.
 */
void gba_link_bridge_detach_core(GbaLinkBridge *bridge);

/**
 * Starts hosting a Link Cable room on 0.0.0.0:port (up to 4 players).
 * Safe to call from any thread; returns immediately (accept loop runs on worker thread).
 */
bool gba_link_bridge_start_host(GbaLinkBridge *bridge, struct mCore *core, int port);

/**
 * Connects as a client (Player 2..4) to host:port with a non-blocking 5s timeout.
 * Safe to call from any thread; returns immediately (connect runs on worker thread).
 */
bool gba_link_bridge_connect(GbaLinkBridge *bridge, struct mCore *core, const char *host, int port);

/**
 * Signals any in-flight SIO transfer wait to abort immediately without needing core mutex.
 * Call before acquiring core mutex to disconnect/destroy so teardown is instant.
 */
void gba_link_bridge_signal_stop(GbaLinkBridge *bridge);

/**
 * Cleanly stops hosting or disconnects client, closes sockets, joins worker threads,
 * and detaches the SIO driver from the core. Must be called with core mutex held.
 */
void gba_link_bridge_disconnect(GbaLinkBridge *bridge);

// Thread-safe status queries (never acquire core mutex)
GbaLinkState gba_link_bridge_get_state(GbaLinkBridge *bridge);
int gba_link_bridge_get_player_id(GbaLinkBridge *bridge);
int gba_link_bridge_get_player_count(GbaLinkBridge *bridge);
int gba_link_bridge_get_ping_ms(GbaLinkBridge *bridge);
bool gba_link_bridge_is_host(GbaLinkBridge *bridge);
void gba_link_bridge_get_error(GbaLinkBridge *bridge, char *outBuf, size_t bufSize);

#ifdef __cplusplus
}
#endif

#endif // GBA_LINK_BRIDGE_H
