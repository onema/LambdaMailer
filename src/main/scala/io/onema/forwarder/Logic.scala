/**
  * This file is part of the ONEMA lambda-mailer Package.
  * For the full copyright and license information,
  * please view the LICENSE file that was distributed
  * with this source code.
  *
  * copyright (c) 2018, Juan Manuel Torres (http://onema.io)
  *
  * @author Juan Manuel Torres <software@onema.io>
  */

package io.onema.forwarder

import java.io.ByteArrayInputStream
import java.util.Properties

import com.amazonaws.services.sns.AmazonSNSAsync
import com.typesafe.scalalogging.Logger
import io.onema.forwarder.Logic.{EmailMessage, SesMessage}
import io.onema.json.Extensions._
import javax.mail.Session
import javax.mail.internet.MimeMessage
import org.apache.commons.mail.util.MimeMessageParser

object Logic {
  case class SesMessage(notificationType: String, mail: Mail, receipt: Receipt, content: String)
  case class Headers(name: String, value: String)
  case class CommonHeaders(returnPath: String, from: List[String], date: String, to: List[String], messageId: String, subject: String)
  case class Mail(
    timestamp: String,
    source: String,
    messageId: String,
    destination: List[String],
    headersTruncated: Boolean,
    headers: List[Headers],
    commonHeaders: CommonHeaders
  )
  case class SpamVerdict(status: String)
  case class Action( `type`: String, topicArn: String, encoding: String)
  case class Receipt(
    timestamp: String,
    processingTimeMillis: Double,
    recipients: List[String],
    spamVerdict: SpamVerdict,
    virusVerdict: SpamVerdict,
    spfVerdict: SpamVerdict,
    dkimVerdict: SpamVerdict,
    dmarcVerdict: SpamVerdict,
    action: Action
  )

  case class EmailMessage(to: Seq[String], from: String, subject: String, body: String, replyTo: String = "", raw: Boolean = false)
}

class Logic(val snsClient: AmazonSNSAsync, val mailerTopic: String) {

  //--- Fields ---
  protected val log = Logger("forwarder-logic")

  //--- Methods ---
  def handleRequest(message: SesMessage, emailMapping: String): Unit = {
    log.debug(s"Mailer Topic: $mailerTopic")

    // The email mapping will get us all the addresses associated with the forwarder address
    val allowedForwardingEmailMapping = parseEmailMapping(emailMapping)
    val resultingForwardingEmails = getForwardingEmailAddresses(message.mail.destination, allowedForwardingEmailMapping)

    // Extract values from the message
    val (subject, replyTo, origin, parser) = getMessageValues(message)

    // iterate over each mapped address and forward the email
    log.debug(resultingForwardingEmails.toString())
    resultingForwardingEmails.foreach{case (from, toEmails) =>
      toEmails.foreach(to => {
        log.debug(s"FROM: $from TO: $to REPLY-TO: $replyTo ORIGIN: $origin")
        val rawContent = if(parser.hasHtmlContent) parser.getHtmlContent else parser.getPlainContent
        val emailMessage = EmailMessage(Seq(to), from, subject, rawContent, replyTo).asJson
        log.debug(s"Json Message: $emailMessage")
        snsClient.publish(mailerTopic, emailMessage)
      })
    }
  }

  private def parseMessage(content: String): MimeMessageParser = {
    val s = Session.getInstance(new Properties())
    val is = new ByteArrayInputStream(content.getBytes)
    val mimeMessage = new MimeMessage(s, is)
    val parser = new MimeMessageParser(mimeMessage)
    parser.parse()
  }

  private def getMessageValues(message: SesMessage): (String, String, String, MimeMessageParser) = {
    (
      message.mail.commonHeaders.subject,
      message.mail.commonHeaders.from.head,
      message.mail.source,

      // Parse the raw message using Apache commons
      parseMessage(message.content)
    )
  }

  private def parseEmailMapping(mapping: String): Map[String, Seq[String]] = {
    val parts = mapping
      .split('&')
      parts.map(x => {
        val keyValueParts = x.split('=')
        if(keyValueParts.length < 2) throw new Exception("The email mappings are not properly formatted, see documentation for more information.")
        keyValueParts(0) -> keyValueParts(1).split(',').toSeq
      }).toMap[String, Seq[String]]
  }

  private def getForwardingEmailAddresses(destination: Seq[String], allowedForwardingEmailMapping: Map[String, Seq[String]]): Map[String, Seq[String]] = {
    val resultingEmailsMapping = allowedForwardingEmailMapping.filterKeys(destination.contains)
    if(resultingEmailsMapping.nonEmpty) resultingEmailsMapping
    else throw new Exception(s"The destination emails $destination, do not contain a valid mapping in the configuration. Received $destination, allowed $allowedForwardingEmailMapping")
  }
}
