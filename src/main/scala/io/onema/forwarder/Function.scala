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

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.SNSEvent
import com.amazonaws.services.sns.{AmazonSNSAsync, AmazonSNSAsyncClientBuilder}
import io.onema.forwarder.Logic.SesMessage
import io.onema.json.Extensions._
import io.onema.serverlessbase.configuration.lambda.EnvLambdaConfiguration
import io.onema.serverlessbase.function.LambdaHandler

import scala.collection.JavaConverters._

class Function extends LambdaHandler[Unit] with EnvLambdaConfiguration {

  //--- Fields ---
  override protected val snsClient: AmazonSNSAsync = AmazonSNSAsyncClientBuilder.defaultClient()

  val logic = new Logic(snsClient = AmazonSNSAsyncClientBuilder.defaultClient(), mailerTopic = getValue("sns/mailer/topic").get)

  //--- Methods ---
  def lambdaHandler(event: SNSEvent, context: Context): Unit = {
    log.info(event.asJson)
    val accountId = context.getInvokedFunctionArn.split(':')(4)
    val region = getValue("aws/region").get

    // Get an email mapping like "foo@bar.com=baz@balh.com,baz1@balh.com&foo2@bar.com=blah@balh.com"
    // and parse it into a dictionary of [sender, recipients] where the recipients are a sequence of strings
    val emailMapping = getValue("email/mapping")
    val snsRecord: SNSEvent.SNS = event.getRecords.asScala.head.getSNS

    log.debug(snsRecord.getMessage)

    val sesMessage: SesMessage = snsRecord.getMessage.jsonDecode[SesMessage]
    handle {
      logic.handleRequest(sesMessage, emailMapping.getOrElse(""))
    }
  }
}
