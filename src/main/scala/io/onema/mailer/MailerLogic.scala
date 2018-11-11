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

import java.nio.ByteBuffer

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap
import com.amazonaws.services.dynamodbv2.document.{DynamoDB, ItemCollection, QueryOutcome}
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService
import com.amazonaws.services.simpleemail.model._
import com.typesafe.scalalogging.Logger
import io.onema.mailer.MailerLogic.Email
import net.logstash.logback.argument.StructuredArguments._

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

object MailerLogic {
  case class Email(to: Seq[String], from: String, subject: String, body: String, replyTo: String = "", raw: Boolean = false) {
    lazy val request: SendEmailRequest = {
      val destination = new Destination().withToAddresses(to.asJava)
      val message = new Message().withBody(
        new Body().withHtml(
          new Content().withCharset("UTF-8").withData(body)
        ))
        .withSubject(
          new Content().withCharset("UTF-8").withData(subject)
        )
      val request = new SendEmailRequest()
        .withDestination(destination)
        .withMessage(message)
        .withSource(from)

      if(replyTo.nonEmpty) request.withReplyToAddresses(replyTo)
      else request
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

class MailerLogic(val sesClient: AmazonSimpleEmailService, val dynamodbClient: AmazonDynamoDBAsync, val tableName: String, val shouldLogEmail: Boolean) {

  //--- Fields ---
  protected val log = Logger("mailer-logic")
  private val dynamoDb = new DynamoDB(dynamodbClient)
  private val table = dynamoDb.getTable(tableName)

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
      log.debug("Sending plain email", keyValue("BODY", email.body))
      sesClient.sendEmail(email.request)
    }
    log.info("Email sent successfully")
  }

  def isNotBlocked(destinationAddress: String): Boolean = {
    Try(findEmail(destinationAddress)) match {
      case Success(response) =>
        log.info("Successfully query the sesMessage table")

        // If it does find any items in the table, the address is not blocked
        !response.iterator().hasNext
      case Failure(ex) =>
        log.error("Unable to read from table")
        throw ex
    }
  }

  private def findEmail(destinationAddress: String): ItemCollection[QueryOutcome]  = {
    val index = table.getIndex("EmailIndex")
    val query = new QuerySpec()
      .withKeyConditionExpression("DestinationAddress = :v_email")
      .withValueMap(new ValueMap().withString(":v_email", destinationAddress))
    index.query(query)
  }
}
