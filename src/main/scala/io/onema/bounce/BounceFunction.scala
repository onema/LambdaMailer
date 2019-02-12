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
import io.onema.bounce.BounceLogic.SesNotification
import io.onema.json.Extensions._
import io.onema.userverless.configuration.lambda.EnvLambdaConfiguration
import io.onema.userverless.function.LambdaHandler

import scala.collection.JavaConverters._

class BounceFunction extends LambdaHandler[SNSEvent, Unit] with EnvLambdaConfiguration {

  //--- Fields ---
  val logic = new BounceLogic(
    AmazonDynamoDBAsyncClientBuilder.defaultClient(),
    getValue("/table/name").getOrElse("LambdaMailerSESNotifications")
  )

  //--- Methods ---
  override def execute(event: SNSEvent, context: Context): Unit = {
    val snsRecord: SNSEvent.SNS = event.getRecords.asScala.head.getSNS
    val snsPublishTime = snsRecord.getTimestamp.toString("yyyy-MM-dd'T'HH:mm:ss'Z'")
    val sesMessage = snsRecord.getMessage.jsonDecode[SesNotification]
    val sesMessageId = sesMessage.mail.messageId
    logic.handleRequest(snsPublishTime, sesMessageId, sesMessage)
  }
}
