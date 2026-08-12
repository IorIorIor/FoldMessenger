package com.foldmessenger.app

/**
 * Shared configuration. TOPIC_BASE is the shared secret: anyone who knows it can
 * send to the phones. Must match TOPIC_BASE in sender.html. Change both to rotate.
 */
object Config {
    const val NTFY_SERVER = "https://ntfy.sh"
    const val TOPIC_BASE = "fm-pw3h5q3z"
    /** Handsets in the field. Keep in sync with PHONE_COUNT in sender.html. */
    const val PHONE_COUNT = 6

    /** Tables of players, one in play at a time. Keep in sync with sender.html. */
    const val TABLE_COUNT = 3

    fun phoneTopic(id: Int) = "$TOPIC_BASE-p$id"
    fun allTopic() = "$TOPIC_BASE-all"

    /** Where phones publish their answers to the final question. */
    fun votesTopic() = "$TOPIC_BASE-votes"
}
