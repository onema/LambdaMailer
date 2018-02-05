/**
  * This file is part of the ONEMA lambda-sample-request Package.
  * For the full copyright and license information,
  * please view the LICENSE file that was distributed
  * with this source code.
  *
  * copyright (c) 2018, Juan Manuel Torres (http://onema.io)
  *
  * @author Juan Manuel Torres <kinojman@gmail.com>
  */

package onema.mailer

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap
import com.amazonaws.services.dynamodbv2.document.{DynamoDB, ItemCollection, QueryOutcome}
import com.amazonaws.services.lambda.runtime.events.SNSEvent.SNS
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService
import com.amazonaws.services.simpleemail.model._
import com.typesafe.scalalogging.Logger
import onema.core.json.Implicits._
import onema.mailer.Logic.Email

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

object Logic {
  case class Email(to: List[String], from: String, subject: String, body: String, replyTo: String = "") {
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
  }
}

class Logic(val sesClient: AmazonSimpleEmailService, val dynamodbClient: AmazonDynamoDBAsync, val tableName: String) {

  //--- Fields ---
  protected val log = Logger("mailer-logic")
  private val dynamoDb = new DynamoDB(dynamodbClient)
  private val table = dynamoDb.getTable(tableName)

  //--- Methods ---
  def handleRequest(snsRecord: SNS): Unit = {
    val email = snsRecord.getMessage.jsonParse[Email]
    val filteredEmails = email.copy(to = email.to.filter(isNotBlocked))

    if(filteredEmails.to.nonEmpty) {
      log.info(s"Sending email to ${filteredEmails.to}")
      sendEmail(filteredEmails)
    } else log.info(s"Email ${email.to.head} is blocked")
  }

  def sendEmail(email: Email): Unit = {
    sesClient.sendEmail(email.request)
    log.info("Email sent successfully")
  }

  def isNotBlocked(destinationAddress: String): Boolean = {
    Try(findEmail(destinationAddress)) match {
      case Success(response) =>
        log.info("Successfully query the event table")

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
