/*
 * mGBA Android — GBA Link Cable TCP/IP Network Bridge Implementation
 * Licensed under the Mozilla Public License, v. 2.0 (MPL-2.0).
 */

#include "gba_link_bridge.h"

#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gba/io.h>
#include <mgba/internal/gba/sio.h>

#define LOG_TAG "mGBA_LinkBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define LINK_MAGIC 0x4D474241u /* "MGBA" */
#define LINK_PROTO_VERSION 1u
#define LINK_RX_QUEUE_CAP 32
#define LINK_POLL_CYCLES 2048
#define LINK_XFER_TIMEOUT_MS 250
#define LINK_CONNECT_TIMEOUT_MS 5000
#define LINK_PING_INTERVAL_MS 1500

enum LinkMsgType {
    LINK_MSG_HELLO       = 1,
    LINK_MSG_WELCOME     = 2,
    LINK_MSG_PEER_COUNT  = 3,
    LINK_MSG_READY       = 4,
    LINK_MSG_MULTI_REQ   = 5,
    LINK_MSG_MULTI_RESP  = 6,
    LINK_MSG_MULTI_DONE  = 7,
    LINK_MSG_NORMAL_REQ  = 8,
    LINK_MSG_NORMAL_RESP = 9,
    LINK_MSG_PING        = 10,
    LINK_MSG_PONG        = 11,
    LINK_MSG_BYE         = 12
};

typedef struct __attribute__((packed)) LinkPacket {
    uint32_t magic;
    uint8_t  type;
    uint8_t  playerId;
    uint8_t  playerCount;
    uint8_t  flags;
    uint32_t seq;
    uint16_t data[4];
} LinkPacket;

typedef struct MultiRxItem {
    uint16_t words[4];
    uint8_t baud;
} MultiRxItem;

typedef struct NormalRxItem {
    uint32_t word;
    bool is32Bit;
} NormalRxItem;

typedef struct LinkSioDriver {
    struct GBASIODriver d;
    GbaLinkBridge *bridge;
    bool isNormalDriver;
} LinkSioDriver;

struct GbaLinkBridge {
    LinkSioDriver multiDriver;
    LinkSioDriver normalDriver;

    struct mCore *attachedCore;
    struct mTimingEvent event;
    bool eventScheduled;

    pthread_mutex_t lock;
    pthread_cond_t xferCond;
    pthread_t workerThread;
    bool workerRunning;
    atomic_bool stopRequested;

    GbaLinkState state;
    bool isHost;
    int playerId;      // 0 = Host, 1..3 = Clients, -1 = Idle
    int playerCount;   // 1..4
    int pingMs;        // -1 if unavailable
    char lastError[160];

    // Config for worker thread
    char targetHost[128];
    int targetPort;

    // Socket descriptors (protected by lock for lifecycle close)
    int listenFd;
    int peerFds[MAX_GBAS]; // Host uses [1..3]; Client uses [0] as host socket

    // Emulation-side cached send values (atomic so worker thread reads lock-free)
    atomic_uint_fast16_t localMultiSend;
    atomic_uint_fast32_t localNormalSend;
    atomic_bool inMultiMode;

    // Host transfer synchronization (protected by lock)
    uint32_t transferSeq;
    uint8_t expectedRespMask;
    uint8_t receivedRespMask;
    uint16_t respWords[MAX_GBAS];
    bool normalRespReceived;
    uint32_t normalRespWord;

    // Pending transfer completion delivered on mTimingEvent (protected by lock)
    bool hostPendingMulti;
    uint16_t hostPendingMultiWords[4];
    bool hostPendingNormal;
    uint32_t hostPendingNormalWord;
    bool hostPendingNormal32;

    // Client RX queues delivered on mTimingEvent (protected by lock)
    MultiRxItem multiQueue[LINK_RX_QUEUE_CAP];
    int multiQHead;
    int multiQTail;
    int multiQCount;

    NormalRxItem normalQueue[LINK_RX_QUEUE_CAP];
    int normalQHead;
    int normalQTail;
    int normalQCount;
};

/* ========================================================================== */
/* Time & Socket Helpers                                                      */
/* ========================================================================== */

static uint64_t _nowMs(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000ULL + (uint64_t)(ts.tv_nsec / 1000000ULL);
}

static void _setErrorLocked(GbaLinkBridge *b, const char *msg) {
    if (!msg) {
        b->lastError[0] = '\0';
        return;
    }
    strncpy(b->lastError, msg, sizeof(b->lastError) - 1);
    b->lastError[sizeof(b->lastError) - 1] = '\0';
}

static void _configureLowLatencySocket(int fd) {
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof(one));
}

static void _closeFdSafe(int *fdPtr) {
    if (fdPtr && *fdPtr >= 0) {
        int fd = *fdPtr;
        *fdPtr = -1;
        shutdown(fd, SHUT_RDWR);
        close(fd);
    }
}

