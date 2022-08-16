package org.signal.smsexporter

/**
 * Represents an exportable MMS or SMS message
 */
sealed interface ExportableMessage {

  /**
   * An exportable SMS message
   */
  data class Sms(
    val id: String,
    val address: String,
    val dateReceived: Long,
    val dateSent: Long,
    val isRead: Boolean,
    val isOutgoing: Boolean,
    val body: String
  ) : ExportableMessage

  /**
   * An exportable MMS message
   */
  data class Mms(
    val id: String,
    val addresses: Set<String>,
    val dateReceived: Long,
    val dateSent: Long,
    val isRead: Boolean,
    val isOutgoing: Boolean,
    val parts: List<Part>,
    val sender: CharSequence
  ) : ExportableMessage {
    /**
     * An attachment, attached to an MMS message
     */
    sealed interface Part {

      val contentType: String
      val contentId: String

      data class Text(val text: String) : Part {
        override val contentType: String = "text/plain"
        override val contentId: String = "text"
      }
      data class Stream(
        val id: String,
        override val contentType: String
      ) : Part {
        override val contentId: String = id
      }
    }
  }
}
