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

package io.onema.bounce

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.amazonaws.services.dynamodbv2.model.{AttributeValue, PutItemResult}
import com.amazonaws.services.lambda.runtime.events.SNSEvent.SNS
import com.typesafe.scalalogging.Logger
import io.onema.bounce.Logic.SnsNotification
import io.onema.json.Extensions._

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}


object Logic {
  case class SnsNotification(notificationType: String = "", bounce: Bounce = Bounce(), complaint: Complaint = Complaint(), mail: Mail = Mail())
  case class Bounce(
    bounceType: String = "",
    bounceSubType: String = "",
    bouncedRecipients: List[BouncedRecipients] = List(),
    timestamp: String = "",
    feedbackId: String = "",
    remoteMtaIp: String = "",
    reportingMTA: String = ""
  )
  case class Mail(
    timestamp: String = "",
    source: String = "",
    sourceArn: String = "",
    sourceIp: String = "",
    sendingAccountId: String = "",
    messageId: String = "",
    destination: List[String] = List(),
    headersTruncated: Boolean = false,
    headers: List[Headers] = List(),
    commonHeaders: CommonHeaders = CommonHeaders()
  )
  case class BouncedRecipients(emailAddress: String = "", action: String = "", status: String = "", diagnosticCode: String = "")
  case class Headers(name: String = "", value: String = "")
  case class CommonHeaders(from: List[String] = List(), to: List[String] = List(), subject: String = "")
  case class ComplainedRecipients(emailAddress: String = "")
  case class Complaint(
    userAgent: String = "",
    complainedRecipients: List[ComplainedRecipients] = List(),
    complaintFeedbackType: String = "",
    arrivalDate: String = "",
    timestamp: String = "",
    feedbackId: String = ""
  )
}

class Logic(val dynamodbClient: AmazonDynamoDBAsync, val table: String) {

  //--- Fields ---
  private val TTL = 604800 // 7 Days
  protected val log = Logger("bounce-logic")

  //--- Methods ---
  def handleRequest(snsRecord: SNS): Unit = {
    val snsPublishTime = snsRecord.getTimestamp.toString("yyyy-MM-dd'T'HH:mm:ss'Z'")
    val snsTopicArn = snsRecord.getTopicArn
    val sesMessage = snsRecord.getMessage.jsonDecode[SnsNotification]
    val sesMessageId = sesMessage.mail.messageId

    if(sesMessage.notificationType == "Bounce") {
      val sesDestinationAddresses = sesMessage.bounce.bouncedRecipients
      val reportingMta = sesMessage.bounce.reportingMTA
      val sesBounceSummary = sesMessage.bounce.bouncedRecipients.asJson
      sesDestinationAddresses.foreach(destination => {
        tryPutBounceRecord(sesMessageId, snsPublishTime, reportingMta, destination.emailAddress, sesBounceSummary, sesMessage.notificationType)
      })
    } else if(sesMessage.notificationType == "Complaint") {
      val sesDestinationAddresses = sesMessage.complaint.complainedRecipients
      val feedbackId = sesMessage.complaint.feedbackId
      val feedbackType = sesMessage.complaint.complaintFeedbackType
      sesDestinationAddresses.foreach(destination => {

        tryPutComplaintRecord(sesMessageId, snsPublishTime, feedbackId, destination.emailAddress, feedbackType)
      })
    }
  }

  def tryPutBounceRecord(messageId: String, publishTime: String, reportingMTA: String, destinationAddress: String, summary: String, messageType: String): Unit = {
    Try(recordBounce(messageId, publishTime, reportingMTA, destinationAddress, summary, messageType)) match {
      case Success(response) =>
        log.info("Successfully recorded bounce event")
      case Failure(ex) =>
        log.error("Unable to write bounce record")
        throw ex
    }
  }
  def tryPutComplaintRecord(messageId: String, publishTime: String, feedbackId: String, sesDestinationAddress: String, feedbackType: String): Unit = {
    Try(recordComplaint(messageId, publishTime, feedbackId, sesDestinationAddress, feedbackType)) match {
      case Success(response) =>
        log.info("Successful recorded complaint event")
      case Failure(ex) =>
        log.error("Unable to write complaint record")
        throw ex
    }
  }

  def recordBounce(messageId: String, publishTime: String, reportingMTA: String, destinationAddress: String, summary: String, messageType: String): PutItemResult = {
    log.debug(s"messageId: $messageId, publishTime: $publishTime, destination: $destinationAddress")
    dynamodbClient.putItem(
      table,
      Map(
        "MessageId" -> new AttributeValue().withS(messageId),
        "DestinationAddress" -> new AttributeValue().withS(destinationAddress),
        "SnsPublishTime" -> new AttributeValue().withS(publishTime),
        "SESReportingMTA" -> new AttributeValue().withS(reportingMTA),
        "SESBounceSummary" -> new AttributeValue().withS(summary),
        "SESMessageType" -> new AttributeValue().withS(messageType),
        "ExpirationTime" -> new AttributeValue().withN(((System.currentTimeMillis / 1000) + TTL).toString)
      ).asJava
    )
  }

  def recordComplaint(messageId: String, publishTime: String, feedbackId: String, destinationAddress: String, feedbackType: String): PutItemResult = {
    log.debug(s"messageId: $messageId, publishTime: $publishTime, destination: $destinationAddress, feedbackType: $feedbackType")
    dynamodbClient.putItem(
      table,
      Map(
        "MessageId" -> new AttributeValue().withS(messageId),
        "DestinationAddress" -> new AttributeValue().withS(destinationAddress),
        "SnsPublishTime" -> new AttributeValue().withS(publishTime),
        "FeedbackId" -> new AttributeValue().withS(feedbackId),
        "FeedbackType" -> new AttributeValue().withS(feedbackType)
      ).asJava
    )
  }
}
