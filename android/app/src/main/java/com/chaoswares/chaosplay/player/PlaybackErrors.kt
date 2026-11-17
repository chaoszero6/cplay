package com.chaoswares.chaosplay.player

import androidx.media3.common.PlaybackException

/**
 * Player errors in words people understand (spec 8: "Errors say what
 * happened and what to do next"), plus the recovery decision.
 */
object PlaybackErrors {

    enum class Recovery {
        /** Try the next decoder mode (local codec trouble). */
        NEXT_DECODER,
        /** Ask the media server to transcode (Jellyfin direct play failed). */
        SERVER_TRANSCODE,
        /** Nothing to retry automatically: show the message. */
        NONE
    }

    fun isDecoderProblem(code: Int): Boolean = code in DECODER_CODES

    private val DECODER_CODES = setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
    )

    fun recoveryFor(code: Int, isJellyfin: Boolean, alreadyTranscoding: Boolean, decoderAttempts: Int): Recovery {
        val unsupported = isDecoderProblem(code) ||
            code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            code == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
        return when {
            isJellyfin && unsupported && !alreadyTranscoding -> Recovery.SERVER_TRANSCODE
            isDecoderProblem(code) && decoderAttempts < 2 -> Recovery.NEXT_DECODER
            else -> Recovery.NONE
        }
    }

    fun messageFor(code: Int, httpStatus: Int? = null): String = when {
        httpStatus == 401 || httpStatus == 403 -> "The server refused access ($httpStatus). Sign in to it again from the Network tab."
        httpStatus == 404 -> "The file is no longer on the server (404)."
        httpStatus != null && httpStatus >= 500 -> "The server had a problem ($httpStatus). Try again later."
        code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
            "Cannot reach the server. Check Wi-Fi, VPN or that the server is on."
        code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "The server stopped answering. Check your connection and try again."
        code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server answered with an error."
        code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The file was moved or deleted."
        code == PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "CPlay is not allowed to read this file."
        code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
            "This file format is not supported or the file is damaged."
        code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
            "The stream's playlist is damaged or not a video stream."
        isDecoderProblem(code) -> "This device cannot decode this video's format."
        code == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> "The audio format is not supported on this device."
        code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "The live stream moved on. Reopen it to catch up."
        else -> "This file could not be played."
    }
}
