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

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.sns.AmazonSNS
import com.sun.mail.smtp.SMTPMessage
import com.typesafe.scalalogging.Logger
import io.onema.forwarder.ForwarderLogic.SesMessage
import io.onema.json.Extensions._
import io.onema.mailer.MailerLogic.Email
import io.onema.userverless.monitoring.LogMetrics._
import io.onema.vff.FileSystem
import io.onema.vff.adapter.AwsS3Adapter
import io.onema.vff.extensions.StreamExtensions._
import javax.activation.DataSource
import javax.mail.Session
import net.logstash.logback.argument.StructuredArguments._
import org.apache.commons.mail.util.MimeMessageParser
import org.json4s.FieldSerializer._
import org.json4s.jackson.Serialization
import org.json4s.{FieldSerializer, Formats, NoTypeHints}

import scala.collection.JavaConverters._
import scala.io.Source

object ForwarderLogic {
  case class Headers(name: String, value: String)
  case class CommonHeaders(returnPath: String, from: List[String], date: String, to: List[String], subject: String, messageId: Option[String], replyTo: Option[List[String]], sender: Option[String])
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

class ForwarderLogic(val snsClient: AmazonSNS, val mailerTopic: String, val s3Client: AmazonS3, val bucketName: String, val attachmentBucket: String, val logEmail: Boolean = false) {

  //--- Fields ---
  protected val log = Logger("forwarder-logic")
  private val s3 = new FileSystem(new AwsS3Adapter(s3Client, attachmentBucket))

  //--- Methods ---
  def handleRequest(message: SesMessage, emailMapping: String): Unit = {

    // The email mapping will get us all the addresses associated with the forwarder address
    val messageId = message.mail.messageId
    val allowedForwardingEmailMapping = parseEmailMapping(emailMapping)
    val resultingForwardingEmails = getForwardingEmailAddresses(message.receipt.recipients.map(_.toLowerCase), allowedForwardingEmailMapping)
    log.debug(s"addresses found: $resultingForwardingEmails")

    // Get message from s3 and parse it
    val responseInputStream = s3Client.getObject(bucketName, message.mail.messageId).getObjectContent
    val originalContent = Source.fromInputStream(responseInputStream).mkString

    // iterate over each mapped address and forward the email
    resultingForwardingEmails.foreach{case (from, toEmails) =>
      toEmails.foreach(to => {
        val (parser, _) = parseMessage(originalContent)
        val htmlContent = content(parser)
        val att = attachments(parser, messageId)
        val subject = message.mail.commonHeaders.subject
        val originalSender: String = message.mail.commonHeaders.from.mkString(",")

        // Send message to mailer
        val emailMessage = Email(Seq(to), from, subject, htmlContent, replyTo = Some(originalSender), attachments = att).asJson
        log.debug(s"MESSAGE: $emailMessage", keyValue("SUBJECT", subject))
        snsClient.publish(mailerTopic, emailMessage)
        if(logEmail) count("ForwardingEmail", ("from email", originalSender))
      })
    }
  }

  private def parseMessage(content: String): (MimeMessageParser, SMTPMessage) = {
    val s = Session.getInstance(new Properties())
    val is = new ByteArrayInputStream(content.getBytes)
    val smtpMessage = new SMTPMessage(s, is)
    val parser = new MimeMessageParser(smtpMessage)
    (parser.parse(), smtpMessage)
  }

  private def content(mimeMessageParser: MimeMessageParser): String = {
    if(mimeMessageParser.hasHtmlContent) {
      mimeMessageParser.getHtmlContent
    } else {
      mimeMessageParser.getPlainContent
    }
  }

  private def attachments(mimeMessageParser: MimeMessageParser, messageId: String): Option[Seq[String]] = {
    if(mimeMessageParser.hasAttachments) {
      val attachmentMap = mimeMessageParser
        .getContentIds.asScala
        .map(x => (x, mimeMessageParser.findAttachmentByCid(x)))
        .filter(_._2 != null)
        .toMap
      val files = if(attachmentMap.size == mimeMessageParser.getAttachmentList.size()) {
        attachmentMap.map { case(cid, file) => uploadAttachment(file,cid, messageId)}
      } else {
        mimeMessageParser.getAttachmentList.asScala.map(uploadAttachment(_, messageId))
      }
      Some(files.toSeq)
    } else {
      None
    }
  }

  private def uploadAttachment(x: DataSource, messageId: String): String = {
    val fileBytes = x.getInputStream.toBytes
    val destinationName = s"/$messageId/${x.getName}"
    log.debug(s"Uploading attachment to $destinationName")
    s3.write(destinationName, fileBytes)
    destinationName
  }

  private def uploadAttachment(x: DataSource, contentId: String, messageId: String): String = {
    val destinationName = s"$messageId/${x.getName}"
    log.debug(s"Uploading attachment to $destinationName")
    val metadata = new ObjectMetadata()
    metadata.addUserMetadata("ContentId", contentId)
    s3Client.putObject(attachmentBucket, destinationName, x.getInputStream, metadata)
    destinationName
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
