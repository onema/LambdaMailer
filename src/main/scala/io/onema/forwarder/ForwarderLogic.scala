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

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.Properties

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.sns.AmazonSNS
import com.typesafe.scalalogging.Logger
import io.onema.forwarder.ForwarderLogic.SesMessage
import io.onema.json.Extensions._
import io.onema.mailer.MailerLogic.Email
import javax.mail.internet.{InternetAddress, MimeMessage}
import javax.mail.{Address, Message, Session}
import net.logstash.logback.argument.StructuredArguments._
import org.apache.commons.mail.util.MimeMessageParser
import org.json4s.FieldSerializer._
import org.json4s.jackson.Serialization
import org.json4s.{FieldSerializer, Formats, NoTypeHints}

import scala.io.Source

object ForwarderLogic {
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
  case class Action(actionType: String, functionArn: String, encoding: Option[String])
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
  case class SesMessage(notificationType: Option[String], mail: Mail, receipt: Receipt, content: Option[String])
  case class Records(eventSource: Option[String], eventVersion: Option[String], ses: SesMessage)
  case class SesEvent(records: List[Records])

  def fieldRenames: Formats = {
    Serialization.formats(NoTypeHints) +
    FieldSerializer[SesEvent](
      renameTo("records", "Records"),
      renameFrom("Records", "records")
    ) +
    FieldSerializer[Action](
      renameTo("actionType", "type"),
      renameFrom("type", "actionType")
    )
  }
}

class ForwarderLogic(val snsClient: AmazonSNS, val mailerTopic: String, val s3Client: AmazonS3, val bucketName: String) {

  //--- Fields ---
  protected val log = Logger("forwarder-logic")

  //--- Methods ---
  def handleRequest(message: SesMessage, emailMapping: String): Unit = {

    // The email mapping will get us all the addresses associated with the forwarder address
    val allowedForwardingEmailMapping = parseEmailMapping(emailMapping)
    val resultingForwardingEmails = getForwardingEmailAddresses(message.receipt.recipients, allowedForwardingEmailMapping)
    log.debug(s"addresses found: $resultingForwardingEmails")

    // Get message from s3 and parse it
    val responseInputStream = s3Client.getObject(bucketName, message.mail.messageId).getObjectContent
    val originalContent = Source.fromInputStream(responseInputStream).mkString

    // iterate over each mapped address and forward the email
    resultingForwardingEmails.foreach{case (from, toEmails) =>
      toEmails.foreach(to => {
        val content = rawMessage(originalContent, message, to, from)
        val subject = message.mail.commonHeaders.subject

        // Send message to mailer
        val emailMessage = Email(Seq(to), from, subject, content, raw = true).asJson
        log.debug(s"MESSAGE: $emailMessage", keyValue("SUBJECT", subject))
        snsClient.publish(mailerTopic, emailMessage)
      })
    }
  }

  private def parseMessage(content: String): (MimeMessageParser, MimeMessage) = {
    val s = Session.getInstance(new Properties())
    val is = new ByteArrayInputStream(content.getBytes)
    val mimeMessage = new MimeMessage(s, is)
    val parser = new MimeMessageParser(mimeMessage)
    (parser.parse(), mimeMessage)
  }

  private def rawMessage(content: String, message: SesMessage, to: String, from: String): String = {

    // parse the content and get the mimeMessage
    val (_, mimeMessage) = parseMessage(content)

    // Set reply-to address
    val replyTo: Array[Address] = message.mail.commonHeaders.from.map(new InternetAddress(_)).toArray
    mimeMessage.setReplyTo(replyTo)

    // Set to address
    val toAddresses: Array[Address] = Seq(new InternetAddress(to)).toArray
    mimeMessage.setRecipients(Message.RecipientType.TO, toAddresses)

    // Set from address
    mimeMessage.setFrom(from)

    // Recreate raw email
    val os = new ByteArrayOutputStream()
    mimeMessage.writeTo(os)
    log.debug("RAW EMAIL info", keyValue("FROM", from), keyValue("REPLY-TO", s"${message.mail.commonHeaders.from}"))
    val newMessage = os.toString()
    if(newMessage.getBytes.length > 131072) {
      log.warn(s"${newMessage.getBytes.length} byte payload is too large for the SNS Event invocation (limit 131072 bytes)")
    }
    newMessage
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