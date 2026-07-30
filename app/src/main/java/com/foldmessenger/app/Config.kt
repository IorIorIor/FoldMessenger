package com.foldmessenger.app

/**
 * Shared configuration. TOPIC_BASE is the shared secret: anyone who knows it can
 * send to the phones. Must match TOPIC_BASE in sender.html. Change both to rotate.
 */
object Config {
    const val NTFY_SERVER = "https://ntfy.sh"
    const val TOPIC_BASE = "fm-pw3h5q3z"
    const val PHONE_COUNT = 8

    fun phoneTopic(id: Int) = "$TOPIC_BASE-p$id"
    fun allTopic() = "$TOPIC_BASE-all"
}
