/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.model.call

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/*
 * Custom OZ-Chat event for updating the call type (voice/video) mid-call.
 * This extends the Matrix spec with additional fields for mid-call renegotiation.
 *
 * m.call.call_update_type
 * ------------------------
 * VIDEO_REQUEST → just a signal (no SDP).
 * VIDEO_ACCEPT  → confirmation.
 * VIDEO         → may contain SDP for renegotiation.
 * VOICE         → downgrade to audio-only.
 */

@JsonClass(generateAdapter = true)
data class CallUpdateTypeContent(
        /**
         * Required. A unique identifier for the call.
         * Used to correlate signaling messages to the same call instance.
         */
        @Json(name = "call_id") override val callId: String?,

        /**
         * Required. ID to let a user identify remote echo of their own events.
         * Each party in a call has a unique party_id.
         */
        @Json(name = "party_id") override val partyId: String? = null,

        /**
         * Required. The version of the VoIP specification this message adheres to.
         * Enables backward-compatibility handling.
         */
        @Json(name = "version") override val version: String? = null,

        /**
         * Optional. Lifetime in ms that this update should be considered valid.
         * Similar to invite/answer lifetime, ensures updates don’t persist forever.
         */
        @Json(name = "lifetime") val lifetime: Int? = null,

        /**
         * Required. The requested/confirmed call type.
         * Values: VOICE, VIDEO, VIDEO_REQUEST, VIDEO_ACCEPT.
         */
        @Json(name = "call_type") val updateCallType: UpdateCallType,

        /**
         * Optional. SDP description, included only for renegotiation (e.g. full VIDEO).
         * VIDEO_REQUEST/VIDEO_ACCEPT usually omit this.
         */
        @Json(name = "description") val description: Description? = null
) : CallSignalingContent {

    /**
     * Nested SDP description block.
     * Mirrors CallInviteContent / CallNegotiateContent structures,
     * but reused here for mid-call updates.
     */
    @JsonClass(generateAdapter = true)
    data class Description(
            /**
             * The type of SDP (offer/answer).
             */
            @Json(name = "type") val type: SdpType,

            /**
             * The actual SDP string payload.
             */
            @Json(name = "sdp") val sdp: String
    )
}

