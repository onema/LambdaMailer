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
import com.gu.scanamo._
import com.gu.scanamo.error.DynamoReadError
import com.typesafe.scalalogging.Logger
import io.onema.bounce.BounceLogic.{BounceComplaintItem, BounceData, ComplaintData, SesNotification}
import io.onema.json.Extensions._


object BounceLogic {
  case class BounceComplaintItem(messageId: String, destinationAddress: String, snsPublishTime: String, ttl: Option[String], bounceData: Option[String], complaintData: Option[String])
  case class BounceData(sesReportingMTA: String , sesBounceSummary: String, sesMessageType: String)
  case class ComplaintData(feedbackId: String, feedbackType: String)

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

class BounceLogic(val dynamodbClient: AmazonDynamoDBAsync, val tableName: String) {

  //--- Fields ---
  private val TTL = 604800 // 7 Days
  protected val log = Logger("bounce-logic")
  private val table = Table[BounceComplaintItem](tableName)

  //--- Methods ---
  def handleRequest(snsPublishTime: String, sesMessageId: String, sesMessage: SesNotification): Unit = {
    log.debug(sesMessage.asJson)
    log.info(s"Notification type: ${sesMessage.notificationType}")
    if(sesMessage.notificationType == "Bounce") {
      val sesDestinationAddresses = sesMessage.bounce.bouncedRecipients
      val reportingMta = sesMessage.bounce.reportingMTA
      val sesBounceSummary = sesMessage.bounce.bouncedRecipients.asJson
      sesDestinationAddresses.foreach(destination => {
        val ttl = ((System.currentTimeMillis / 1000) + TTL).toInt.toString
        val bounceItem = BounceComplaintItem(sesMessageId, destination.emailAddress, snsPublishTime, Some(ttl), Some(BounceData(reportingMta, sesBounceSummary, sesMessage.notificationType).asJson), None)
        tryPutRecord(bounceItem, "bounce")
      })
    } else if(sesMessage.notificationType == "Complaint") {
      val sesDestinationAddresses = sesMessage.complaint.complainedRecipients
      val feedbackId = sesMessage.complaint.feedbackId
      val feedbackType = sesMessage.complaint.complaintFeedbackType
      sesDestinationAddresses.foreach(destination => {
        val complaintItem = BounceComplaintItem(sesMessageId, destination.emailAddress, snsPublishTime, None, None, Some(ComplaintData(feedbackId, feedbackType).asJson))
        tryPutRecord(complaintItem, "complaint")
      })
    }
  }

  def tryPutRecord(bounceItem: BounceComplaintItem, event: String): Unit = {
    val response = saveItem(bounceItem)
    log.debug(s"Scanamom response ${response.toString}")
    if(response.isEmpty && !response.exists(_.isLeft)) {
      log.info(s"Successfully recorded $event sesMessage")
    } else {
      log.error(s"Unable to write $event record")
    }
  }

  def saveItem(bounceItem: BounceComplaintItem): Option[Either[DynamoReadError, BounceComplaintItem]] = {
    val operations = table.put(bounceItem)
    Scanamo.exec(dynamodbClient)(operations)
  }
}
