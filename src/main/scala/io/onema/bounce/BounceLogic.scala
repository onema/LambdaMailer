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

import com.typesafe.scalalogging.Logger
import io.onema.AwsExtensions._
import io.onema.bounce.BounceLogic.SesNotification
import io.onema.json.Extensions._
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.{AttributeValue, PutItemRequest}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}


object BounceLogic {
  case class SesNotification(notificationType: String = "", bounce: Bounce = Bounce(), complaint: Complaint = Complaint(), mail: Mail = Mail())
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

class BounceLogic(val dynamodbClient: DynamoDbAsyncClient, val table: String) {

  //--- Fields ---
  private val TTL = 604800 // 7 Days
  protected val log = Logger("bounce-logic")

  //--- Methods ---
  def handleRequest(snsPublishTime: String, sesMessageId: String, sesMessage: SesNotification): Unit = {
    if(sesMessage.notificationType == "Bounce") {
      val sesDestinationAddresses = sesMessage.bounce.bouncedRecipients
      val reportingMta = sesMessage.bounce.reportingMTA
      val sesBounceSummary = sesMessage.bounce.bouncedRecipients.asJson
      sesDestinationAddresses.foreach(destination => {
        putBounceRecord(sesMessageId, snsPublishTime, reportingMta, destination.emailAddress, sesBounceSummary, sesMessage.notificationType)
      })
    } else if(sesMessage.notificationType == "Complaint") {
      val sesDestinationAddresses = sesMessage.complaint.complainedRecipients
      val feedbackId = sesMessage.complaint.feedbackId
      val feedbackType = sesMessage.complaint.complaintFeedbackType
      sesDestinationAddresses.foreach(destination => {
        putComplaintRecord(sesMessageId, snsPublishTime, feedbackId, destination.emailAddress, feedbackType)
      })
    }
  }

  def putBounceRecord(messageId: String, publishTime: String, reportingMTA: String, destinationAddress: String, summary: String, messageType: String): Unit = {
    log.debug(s"messageId: $messageId, publishTime: $publishTime, destination: $destinationAddress")
    tryPutItem(
      Map(
        "MessageId" -> AttributeValue.builder().s(messageId).build(),
        "DestinationAddress" -> AttributeValue.builder().s(destinationAddress).build(),
        "SnsPublishTime" -> AttributeValue.builder().s(publishTime).build(),
        "SESReportingMTA" -> AttributeValue.builder().s(reportingMTA).build(),
        "SESBounceSummary" -> AttributeValue.builder().s(summary).build(),
        "SESMessageType" -> AttributeValue.builder().s(messageType).build(),
        "ExpirationTime" -> AttributeValue.builder().n(((System.currentTimeMillis / 1000) + TTL).toString).build()
      )
    ).onComplete {
      case Success(_) =>
        log.info("Successfully recorded bounce sesMessage")
      case Failure(ex) =>
        log.error("Unable to write bounce record")
        throw ex
    }
  }

  def putComplaintRecord(messageId: String, publishTime: String, feedbackId: String, sesDestinationAddress: String, feedbackType: String): Unit = {
    log.debug(s"messageId: $messageId, publishTime: $publishTime, destination: $sesDestinationAddress, feedbackType: $feedbackType")
    tryPutItem(
      Map(
        "MessageId" -> AttributeValue.builder().s(messageId).build(),
        "DestinationAddress" -> AttributeValue.builder().s(sesDestinationAddress).build(),
        "SnsPublishTime" -> AttributeValue.builder().s(publishTime).build(),
        "FeedbackId" -> AttributeValue.builder().s(feedbackId).build(),
        "FeedbackType" -> AttributeValue.builder().s(feedbackType).build()
      )
    ).onComplete {
      case Success(_) =>
        log.info("Successful recorded complaint sesMessage")
      case Failure(ex) =>
        log.error("Unable to write complaint record")
        throw ex
    }
  }

  private def tryPutItem(attributeValues: Map[String, AttributeValue]) = {
    dynamodbClient.putItem(
      PutItemRequest.builder()
        .tableName(table)
        .item(attributeValues.asJava)
        .build()
    ).asScala
  }
}
