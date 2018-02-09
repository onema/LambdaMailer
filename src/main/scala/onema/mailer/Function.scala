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

package onema.mailer

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClientBuilder
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.SNSEvent
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceAsyncClientBuilder
import onema.core.json.Implicits._
import onema.serverlessbase.function.SnsHandler

import scala.collection.JavaConverters._

class Function extends SnsHandler {

  //--- Fields ---
  val logic = new Logic(
    AmazonSimpleEmailServiceAsyncClientBuilder.defaultClient(),
    AmazonDynamoDBAsyncClientBuilder.defaultClient(),
    "SESNotifications",
    logEmail
  )

  //--- Methods ---
  def lambdaHandler(event: SNSEvent, context: Context): Unit = {
    log.info(event.javaClassToJson)
    val snsRecord: SNSEvent.SNS = event.getRecords.asScala.head.getSNS
    handle(() => logic.handleRequest(snsRecord))
  }

  private def logEmail = {
    val shouldLog = sys.env.getOrElse("LOG_EMAIL", "false")
    if(shouldLog.toLowerCase() == "true") true
    else false
  }
}
