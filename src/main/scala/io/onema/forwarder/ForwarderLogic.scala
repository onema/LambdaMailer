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

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.sns.AmazonSNS
import com.sun.mail.smtp.SMTPMessage
import com.typesafe.scalalogging.Logger
import io.onema.forwarder.ForwarderLogic.{ForwarderMappingItem, SesMessage}
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
import com.gu.scanamo.error.DynamoReadError
import com.gu.scanamo.syntax._
import com.gu.scanamo._

import scala.collection.JavaConverters._
import scala.io.Source

object ForwarderLogic {
  case class ForwarderMappingItem(forwardingEmail: String, destinationEmail: String)

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

class ForwarderLogic(snsClient: AmazonSNS, val s3Client: AmazonS3, val dynamoDbClient: AmazonDynamoDB, val tableName: String, val mailerTopic: String, val bucketName: String, val attachmentBucket: String, val logEmail: Boolean = false) {

  //--- Fields ---
  protected val log = Logger("forwarder-logic")
  private val s3 = new FileSystem(new AwsS3Adapter(s3Client, attachmentBucket))
  private val table = Table[ForwarderMappingItem](tableName)

  //--- Methods ---
  def handleRequest(message: SesMessage): Unit = {

    // The email mapping will get us all the addresses associated with the forwarder address
    val resultingForwardingEmails = message.receipt.recipients.map(_.toLowerCase).map(getEmails)
    log.debug(s"addresses found: $resultingForwardingEmails")

    // Get message from s3 and parse it
    val responseInputStream = s3Client.getObject(bucketName, message.mail.messageId).getObjectContent
    val originalContent = Source.fromInputStream(responseInputStream).mkString

    // iterate over each mapped address and forward the email
    for {
      (from, toEmails) <- resultingForwardingEmails
      to <- toEmails
    } yield send(to, from, originalContent, message)
  }

  private def send(to: String, from: String, originalContent: String, message: SesMessage): Unit = {
    val messageId = message.mail.messageId
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

  def getEmails(forwardingEmail: String): (String, Seq[String]) = {
    val items = findEmail(forwardingEmail)

    // Warn if there are any read errors in the sequence but don't
    if(items.exists(_.isLeft)) {
      log.warn("There were errors reading items from the table")
    }
    val destination = items.filter(_.isRight)
      .map(_.right.get.destinationEmail)
    if(destination.isEmpty) {
      throw new Exception(s"The email $forwardingEmail does not contain any mapping values")
    }
    forwardingEmail -> destination
  }

  private def findEmail(forwardingEmail: String): Seq[Either[DynamoReadError, ForwarderMappingItem]] = {
    val operations = table.query('forwardingEmail -> forwardingEmail)
    Scanamo.exec(dynamoDbClient)(operations)
  }
}
