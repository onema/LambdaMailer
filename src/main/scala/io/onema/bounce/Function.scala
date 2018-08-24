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

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClientBuilder
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.SNSEvent
import io.onema.json.JavaExtensions._
import io.onema.serverlessbase.configuration.lambda.EnvLambdaConfiguration
import io.onema.serverlessbase.function.LambdaHandler

import scala.collection.JavaConverters._

class Function extends LambdaHandler[SNSEvent, Unit] with EnvLambdaConfiguration {

  //--- Fields ---
  val logic = new Logic(
    AmazonDynamoDBAsyncClientBuilder.defaultClient(),
    getValue("/table/name").getOrElse("LambdaMailerSESNotifications")
  )

  //--- Methods ---
  override def execute(event: SNSEvent, context: Context): Unit = {
    log.info(event.asJson)
    val snsRecord: SNSEvent.SNS = event.getRecords.asScala.head.getSNS
    logic.handleRequest(snsRecord)
  }
}