static bool _sendPacket(int fd, const LinkPacket *pkt) {
    if (fd < 0 || !pkt) return false;
    const uint8_t *buf = (const uint8_t *)pkt;
    size_t total = sizeof(LinkPacket);
    size_t sent = 0;
    while (sent < total) {
        ssize_t n = send(fd, buf + sent, total - sent, MSG_NOSIGNAL);
        if (n < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        if (n == 0) return false;
        sent += (size_t)n;
    }
    return true;
}

static bool _recvPacket(int fd, LinkPacket *outPkt) {
    if (fd < 0 || !outPkt) return false;
    uint8_t *buf = (uint8_t *)outPkt;
    size_t total = sizeof(LinkPacket);
    size_t recvd = 0;
    while (recvd < total) {
        ssize_t n = recv(fd, buf + recvd, total - recvd, 0);
        if (n < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        if (n == 0) return false;
        recvd += (size_t)n;
    }
    return outPkt->magic == LINK_MAGIC;
}

static void _initPacket(LinkPacket *pkt, uint8_t type, uint8_t playerId, uint8_t playerCount, uint8_t flags, uint32_t seq) {
    memset(pkt, 0, sizeof(*pkt));
    pkt->magic = LINK_MAGIC;
    pkt->type = type;
    pkt->playerId = playerId;
    pkt->playerCount = playerCount;
    pkt->flags = flags;
    pkt->seq = seq;
}

static int _countHostPlayersLocked(GbaLinkBridge *b) {
    int count = 1; // Host itself is always P1
    for (int i = 1; i < MAX_GBAS; ++i) {
        if (b->peerFds[i] >= 0) count++;
    }
    return count;
}

static void _broadcastPeerCountLocked(GbaLinkBridge *b) {
    int count = _countHostPlayersLocked(b);
    b->playerCount = count;
    b->state = (count >= 2) ? LINK_STATE_CONNECTED : LINK_STATE_HOSTING;
    if (count < 2) {
        b->pingMs = -1;
    }

    LinkPacket pkt;
    _initPacket(&pkt, LINK_MSG_PEER_COUNT, 0, (uint8_t)count, 0, 0);
    for (int i = 1; i < MAX_GBAS; ++i) {
        if (b->peerFds[i] >= 0) {
            _sendPacket(b->peerFds[i], &pkt);
        }
    }
}

/* ========================================================================== */
/* GBA SIO Timing Event (Always runs on Emulation Thread)                     */
/* ========================================================================== */

static void _sioTimingCallback(struct mTiming *timing, void *context, uint32_t cyclesLate) {
    (void)cyclesLate;
    GbaLinkBridge *b = (GbaLinkBridge *)context;
    if (!b || !b->attachedCore || b->attachedCore->platform(b->attachedCore) != mPLATFORM_GBA) {
        return;
    }

    struct GBA *gba = (struct GBA *)b->attachedCore->board;
    if (!gba) return;
    struct GBASIO *sio = &gba->sio;

    pthread_mutex_lock(&b->lock);
    GbaLinkState state = b->state;
    bool isHost = b->isHost;
    int playerId = b->playerId;
    int playerCount = b->playerCount;

    if (sio->mode == SIO_MULTI) {
        bool ready = (state == LINK_STATE_CONNECTED && playerCount >= 2);
        sio->siocnt = GBASIOMultiplayerSetReady(sio->siocnt, ready);
        sio->siocnt = GBASIOMultiplayerSetSlave(sio->siocnt, !isHost);
        if (playerId >= 0) {
            sio->siocnt = GBASIOMultiplayerSetId(sio->siocnt, (unsigned)playerId);
        }

        if (isHost && b->hostPendingMulti) {
            uint16_t w0 = b->hostPendingMultiWords[0];
            uint16_t w1 = b->hostPendingMultiWords[1];
            uint16_t w2 = b->hostPendingMultiWords[2];
            uint16_t w3 = b->hostPendingMultiWords[3];
            b->hostPendingMulti = false;
            pthread_mutex_unlock(&b->lock);

            gba->memory.io[REG_SIOMULTI0 >> 1] = w0;
            gba->memory.io[REG_SIOMULTI1 >> 1] = w1;
            gba->memory.io[REG_SIOMULTI2 >> 1] = w2;
            gba->memory.io[REG_SIOMULTI3 >> 1] = w3;
            sio->rcnt |= 1;
            sio->siocnt = GBASIOMultiplayerClearBusy(sio->siocnt);
            sio->siocnt = GBASIOMultiplayerSetId(sio->siocnt, 0);
            if (GBASIOMultiplayerIsIrq(sio->siocnt)) {
                GBARaiseIRQ(gba, GBA_IRQ_SIO, 0);
            }
            mTimingSchedule(timing, &b->event, LINK_POLL_CYCLES);
            return;
        }

        if (!isHost && b->multiQCount > 0) {
            MultiRxItem item = b->multiQueue[b->multiQHead];
            b->multiQHead = (b->multiQHead + 1) % LINK_RX_QUEUE_CAP;
            b->multiQCount--;
            pthread_mutex_unlock(&b->lock);

            gba->memory.io[REG_SIOMULTI0 >> 1] = item.words[0];
            gba->memory.io[REG_SIOMULTI1 >> 1] = item.words[1];
            gba->memory.io[REG_SIOMULTI2 >> 1] = item.words[2];
            gba->memory.io[REG_SIOMULTI3 >> 1] = item.words[3];
            sio->rcnt |= 1;
            sio->siocnt = GBASIOMultiplayerClearBusy(sio->siocnt);
            sio->siocnt = GBASIOMultiplayerSetSlave(sio->siocnt, true);
            sio->siocnt = GBASIOMultiplayerSetReady(sio->siocnt, true);
            if (playerId >= 0) {
                sio->siocnt = GBASIOMultiplayerSetId(sio->siocnt, (unsigned)playerId);
            }
            if (GBASIOMultiplayerIsIrq(sio->siocnt)) {
                GBARaiseIRQ(gba, GBA_IRQ_SIO, 0);
            }
            mTimingSchedule(timing, &b->event, LINK_POLL_CYCLES);
            return;
        }
    } else if (sio->mode == SIO_NORMAL_8 || sio->mode == SIO_NORMAL_32) {
        if (isHost && b->hostPendingNormal) {
            uint32_t word = b->hostPendingNormalWord;
            bool is32 = b->hostPendingNormal32;
            b->hostPendingNormal = false;
            pthread_mutex_unlock(&b->lock);

            if (is32) {
                gba->memory.io[REG_SIODATA32_LO >> 1] = (uint16_t)(word & 0xFFFFu);
                gba->memory.io[REG_SIODATA32_HI >> 1] = (uint16_t)((word >> 16) & 0xFFFFu);
            } else {
                gba->memory.io[REG_SIODATA8 >> 1] = (uint16_t)(word & 0xFFu);
            }
            sio->siocnt = GBASIONormalClearStart(sio->siocnt);
            if (GBASIONormalIsIrq(sio->siocnt)) {
                GBARaiseIRQ(gba, GBA_IRQ_SIO, 0);
            }
            mTimingSchedule(timing, &b->event, LINK_POLL_CYCLES);
            return;
        }

        if (!isHost && b->normalQCount > 0) {
            NormalRxItem item = b->normalQueue[b->normalQHead];
            b->normalQHead = (b->normalQHead + 1) % LINK_RX_QUEUE_CAP;
            b->normalQCount--;
            pthread_mutex_unlock(&b->lock);

            if (item.is32Bit || sio->mode == SIO_NORMAL_32) {
                gba->memory.io[REG_SIODATA32_LO >> 1] = (uint16_t)(item.word & 0xFFFFu);
                gba->memory.io[REG_SIODATA32_HI >> 1] = (uint16_t)((item.word >> 16) & 0xFFFFu);
            } else {
                gba->memory.io[REG_SIODATA8 >> 1] = (uint16_t)(item.word & 0xFFu);
            }
            sio->siocnt = GBASIONormalClearStart(sio->siocnt);
            if (GBASIONormalIsIrq(sio->siocnt)) {
                GBARaiseIRQ(gba, GBA_IRQ_SIO, 0);
            }
            mTimingSchedule(timing, &b->event, LINK_POLL_CYCLES);
            return;
        }
    }

    pthread_mutex_unlock(&b->lock);
    mTimingSchedule(timing, &b->event, LINK_POLL_CYCLES);
}

/* ========================================================================== */
/* GBASIODriver Callbacks (Run on Emulation Thread)                           */
/* ========================================================================== */

static bool _driverInit(struct GBASIODriver *driver) {
    LinkSioDriver *ld = (LinkSioDriver *)driver;
    GbaLinkBridge *b = ld->bridge;
    b->event.context = b;
    b->event.name = "GBA Network Link Bridge";
    b->event.callback = _sioTimingCallback;
    b->event.priority = 0x80;
    return true;
}

static void _driverDeinit(struct GBASIODriver *driver) {
    (void)driver;
}

static bool _driverLoad(struct GBASIODriver *driver) {
    LinkSioDriver *ld = (LinkSioDriver *)driver;
    GbaLinkBridge *b = ld->bridge;
    struct GBASIO *sio = driver->p;
    if (!sio || !sio->p) return true;

    pthread_mutex_lock(&b->lock);
    bool isHost = b->isHost;
    int playerId = b->playerId;
    int playerCount = b->playerCount;
    bool ready = (b->state == LINK_STATE_CONNECTED && playerCount >= 2);
    pthread_mutex_unlock(&b->lock);

    if (sio->mode == SIO_MULTI) {
        atomic_store(&b->inMultiMode, true);
        sio->siocnt = GBASIOMultiplayerSetReady(sio->siocnt, ready);
        if (isHost) {
            sio->rcnt &= ~4;
            sio->siocnt = GBASIOMultiplayerClearSlave(sio->siocnt);
            sio->siocnt = GBASIOMultiplayerSetId(sio->siocnt, 0);
        } else {
            sio->rcnt |= 4;
            sio->siocnt = GBASIOMultiplayerFillSlave(sio->siocnt);
            if (playerId >= 0) {
                sio->siocnt = GBASIOMultiplayerSetId(sio->siocnt, (unsigned)playerId);
            }
        }
    } else if (sio->mode == SIO_NORMAL_8 || sio->mode == SIO_NORMAL_32) {
        atomic_store(&b->inMultiMode, false);
        sio->siocnt = GBASIONormalClearSi(sio->siocnt);
    }

    if (mTimingIsScheduled(&sio->p->timing, &b->event)) {
        mTimingDeschedule(&sio->p->timing, &b->event);
    }
    mTimingSchedule(&sio->p->timing, &b->event, LINK_POLL_CYCLES);
    b->eventScheduled = true;
    return true;
}

static bool _driverUnload(struct GBASIODriver *driver) {
    LinkSioDriver *ld = (LinkSioDriver *)driver;
    GbaLinkBridge *b = ld->bridge;
    struct GBASIO *sio = driver->p;
    atomic_store(&b->inMultiMode, false);
    if (sio && sio->p && mTimingIsScheduled(&sio->p->timing, &b->event)) {
        mTimingDeschedule(&sio->p->timing, &b->event);
    }
    b->eventScheduled = false;
    return true;
}

static void _hostPerformMultiTransfer(GbaLinkBridge *b, struct GBASIO *sio, uint16_t mySend, int baud) {
    int peerFdsCopy[MAX_GBAS];
    uint8_t expectedMask = 0;
    uint32_t seq = 0;
    int pCount = 1;

    pthread_mutex_lock(&b->lock);
    if (b->state != LINK_STATE_CONNECTED || b->playerCount < 2) {
        pthread_mutex_unlock(&b->lock);
        // Standalone fallback so the game never stalls
        sio->p->memory.io[REG_SIOMULTI0 >> 1] = mySend;
        sio->p->memory.io[REG_SIOMULTI1 >> 1] = 0xFFFF;
        sio->p->memory.io[REG_SIOMULTI2 >> 1] = 0xFFFF;
        sio->p->memory.io[REG_SIOMULTI3 >> 1] = 0xFFFF;
        sio->siocnt = GBASIOMultiplayerClearBusy(sio->siocnt);
        return;
    }

    seq = ++b->transferSeq;
    b->receivedRespMask = 0;
    b->respWords[0] = mySend;
    b->respWords[1] = 0xFFFF;
    b->respWords[2] = 0xFFFF;
    b->respWords[3] = 0xFFFF;
    pCount = b->playerCount;

    for (int i = 1; i < MAX_GBAS; ++i) {
        peerFdsCopy[i] = b->peerFds[i];
        if (peerFdsCopy[i] >= 0) {
            expectedMask |= (uint8_t)(1u << i);
        }
    }
    b->expectedRespMask = expectedMask;
    pthread_mutex_unlock(&b->lock);

    // Send transfer request to all connected clients
    LinkPacket req;
    _initPacket(&req, LINK_MSG_MULTI_REQ, 0, (uint8_t)pCount, (uint8_t)(baud & 3), seq);
    req.data[0] = mySend;
    for (int i = 1; i < MAX_GBAS; ++i) {
        if (peerFdsCopy[i] >= 0) {
            _sendPacket(peerFdsCopy[i], &req);
        }
    }

    // Wait for responses with bounded timeout (so a dead link never hangs emulation)
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_nsec += (long)LINK_XFER_TIMEOUT_MS * 1000000L;
    while (ts.tv_nsec >= 1000000000L) {
        ts.tv_sec += 1;
        ts.tv_nsec -= 1000000000L;
    }

    uint16_t finalWords[4];
    pthread_mutex_lock(&b->lock);
    while (!atomic_load(&b->stopRequested) &&
           (b->receivedRespMask & b->expectedRespMask) != b->expectedRespMask) {
        int rc = pthread_cond_timedwait(&b->xferCond, &b->lock, &ts);
        if (rc == ETIMEDOUT) break;
    }
    for (int i = 0; i < 4; ++i) {
        finalWords[i] = b->respWords[i];
    }
    b->expectedRespMask = 0;
    b->hostPendingMulti = true;
    memcpy(b->hostPendingMultiWords, finalWords, sizeof(finalWords));
    for (int i = 1; i < MAX_GBAS; ++i) {
        peerFdsCopy[i] = b->peerFds[i];
    }
    pCount = b->playerCount;
    pthread_mutex_unlock(&b->lock);

    // Broadcast completed 4-player word set to all clients
    LinkPacket done;
    _initPacket(&done, LINK_MSG_MULTI_DONE, 0, (uint8_t)pCount, (uint8_t)(baud & 3), seq);
    memcpy(done.data, finalWords, sizeof(finalWords));
    for (int i = 1; i < MAX_GBAS; ++i) {
        if (peerFdsCopy[i] >= 0) {
            _sendPacket(peerFdsCopy[i], &done);
        }
    }

    // Schedule GBA completion event after authentic cycle duration
    int idx = (pCount >= 1 && pCount <= MAX_GBAS) ? (pCount - 1) : 1;
    int cycles = GBASIOCyclesPerTransfer[baud & 3][idx];
    if (mTimingIsScheduled(&sio->p->timing, &b->event)) {
        mTimingDeschedule(&sio->p->timing, &b->event);
    }
    mTimingSchedule(&sio->p->timing, &b->event, (int32_t)cycles);
}

static void _hostPerformNormalTransfer(GbaLinkBridge *b, struct GBASIO *sio, bool is32Bit) {
    uint32_t myWord = is32Bit
        ? ((uint32_t)sio->p->memory.io[REG_SIODATA32_LO >> 1] | ((uint32_t)sio->p->memory.io[REG_SIODATA32_HI >> 1] << 16))
        : (uint32_t)(sio->p->memory.io[REG_SIODATA8 >> 1] & 0xFFu);

    int peerFd = -1;
    uint32_t seq = 0;
    pthread_mutex_lock(&b->lock);
    for (int i = 1; i < MAX_GBAS; ++i) {
        if (b->peerFds[i] >= 0) {
            peerFd = b->peerFds[i];
            break;
        }
    }
    if (peerFd < 0) {
        pthread_mutex_unlock(&b->lock);
        b->hostPendingNormal = true;
        b->hostPendingNormalWord = 0xFFFFFFFFu;
        b->hostPendingNormal32 = is32Bit;
        if (mTimingIsScheduled(&sio->p->timing, &b->event)) {
            mTimingDeschedule(&sio->p->timing, &b->event);
        }
        mTimingSchedule(&sio->p->timing, &b->event, 1024);
        return;
    }

    seq = ++b->transferSeq;
    b->normalRespReceived = false;
    b->normalRespWord = 0xFFFFFFFFu;
    pthread_mutex_unlock(&b->lock);

    LinkPacket req;
    _initPacket(&req, LINK_MSG_NORMAL_REQ, 0, 2, is32Bit ? 1 : 0, seq);
    req.data[0] = (uint16_t)(myWord & 0xFFFFu);
    req.data[1] = (uint16_t)((myWord >> 16) & 0xFFFFu);
    _sendPacket(peerFd, &req);

    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_nsec += (long)LINK_XFER_TIMEOUT_MS * 1000000L;
    while (ts.tv_nsec >= 1000000000L) {
        ts.tv_sec += 1;
        ts.tv_nsec -= 1000000000L;
    }

    pthread_mutex_lock(&b->lock);
    while (!atomic_load(&b->stopRequested) && !b->normalRespReceived) {
        int rc = pthread_cond_timedwait(&b->xferCond, &b->lock, &ts);
        if (rc == ETIMEDOUT) break;
    }
    b->hostPendingNormal = true;
    b->hostPendingNormalWord = b->normalRespWord;
    b->hostPendingNormal32 = is32Bit;
    pthread_mutex_unlock(&b->lock);

    if (mTimingIsScheduled(&sio->p->timing, &b->event)) {
        mTimingDeschedule(&sio->p->timing, &b->event);
    }
    mTimingSchedule(&sio->p->timing, &b->event, 2048);
}

static uint16_t _multiWriteRegister(struct GBASIODriver *driver, uint32_t address, uint16_t value) {
    LinkSioDriver *ld = (LinkSioDriver *)driver;
    GbaLinkBridge *b = ld->bridge;
    struct GBASIO *sio = driver->p;

    if (address == REG_SIOMLT_SEND) {
        atomic_store(&b->localMultiSend, value);
        return value;
    }

    if (address == REG_SIOCNT) {
        pthread_mutex_lock(&b->lock);
        bool isHost = b->isHost;
        int playerId = b->playerId;
        int playerCount = b->playerCount;
        bool ready = (b->state == LINK_STATE_CONNECTED && playerCount >= 2);
        pthread_mutex_unlock(&b->lock);

        sio->siocnt = GBASIOMultiplayerSetReady(sio->siocnt, ready);
        sio->siocnt = GBASIOMultiplayerSetSlave(sio->siocnt, !isHost || !ready);
        if (playerId >= 0) {
            sio->siocnt = GBASIOMultiplayerSetId(sio->siocnt, (unsigned)playerId);
        }

        if ((value & 0x0080) && isHost && ready) {
            uint16_t mySend = sio->p->memory.io[REG_SIOMLT_SEND >> 1];
            int baud = GBASIOMultiplayerGetBaud(value);
            sio->siocnt = GBASIOMultiplayerFillBusy(sio->siocnt);
            _hostPerformMultiTransfer(b, sio, mySend, baud);
        }

        value &= 0xFF83;
        value |= sio->siocnt & 0x00FC;
        return value;
    }

    return value;
}

static uint16_t _normalWriteRegister(struct GBASIODriver *driver, uint32_t address, uint16_t value) {
    LinkSioDriver *ld = (LinkSioDriver *)driver;
    GbaLinkBridge *b = ld->bridge;
    struct GBASIO *sio = driver->p;

    if (address == REG_SIODATA8) {
        atomic_store(&b->localNormalSend, (uint32_t)(value & 0xFFu));
        return value;
    }
    if (address == REG_SIODATA32_LO) {
        uint32_t cur = (uint32_t)atomic_load(&b->localNormalSend);
        atomic_store(&b->localNormalSend, (cur & 0xFFFF0000u) | (uint32_t)value);
        return value;
    }
    if (address == REG_SIODATA32_HI) {
        uint32_t cur = (uint32_t)atomic_load(&b->localNormalSend);
        atomic_store(&b->localNormalSend, (cur & 0x0000FFFFu) | ((uint32_t)value << 16));
        return value;
    }
    if (address == REG_SIOCNT) {
        pthread_mutex_lock(&b->lock);
        bool isHost = b->isHost;
        bool ready = (b->state == LINK_STATE_CONNECTED && b->playerCount >= 2);
        pthread_mutex_unlock(&b->lock);

        if ((value & 0x0080) && (value & 0x0001) && isHost && ready) {
            bool is32Bit = (sio->mode == SIO_NORMAL_32) || ((value & 0x1000) != 0);
            _hostPerformNormalTransfer(b, sio, is32Bit);
        } else if ((value & 0x0080) && !ready) {
            value &= ~0x0080;
            if (value & 0x4000) {
                GBARaiseIRQ(sio->p, GBA_IRQ_SIO, 0);
            }
        }
        return value;
    }
    return value;
}

/* ========================================================================== */
/* Background Network Worker Threads                                          */
/* ========================================================================== */

static void *_hostWorkerThread(void *arg) {
    GbaLinkBridge *b = (GbaLinkBridge *)arg;
    uint64_t lastPingSentMs = _nowMs();

    LOGI("Host worker thread started on port %d", b->targetPort);

    while (!atomic_load(&b->stopRequested)) {
        struct pollfd fds[MAX_GBAS];
        int fdMap[MAX_GBAS];
        int nfds = 0;

        pthread_mutex_lock(&b->lock);
        if (b->listenFd >= 0) {
            fds[nfds].fd = b->listenFd;
            fds[nfds].events = POLLIN;
            fds[nfds].revents = 0;
            fdMap[nfds] = 0; // 0 = listen socket
            nfds++;
        }
        for (int i = 1; i < MAX_GBAS; ++i) {
            if (b->peerFds[i] >= 0) {
                fds[nfds].fd = b->peerFds[i];
                fds[nfds].events = POLLIN;
                fds[nfds].revents = 0;
                fdMap[nfds] = i; // 1..3 = peer slot
                nfds++;
            }
        }
        pthread_mutex_unlock(&b->lock);

        if (nfds == 0) break;

        int ready = poll(fds, (nfds_t)nfds, 100);
        if (ready < 0) {
            if (errno == EINTR) continue;
            break;
        }

        uint64_t now = _nowMs();
        if (now - lastPingSentMs >= LINK_PING_INTERVAL_MS) {
            lastPingSentMs = now;
            pthread_mutex_lock(&b->lock);
            int firstPeer = -1;
            for (int i = 1; i < MAX_GBAS; ++i) {
                if (b->peerFds[i] >= 0) {
                    firstPeer = b->peerFds[i];
                    break;
                }
            }
            pthread_mutex_unlock(&b->lock);
            if (firstPeer >= 0) {
                LinkPacket ping;
                _initPacket(&ping, LINK_MSG_PING, 0, 0, 0, (uint32_t)(now & 0xFFFFFFFFu));
                _sendPacket(firstPeer, &ping);
            }
        }

        if (ready == 0) continue;

        for (int k = 0; k < nfds; ++k) {
            if (fds[k].revents == 0) continue;
            int slot = fdMap[k];

            if (slot == 0) {
                // Accept incoming client
                struct sockaddr_in clientAddr;
                socklen_t addrLen = sizeof(clientAddr);
                int clientFd = accept(fds[k].fd, (struct sockaddr *)&clientAddr, &addrLen);
                if (clientFd < 0) continue;

                _configureLowLatencySocket(clientFd);

                // Read HELLO handshake (with short timeout)
                struct timeval tv = { 2, 0 };
                setsockopt(clientFd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
                LinkPacket hello;
                if (!_recvPacket(clientFd, &hello) || hello.type != LINK_MSG_HELLO) {
                    close(clientFd);
                    continue;
                }
                tv.tv_sec = 0;
                tv.tv_usec = 0;
                setsockopt(clientFd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

                pthread_mutex_lock(&b->lock);
                int freeSlot = -1;
                for (int i = 1; i < MAX_GBAS; ++i) {
                    if (b->peerFds[i] < 0) {
                        freeSlot = i;
                        break;
                    }
                }
                if (freeSlot < 0) {
                    pthread_mutex_unlock(&b->lock);
                    close(clientFd);
                    LOGW("Rejected client: room full (4/4)");
                    continue;
                }

                b->peerFds[freeSlot] = clientFd;
                int count = _countHostPlayersLocked(b);
                LinkPacket welcome;
                _initPacket(&welcome, LINK_MSG_WELCOME, (uint8_t)freeSlot, (uint8_t)count, LINK_PROTO_VERSION, 0);
                _sendPacket(clientFd, &welcome);
                _broadcastPeerCountLocked(b);
                pthread_mutex_unlock(&b->lock);

                LOGI("Accepted client as Player %d (total=%d)", freeSlot + 1, count);
            } else {
                // Incoming packet from peer slot 1..3
                LinkPacket pkt;
                if ((fds[k].revents & (POLLERR | POLLHUP | POLLNVAL)) || !_recvPacket(fds[k].fd, &pkt) || pkt.type == LINK_MSG_BYE) {
                    pthread_mutex_lock(&b->lock);
                    _closeFdSafe(&b->peerFds[slot]);
                    b->expectedRespMask &= (uint8_t)~(1u << slot);
                    b->normalRespReceived = true;
                    _broadcastPeerCountLocked(b);
                    pthread_cond_broadcast(&b->xferCond);
                    pthread_mutex_unlock(&b->lock);
                    LOGI("Client slot %d disconnected", slot + 1);
                    continue;
                }

                switch (pkt.type) {
                    case LINK_MSG_MULTI_RESP:
                        pthread_mutex_lock(&b->lock);
                        if (pkt.seq == b->transferSeq) {
                            b->respWords[slot] = pkt.data[0];
                            b->receivedRespMask |= (uint8_t)(1u << slot);
                            if ((b->receivedRespMask & b->expectedRespMask) == b->expectedRespMask) {
                                pthread_cond_broadcast(&b->xferCond);
                            }
                        }
                        pthread_mutex_unlock(&b->lock);
                        break;

                    case LINK_MSG_NORMAL_RESP:
                        pthread_mutex_lock(&b->lock);
                        if (pkt.seq == b->transferSeq) {
                            b->normalRespWord = (uint32_t)pkt.data[0] | ((uint32_t)pkt.data[1] << 16);
                            b->normalRespReceived = true;
                            pthread_cond_broadcast(&b->xferCond);
                        }
                        pthread_mutex_unlock(&b->lock);
                        break;

                    case LINK_MSG_PING: {
                        pthread_mutex_lock(&b->lock);
                        uint8_t pCount = (uint8_t)b->playerCount;
                        pthread_mutex_unlock(&b->lock);
                        LinkPacket pong;
                        _initPacket(&pong, LINK_MSG_PONG, 0, pCount, 0, pkt.seq);
                        _sendPacket(fds[k].fd, &pong);
                        break;
                    }

                    case LINK_MSG_PONG: {
                        uint32_t now32 = (uint32_t)(_nowMs() & 0xFFFFFFFFu);
                        int rtt = (int)(now32 - pkt.seq);
                        if (rtt >= 0 && rtt < 10000) {
                            pthread_mutex_lock(&b->lock);
                            b->pingMs = rtt;
                            pthread_mutex_unlock(&b->lock);
                        }
                        break;
                    }

                    default:
                        break;
                }
            }
        }
    }

    LOGI("Host worker thread exiting");
    return NULL;
}

static int _connectWithTimeout(const char *host, int port, int timeoutMs, char *errOut, size_t errSize) {
    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;

    char portStr[16];
    snprintf(portStr, sizeof(portStr), "%d", port);

    struct addrinfo *res = NULL;
    int gai = getaddrinfo(host, portStr, &hints, &res);
    if (gai != 0 || !res) {
        snprintf(errOut, errSize, "Cannot resolve host: %s", host);
        return -1;
    }

    int sock = -1;
    for (struct addrinfo *rp = res; rp != NULL; rp = rp->ai_next) {
        sock = socket(rp->ai_family, rp->ai_socktype, rp->ai_protocol);
        if (sock < 0) continue;

        int flags = fcntl(sock, F_GETFL, 0);
        fcntl(sock, F_SETFL, flags | O_NONBLOCK);

        int rc = connect(sock, rp->ai_addr, rp->ai_addrlen);
        if (rc == 0) {
            fcntl(sock, F_SETFL, flags);
            break;
        }
        if (errno == EINPROGRESS) {
            struct pollfd pfd;
            pfd.fd = sock;
            pfd.events = POLLOUT;
            pfd.revents = 0;
            int pr = poll(&pfd, 1, timeoutMs);
            if (pr > 0) {
                int soErr = 0;
                socklen_t len = sizeof(soErr);
                if (getsockopt(sock, SOL_SOCKET, SO_ERROR, &soErr, &len) == 0 && soErr == 0) {
                    fcntl(sock, F_SETFL, flags);
                    break;
                }
            }
        }
        close(sock);
        sock = -1;
    }

    freeaddrinfo(res);
    if (sock < 0) {
        snprintf(errOut, errSize, "Connection timed out or refused (%s:%d)", host, port);
    }
    return sock;
}

static void *_clientWorkerThread(void *arg) {
    GbaLinkBridge *b = (GbaLinkBridge *)arg;
    char hostCopy[128];
    int portCopy = 0;

    pthread_mutex_lock(&b->lock);
    strncpy(hostCopy, b->targetHost, sizeof(hostCopy) - 1);
    hostCopy[sizeof(hostCopy) - 1] = '\0';
    portCopy = b->targetPort;
    pthread_mutex_unlock(&b->lock);

    LOGI("Client connecting to %s:%d", hostCopy, portCopy);
    char errBuf[160] = {0};
    int fd = _connectWithTimeout(hostCopy, portCopy, LINK_CONNECT_TIMEOUT_MS, errBuf, sizeof(errBuf));
    if (fd < 0 || atomic_load(&b->stopRequested)) {
        if (fd >= 0) close(fd);
        pthread_mutex_lock(&b->lock);
        if (!atomic_load(&b->stopRequested)) {
            b->state = LINK_STATE_ERROR;
            _setErrorLocked(b, errBuf[0] ? errBuf : "Failed to connect to host");
        }
        pthread_mutex_unlock(&b->lock);
        return NULL;
    }

    _configureLowLatencySocket(fd);

    // Send HELLO and wait for WELCOME
    struct timeval tv = { 3, 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    LinkPacket hello;
    _initPacket(&hello, LINK_MSG_HELLO, 0, 1, LINK_PROTO_VERSION, 0);
    LinkPacket welcome;
    if (!_sendPacket(fd, &hello) || !_recvPacket(fd, &welcome) || welcome.type != LINK_MSG_WELCOME) {
        close(fd);
        pthread_mutex_lock(&b->lock);
        if (!atomic_load(&b->stopRequested)) {
            b->state = LINK_STATE_ERROR;
            _setErrorLocked(b, "Handshake failed (room may be full)");
        }
        pthread_mutex_unlock(&b->lock);
        return NULL;
    }
    tv.tv_sec = 0;
    tv.tv_usec = 0;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    pthread_mutex_lock(&b->lock);
    b->peerFds[0] = fd;
    b->playerId = (int)welcome.playerId;
    b->playerCount = (int)(welcome.playerCount >= 2 ? welcome.playerCount : 2);
    b->state = LINK_STATE_CONNECTED;
    _setErrorLocked(b, NULL);
    pthread_mutex_unlock(&b->lock);

    LOGI("Connected to host as Player %d (total=%d)", b->playerId + 1, b->playerCount);

    uint64_t lastPingSentMs = _nowMs();

    while (!atomic_load(&b->stopRequested)) {
        struct pollfd pfd;
        pfd.fd = fd;
        pfd.events = POLLIN;
        pfd.revents = 0;

        int ready = poll(&pfd, 1, 100);
        if (ready < 0) {
            if (errno == EINTR) continue;
            break;
        }

        uint64_t now = _nowMs();
        if (now - lastPingSentMs >= LINK_PING_INTERVAL_MS) {
            lastPingSentMs = now;
            LinkPacket ping;
            _initPacket(&ping, LINK_MSG_PING, (uint8_t)b->playerId, 0, 0, (uint32_t)(now & 0xFFFFFFFFu));
            if (!_sendPacket(fd, &ping)) break;
        }

        if (ready == 0) continue;

        LinkPacket pkt;
        if ((pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) || !_recvPacket(fd, &pkt) || pkt.type == LINK_MSG_BYE) {
            break;
        }

        switch (pkt.type) {
            case LINK_MSG_PEER_COUNT:
                pthread_mutex_lock(&b->lock);
                b->playerCount = (int)pkt.playerCount;
                pthread_mutex_unlock(&b->lock);
                break;

            case LINK_MSG_MULTI_REQ: {
                uint16_t mySend = (uint16_t)atomic_load(&b->localMultiSend);
                LinkPacket resp;
                _initPacket(&resp, LINK_MSG_MULTI_RESP, (uint8_t)b->playerId, 0, 0, pkt.seq);
                resp.data[0] = mySend;
                _sendPacket(fd, &resp);
                break;
            }

            case LINK_MSG_MULTI_DONE:
                pthread_mutex_lock(&b->lock);
                if (b->multiQCount < LINK_RX_QUEUE_CAP) {
                    MultiRxItem *slot = &b->multiQueue[b->multiQTail];
                    memcpy(slot->words, pkt.data, sizeof(slot->words));
                    slot->baud = pkt.flags;
                    b->multiQTail = (b->multiQTail + 1) % LINK_RX_QUEUE_CAP;
                    b->multiQCount++;
                }
                pthread_mutex_unlock(&b->lock);
                break;

            case LINK_MSG_NORMAL_REQ: {
                uint32_t myWord = (uint32_t)atomic_load(&b->localNormalSend);
                LinkPacket resp;
                _initPacket(&resp, LINK_MSG_NORMAL_RESP, (uint8_t)b->playerId, 0, pkt.flags, pkt.seq);
                resp.data[0] = (uint16_t)(myWord & 0xFFFFu);
                resp.data[1] = (uint16_t)((myWord >> 16) & 0xFFFFu);
                _sendPacket(fd, &resp);

                uint32_t hostWord = (uint32_t)pkt.data[0] | ((uint32_t)pkt.data[1] << 16);
                pthread_mutex_lock(&b->lock);
                if (b->normalQCount < LINK_RX_QUEUE_CAP) {
                    NormalRxItem *slot = &b->normalQueue[b->normalQTail];
                    slot->word = hostWord;
                    slot->is32Bit = (pkt.flags != 0);
                    b->normalQTail = (b->normalQTail + 1) % LINK_RX_QUEUE_CAP;
                    b->normalQCount++;
                }
                pthread_mutex_unlock(&b->lock);
                break;
            }

            case LINK_MSG_PING: {
                LinkPacket pong;
                _initPacket(&pong, LINK_MSG_PONG, (uint8_t)b->playerId, 0, 0, pkt.seq);
                _sendPacket(fd, &pong);
                break;
            }

            case LINK_MSG_PONG: {
                uint32_t now32 = (uint32_t)(_nowMs() & 0xFFFFFFFFu);
                int rtt = (int)(now32 - pkt.seq);
                if (rtt >= 0 && rtt < 10000) {
                    pthread_mutex_lock(&b->lock);
                    b->pingMs = rtt;
                    pthread_mutex_unlock(&b->lock);
                }
                break;
            }

            default:
                break;
        }
    }

    pthread_mutex_lock(&b->lock);
    _closeFdSafe(&b->peerFds[0]);
    if (!atomic_load(&b->stopRequested)) {
        b->state = LINK_STATE_ERROR;
        b->pingMs = -1;
        b->playerCount = 1;
        _setErrorLocked(b, "Disconnected from host");
    }
    pthread_mutex_unlock(&b->lock);
    LOGI("Client worker thread exiting");
    return NULL;
}

/* ========================================================================== */
/* Public API                                                                 */
/* ========================================================================== */

GbaLinkBridge *gba_link_bridge_create(void) {
    GbaLinkBridge *b = (GbaLinkBridge *)calloc(1, sizeof(GbaLinkBridge));
    if (!b) return NULL;

    pthread_mutex_init(&b->lock, NULL);
    pthread_cond_init(&b->xferCond, NULL);
    atomic_init(&b->stopRequested, false);
    atomic_init(&b->localMultiSend, 0xFFFFu);
    atomic_init(&b->localNormalSend, 0xFFFFFFFFu);
    atomic_init(&b->inMultiMode, false);

    b->state = LINK_STATE_IDLE;
    b->playerId = -1;
    b->playerCount = 1;
    b->pingMs = -1;
    b->listenFd = -1;
    for (int i = 0; i < MAX_GBAS; ++i) {
        b->peerFds[i] = -1;
    }

    b->multiDriver.d.init = _driverInit;
    b->multiDriver.d.deinit = _driverDeinit;
    b->multiDriver.d.load = _driverLoad;
    b->multiDriver.d.unload = _driverUnload;
    b->multiDriver.d.writeRegister = _multiWriteRegister;
    b->multiDriver.bridge = b;
    b->multiDriver.isNormalDriver = false;

    b->normalDriver.d.init = _driverInit;
    b->normalDriver.d.deinit = _driverDeinit;
    b->normalDriver.d.load = _driverLoad;
    b->normalDriver.d.unload = _driverUnload;
    b->normalDriver.d.writeRegister = _normalWriteRegister;
    b->normalDriver.bridge = b;
    b->normalDriver.isNormalDriver = true;

    return b;
}

bool gba_link_bridge_attach_core(GbaLinkBridge *b, struct mCore *core) {
    if (!b || !core || core->platform(core) != mPLATFORM_GBA) {
        return false;
    }
    if (b->attachedCore == core) {
        return true;
    }
    if (b->attachedCore) {
        gba_link_bridge_detach_core(b);
    }

    struct GBA *gba = (struct GBA *)core->board;
    if (!gba) return false;

    b->attachedCore = core;
    GBASIOSetDriver(&gba->sio, &b->multiDriver.d, SIO_MULTI);
    GBASIOSetDriver(&gba->sio, &b->normalDriver.d, SIO_NORMAL_8);
    LOGI("Attached GBA Link Bridge SIO drivers to core %p", (void *)core);
    return true;
}

static void _stopNetworkOnly(GbaLinkBridge *b) {
    if (!b) return;
    atomic_store(&b->stopRequested, true);

    pthread_mutex_lock(&b->lock);
    // Notify peers politely before closing
    LinkPacket bye;
    _initPacket(&bye, LINK_MSG_BYE, (uint8_t)(b->playerId >= 0 ? b->playerId : 0), 0, 0, 0);
    for (int i = 0; i < MAX_GBAS; ++i) {
        if (b->peerFds[i] >= 0) {
            _sendPacket(b->peerFds[i], &bye);
            _closeFdSafe(&b->peerFds[i]);
        }
    }
    _closeFdSafe(&b->listenFd);
    pthread_cond_broadcast(&b->xferCond);
    bool hadWorker = b->workerRunning;
    pthread_mutex_unlock(&b->lock);

    if (hadWorker) {
        pthread_join(b->workerThread, NULL);
    }

    pthread_mutex_lock(&b->lock);
    b->workerRunning = false;
    b->state = LINK_STATE_IDLE;
    b->isHost = false;
    b->playerId = -1;
    b->playerCount = 1;
    b->pingMs = -1;
    b->multiQHead = b->multiQTail = b->multiQCount = 0;
    b->normalQHead = b->normalQTail = b->normalQCount = 0;
    b->hostPendingMulti = false;
    b->hostPendingNormal = false;
    b->expectedRespMask = 0;
    b->receivedRespMask = 0;
    _setErrorLocked(b, NULL);
    pthread_mutex_unlock(&b->lock);
}

void gba_link_bridge_detach_core(GbaLinkBridge *b) {
    if (!b) return;
    _stopNetworkOnly(b);

    if (b->attachedCore) {
        if (b->attachedCore->platform(b->attachedCore) == mPLATFORM_GBA) {
            struct GBA *gba = (struct GBA *)b->attachedCore->board;
            if (gba) {
                if (mTimingIsScheduled(&gba->timing, &b->event)) {
                    mTimingDeschedule(&gba->timing, &b->event);
                }
                GBASIOSetDriver(&gba->sio, NULL, SIO_MULTI);
                GBASIOSetDriver(&gba->sio, NULL, SIO_NORMAL_8);
            }
        }
        b->attachedCore = NULL;
        b->eventScheduled = false;
        LOGI("Detached GBA Link Bridge SIO drivers");
    }
}

void gba_link_bridge_destroy(GbaLinkBridge *b) {
    if (!b) return;
    gba_link_bridge_detach_core(b);
    pthread_cond_destroy(&b->xferCond);
    pthread_mutex_destroy(&b->lock);
    free(b);
}

bool gba_link_bridge_start_host(GbaLinkBridge *b, struct mCore *core, int port) {
    if (!b || !core || port < 1024 || port > 65535) return false;
    if (core->platform(core) != mPLATFORM_GBA) {
        pthread_mutex_lock(&b->lock);
        b->state = LINK_STATE_ERROR;
        _setErrorLocked(b, "Link Cable requires a GBA game");
        pthread_mutex_unlock(&b->lock);
        return false;
    }

    _stopNetworkOnly(b);

    int listenFd = socket(AF_INET, SOCK_STREAM, 0);
    if (listenFd < 0) {
        pthread_mutex_lock(&b->lock);
        b->state = LINK_STATE_ERROR;
        _setErrorLocked(b, "Failed to create server socket");
        pthread_mutex_unlock(&b->lock);
        return false;
    }

    int one = 1;
    setsockopt(listenFd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons((uint16_t)port);

    if (bind(listenFd, (struct sockaddr *)&addr, sizeof(addr)) < 0 || listen(listenFd, 4) < 0) {
        int err = errno;
        close(listenFd);
        pthread_mutex_lock(&b->lock);
        b->state = LINK_STATE_ERROR;
        char msg[128];
        snprintf(msg, sizeof(msg), "Cannot bind port %d (%s)", port, strerror(err));
        _setErrorLocked(b, msg);
        pthread_mutex_unlock(&b->lock);
        return false;
    }

    pthread_mutex_lock(&b->lock);
    atomic_store(&b->stopRequested, false);
    b->listenFd = listenFd;
    b->targetPort = port;
    b->isHost = true;
    b->playerId = 0;
    b->playerCount = 1;
    b->pingMs = -1;
    b->state = LINK_STATE_HOSTING;
    _setErrorLocked(b, NULL);
    pthread_mutex_unlock(&b->lock);

    if (!gba_link_bridge_attach_core(b, core)) {
        _stopNetworkOnly(b);
        return false;
    }

    pthread_mutex_lock(&b->lock);
    if (pthread_create(&b->workerThread, NULL, _hostWorkerThread, b) == 0) {
        b->workerRunning = true;
        pthread_mutex_unlock(&b->lock);
        return true;
    } else {
        _closeFdSafe(&b->listenFd);
        b->state = LINK_STATE_ERROR;
        _setErrorLocked(b, "Failed to start host thread");
        pthread_mutex_unlock(&b->lock);
        return false;
    }
}

bool gba_link_bridge_connect(GbaLinkBridge *b, struct mCore *core, const char *host, int port) {
    if (!b || !core || !host || host[0] == '\0' || port < 1024 || port > 65535) return false;
    if (core->platform(core) != mPLATFORM_GBA) {
        pthread_mutex_lock(&b->lock);
        b->state = LINK_STATE_ERROR;
        _setErrorLocked(b, "Link Cable requires a GBA game");
        pthread_mutex_unlock(&b->lock);
        return false;
    }

    _stopNetworkOnly(b);

    pthread_mutex_lock(&b->lock);
    atomic_store(&b->stopRequested, false);
    strncpy(b->targetHost, host, sizeof(b->targetHost) - 1);
    b->targetHost[sizeof(b->targetHost) - 1] = '\0';
    b->targetPort = port;
    b->isHost = false;
    b->playerId = -1;
    b->playerCount = 1;
    b->pingMs = -1;
    b->state = LINK_STATE_CONNECTING;
    _setErrorLocked(b, NULL);
    pthread_mutex_unlock(&b->lock);

    if (!gba_link_bridge_attach_core(b, core)) {
        _stopNetworkOnly(b);
        return false;
    }

    pthread_mutex_lock(&b->lock);
    if (pthread_create(&b->workerThread, NULL, _clientWorkerThread, b) == 0) {
        b->workerRunning = true;
        pthread_mutex_unlock(&b->lock);
        return true;
    } else {
        b->state = LINK_STATE_ERROR;
        _setErrorLocked(b, "Failed to start client thread");
        pthread_mutex_unlock(&b->lock);
        return false;
    }
}

void gba_link_bridge_signal_stop(GbaLinkBridge *b) {
    if (!b) return;
    atomic_store(&b->stopRequested, true);
    pthread_mutex_lock(&b->lock);
    pthread_cond_broadcast(&b->xferCond);
    pthread_mutex_unlock(&b->lock);
}

void gba_link_bridge_disconnect(GbaLinkBridge *b) {
    if (!b) return;
    gba_link_bridge_detach_core(b);
}

GbaLinkState gba_link_bridge_get_state(GbaLinkBridge *b) {
    if (!b) return LINK_STATE_IDLE;
    pthread_mutex_lock(&b->lock);
    GbaLinkState st = b->state;
    pthread_mutex_unlock(&b->lock);
    return st;
}

int gba_link_bridge_get_player_id(GbaLinkBridge *b) {
    if (!b) return -1;
    pthread_mutex_lock(&b->lock);
    int id = b->playerId;
    pthread_mutex_unlock(&b->lock);
    return id;
}

int gba_link_bridge_get_player_count(GbaLinkBridge *b) {
    if (!b) return 1;
    pthread_mutex_lock(&b->lock);
    int cnt = b->playerCount;
    pthread_mutex_unlock(&b->lock);
    return cnt;
}

int gba_link_bridge_get_ping_ms(GbaLinkBridge *b) {
    if (!b) return -1;
    pthread_mutex_lock(&b->lock);
    int ms = b->pingMs;
    pthread_mutex_unlock(&b->lock);
    return ms;
}

bool gba_link_bridge_is_host(GbaLinkBridge *b) {
    if (!b) return false;
    pthread_mutex_lock(&b->lock);
    bool host = b->isHost;
    pthread_mutex_unlock(&b->lock);
    return host;
}

void gba_link_bridge_get_error(GbaLinkBridge *b, char *outBuf, size_t bufSize) {
    if (!outBuf || bufSize == 0) return;
    outBuf[0] = '\0';
    if (!b) return;
    pthread_mutex_lock(&b->lock);
    strncpy(outBuf, b->lastError, bufSize - 1);
    outBuf[bufSize - 1] = '\0';
    pthread_mutex_unlock(&b->lock);
}
