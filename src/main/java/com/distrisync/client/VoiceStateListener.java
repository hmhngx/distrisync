package com.distrisync.client;

/**
 * Receives peer hardware mute/unmute updates relayed over TCP ({@code VOICE_STATE}).
 * Does not indicate active speech — that remains on the UDP data plane.
 */
@FunctionalInterface
public interface VoiceStateListener {

    /**
     * @param clientId session id of the peer whose mute state changed
     * @param isMuted  {@code true} when the peer muted their microphone hardware
     */
    void onVoiceState(String clientId, boolean isMuted);
}
