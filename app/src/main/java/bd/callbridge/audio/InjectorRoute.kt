package bd.callbridge.audio

/**
 * Which uplink-injection strategy is active (spec §4.3). Selected via [bd.callbridge.Config.injectorRoute]
 * and surfaced on the status screen (spec §4.6).
 */
enum class InjectorRoute {
    /** Route A: Qualcomm in-call music path via native AudioTrack(AUDIO_OUTPUT_FLAG_INCALL_MUSIC). */
    INCALL_MUSIC,

    /** Route B: AudioTrack.setPreferredDevice(TELEPHONY_TX) from a priv-app with MODIFY_AUDIO_ROUTING. */
    TELEPHONY_TX,

    /** Route C: physical loopback rig (speakerphone + dongle mic on a second device). No-op from
     *  this app's point of view other than logging; the bridge just plays audio to the speaker. */
    LOOPBACK,

    /** No injection wired up yet. Safe default so M0/M2 builds run without root. */
    NOOP,
}
