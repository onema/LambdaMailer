/**
  * This file is part of the ONEMA LambdaMailer Package.
  * For the full copyright and license information,
  * please view the LICENSE file that was distributed
  * with this source code.
  *
  * copyright (c) 2018, Juan Manuel Torres (http://onema.io)
  *
  * @author Juan Manuel Torres <kinojman@gmail.com>
  */

package onema.bounce

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.amazonaws.services.dynamodbv2.model.{AttributeValue, PutItemResult}
import com.amazonaws.services.lambda.runtime.events.SNSEvent.SNS
import com.typesafe.scalalogging.Logger
import onema.bounce.Logic.SnsNotification
import onema.core.json.Implicits._

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}


object Logic {
  case class SnsNotification(notificationType: String = "", bounce: Bounce = Bounce(), mail: Mail = Mail())
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
}

class Logic(val dynamodbClient: AmazonDynamoDBAsync, val table: String) {

  //--- Fields ---
  private val TTL = 604800 // 7 Days
  protected val log = Logger("bounce-logic")

  //--- Methods ---
  def handleRequest(snsRecord: SNS): Unit = {
    val snsPublishTime = snsRecord.getTimestamp.toString("yyyy-MM-dd'T'HH:mm:ss'Z'")
    val snsTopicArn = snsRecord.getTopicArn
    val sesMessage = snsRecord.getMessage.jsonParse[SnsNotification]
    val sesMessageId = sesMessage.mail.messageId
    val sesDestinationAddress = sesMessage.mail.destination.mkString(", ")

    if(sesMessage.notificationType == "Bounce") {
      val reportingMta = sesMessage.bounce.reportingMTA
      val sesBounceSummary = sesMessage.bounce.bouncedRecipients.toJson
      tryPutRecord(sesMessageId, snsPublishTime, reportingMta, sesDestinationAddress, sesBounceSummary, sesMessage.notificationType)
    }
  }

  def tryPutRecord(messageId: String, publishTime: String, reportingMTA: String, destinationAddress: String, summary: String, messageType: String): Unit = {
    Try(recordBounce(messageId, publishTime, reportingMTA, destinationAddress, summary, messageType)) match {
      case Success(response) =>
        log.info("Successfully recorded event")
      case Failure(ex) =>
        log.error("Unable to write record")
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
}
