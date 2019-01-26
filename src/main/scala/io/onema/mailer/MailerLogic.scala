/**
  * This file is part of the ONEMA LambdaMailer Package.
  * For the full copyright and license information,
  * please view the LICENSE file that was distributed
  * with this source code.
  *
  * copyright (c) 2018, Juan Manuel Torres (http://onema.io)
  *
  * @author Juan Manuel Torres <software@onema.io>
  */

package io.onema.mailer

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Properties

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap
import com.amazonaws.services.dynamodbv2.document.{DynamoDB, ItemCollection, QueryOutcome}
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService
import com.amazonaws.services.simpleemail.model._
import com.sun.mail.smtp.SMTPMessage
import com.typesafe.scalalogging.Logger
import io.onema.userverless.monitoring.LogMetrics._
import io.onema.mailer.MailerLogic.Email
import io.onema.vff.FileSystem
import io.onema.vff.adapter.AwsS3Adapter
import io.onema.vff.extensions.StreamExtensions._
import javax.activation.{DataHandler, FileDataSource}
import javax.mail.internet.{InternetAddress, MimeBodyPart, MimeMultipart}
import javax.mail.{Message, Session}
import net.logstash.logback.argument.StructuredArguments._

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

object MailerLogic {
  case class Email(to: Seq[String], from: String, subject: String, body: String, raw: Boolean = false, replyTo: Option[String] = None, attachments: Option[Seq[String]] = None) {

    /**
      * Build a request from the Email object and any attachments passed to it
      * @param atts list of mime body parts containing attachments
      * @return
      */
    def request(atts: Seq[MimeBodyPart]): SendRawEmailRequest = {

      // Create a new smtpMessage to build the Email
      val session = Session.getInstance(new Properties())
      val smtpMessage = new SMTPMessage(session)

      // Create body message and wrapper
      val messageBody = new MimeMultipart("alternative")
      val wrapper = new MimeBodyPart()
      val multiPartMessage = new MimeMultipart("mixed")

      // Define HTML Part
      val htmlPart = new MimeBodyPart()
      Option(body) match {
        case Some(b) => htmlPart.setContent(b, "text/html; charset=UTF-8")
        case None => htmlPart.setContent("", "text/html; charset=UTF-8")
      }

      messageBody.addBodyPart(htmlPart)
      wrapper.setContent(messageBody)
      smtpMessage.setContent(multiPartMessage)
      multiPartMessage.addBodyPart(wrapper)

      // Add any attachments, all attachments must be in a local directory at this point
      atts.foreach(multiPartMessage.addBodyPart)

      // Set basic email information
      smtpMessage.setSubject(subject)
      smtpMessage.setFrom(from)
      smtpMessage.setEnvelopeFrom(from)
      smtpMessage.setSender(new InternetAddress(from))

      // Set the reply to value if one is available
      replyTo.foreach(x => {
        smtpMessage.setReplyTo(Array(new InternetAddress(x)))
      })

      // Add all the recipients as a string of addresses separated by commas
      smtpMessage.setRecipients(Message.RecipientType.TO, to.mkString(","))

      // Write the message to the output stream
      val os = new ByteArrayOutputStream()
      smtpMessage.writeTo(os)

      // Create the Send Raw Email Request
      val message = new RawMessage().withData(ByteBuffer.wrap(os.toByteArray))
      val request = new SendRawEmailRequest()
        .withDestinations(to.asJava)
        .withRawMessage(message)
        .withSource(from)
      request
    }


    lazy val rawRequest: SendRawEmailRequest = {
      val message = new RawMessage().withData(ByteBuffer.wrap(body.getBytes()))
      val request = new SendRawEmailRequest()
        .withDestinations(to.asJava)
        .withRawMessage(message)
        .withSource(from)
      request
    }
  }
}

class MailerLogic(val sesClient: AmazonSimpleEmailService, val dynamodbClient: AmazonDynamoDBAsync, val s3Client: AmazonS3, val tableName: String, val bucketName: String, val shouldLogEmail: Boolean) {

  //--- Fields ---
  protected val log = Logger("mailer-logic")
  private val dynamoDb = new DynamoDB(dynamodbClient)
  private val table = dynamoDb.getTable(tableName)
  private val local = FileSystem()
  private val s3 = new FileSystem(new AwsS3Adapter(s3Client, bucketName))

  //--- Methods ---
  def handleRequest(email: Email): Unit = {
    val filteredEmails = email.copy(to = email.to.filter(isNotBlocked))
    if(filteredEmails.to.nonEmpty) {
      val value = if(shouldLogEmail) filteredEmails.to else s"${filteredEmails.to.size} emails"
      log.info(s"Sending message to $value")
      sendEmail(filteredEmails)
    } else {
      log.info(s"All emails are blocked")
    }
  }

  def sendEmail(email: Email): Unit = {
    if(email.raw) {
      log.debug("Sending raw email", keyValue("RAW-BODY", email.body))
      sesClient.sendRawEmail(email.rawRequest)
    } else {
      log.debug("Sending", keyValue("BODY", email.body))
      val atts = attachments(email)
      sesClient.sendRawEmail(email.request(atts))
      count("emailSent")
    }
    log.info("Email sent successfully")
  }

  /**
    * Check if the destination address is in the bounce/complaint list
    * @param destinationAddress destination address to check for
    * @return
    */
  def isNotBlocked(destinationAddress: String): Boolean = {
    Try(findEmail(destinationAddress)) match {
      case Success(response) =>
        log.info(s"Successfully query the $tableName table")

        // If it does find any items in the table, the address is not blocked
        !response.iterator().hasNext
      case Failure(ex) =>
        log.error("Unable to read from table")
        throw ex
    }
  }

  /**
    * Find an email in the bounce/complaint table
    * @param destinationAddress destination address to check for
    * @return
    */
  private def findEmail(destinationAddress: String): ItemCollection[QueryOutcome]  = {
    val index = table.getIndex("EmailIndex")
    val query = new QuerySpec()
      .withKeyConditionExpression("DestinationAddress = :v_email")
      .withValueMap(new ValueMap().withString(":v_email", destinationAddress))
    index.query(query)
  }

  /**
    * Check for any attachment anc create a MimeBodyPart for each of them. Set the original ContentId if it was
    * set in the user metadata
    * @param email object containing information about the attachments if any.
    * @return
    */
  private def attachments(email: Email): Seq[MimeBodyPart] = email.attachments match {
    case Some(att) => att.map(a => {
      val destinationFile = s"/tmp/${a.stripPrefix("/")}"
      log.debug(s"Downloading attachment from $a")
      val s3Object = s3Client.getObject(bucketName, a.stripPrefix("/"))
      local.write(destinationFile, s3Object.getObjectContent.toBytes)
      val metadata = s3Object.getObjectMetadata
      val att = new MimeBodyPart()
      val fds = new FileDataSource(destinationFile)

      // Add the content id of the attachment if it exists
      Option(metadata.getUserMetaDataOf("ContentId")).foreach(att.setContentID)
      att.setDataHandler(new DataHandler(fds))
      att.setFileName(fds.getName)
      att
    })
    case None => Seq()
  }
}
