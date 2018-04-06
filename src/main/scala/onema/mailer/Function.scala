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
import com.amazonaws.services.sns.{AmazonSNSAsync, AmazonSNSAsyncClientBuilder}
import onema.core.json.Implicits._
import onema.serverlessbase.configuration.lambda.EnvLambdaConfiguration
import onema.serverlessbase.function.LambdaHandler

import scala.collection.JavaConverters._

class Function extends LambdaHandler with EnvLambdaConfiguration {

  //--- Fields ---
  override protected val snsClient: AmazonSNSAsync = AmazonSNSAsyncClientBuilder.defaultClient()

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
    handle(logic.handleRequest(snsRecord))
  }

  private def logEmail = {
    val shouldLog = getValue("/log/email")
    if(shouldLog.isDefined && shouldLog.get.toLowerCase() == "true") true else false
  }
}
