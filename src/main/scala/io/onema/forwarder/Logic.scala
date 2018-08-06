/**
  * This file is part of the ONEMA lambda-mailer Package.
  * For the full copyright and license information,
  * please view the LICENSE file that was distributed
  * with this source code.
  *
  * copyright (c) 2018, Juan Manuel Torres (http://onema.io)
  *
  * @author Juan Manuel Torres <kinojman@gmail.com>
  */

package io.onema.forwarder

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.amazonaws.services.lambda.runtime.events.SNSEvent.SNS
import com.amazonaws.services.sns.AmazonSNSAsync
import com.typesafe.scalalogging.Logger
import io.onema.forwarder.Logic.{EmailMessage, SesMessage}
import io.onema.json.Extensions._

import scala.util.{Failure, Success, Try}

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

  case class EmailMessage(to: Seq[String], from: String, subject: String, body: String, replyTo: String = "")

}
class Logic(val dynamodbClient: AmazonDynamoDBAsync, val table: String, val snsClient: AmazonSNSAsync, val mailerTopic: String) {

  //--- Fields ---
  protected val log = Logger("forwarder-logic")



  //--- Methods ---
  def handleRequest(snsRecord: SNS, emailMapping: String): Unit = {
    log.debug(s"Mailer Topic: $mailerTopic")
    val message = snsRecord.getMessage.jsonDecode[SesMessage]
    val allowedForwardingEmailMapping = parseEmailMapping(emailMapping)
    val resultingForwardingEmails = getForwardingEmailAddresses(message.mail.destination, allowedForwardingEmailMapping)
    val subject = message.mail.commonHeaders.subject
    val content = message.content.split("Content-Type: ")
    val replyTo = message.mail.commonHeaders.returnPath
    val text = getTextFromContent(content)
    resultingForwardingEmails.foreach(x => {
      val from = x._1
      val to = x._2
      val emailMessage = EmailMessage(to, from, subject, text, replyTo).asJson
      log.debug(s"Json Message: $emailMessage")
      snsClient.publish(mailerTopic, emailMessage)
    })
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
    else throw new Exception(s"The destination emails $destination, do not contain a valid mapping in the configuration")
  }

  private def getTextFromContent(content: Seq[String]): String = {
    val plainText = Try(getFromContent(content,"text/plain")) match {
      case Success(seq) => seq
      case Failure(ex) => Seq()
    }
    val htmlText = Try(getFromContent(content, "text/html")) match {
      case Success(seq) => seq
      case Failure(ex) => Seq()
    }
    if(htmlText.nonEmpty) htmlText.mkString else plainText.mkString("<br />")
  }

  private def getFromContent(content: Seq[String], sectionName: String): Seq[String] = {
    content
      .filter(x => x.contains(sectionName))
      .head.split("\\r?\\n").drop(1).dropRight(1).filter(x => !x.contains("Content-Transfer-Encoding:"))
  }
}

