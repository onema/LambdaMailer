/**
  * This file is part of the ONEMA lambda-mailer Package.
  * For the full copyright and license information,
  * please view the LICENSE file that was distributed
  * with this source code.
  *
  * copyright (c) 2018, Juan Manuel Torres (http://onema.io)
  *
  * @author Juan Manuel Torres <software@onema.io>
  */

package io.onema.forwarder

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.sns.AmazonSNSAsyncClientBuilder
import io.onema.forwarder.Logic.SesMessage
import io.onema.json.Extensions._
import io.onema.userverless.configuration.lambda.EnvLambdaConfiguration
import io.onema.userverless.function.SnsHandler

class ForwarderFunction extends SnsHandler[SesMessage] with EnvLambdaConfiguration {

  //--- Fields ---
  val logic = new Logic(snsClient = AmazonSNSAsyncClientBuilder.defaultClient(), mailerTopic = getValue("sns/mailer/topic").get)

  //--- Methods ---
  def execute(sesMessage: SesMessage, context: Context): Unit = {
    val accountId = context.getInvokedFunctionArn.split(':')(4)
    val region = getValue("aws/region").get

    // Get an email mapping like "foo@bar.com=baz@balh.com,baz1@balh.com&foo2@bar.com=blah@balh.com"
    // and parse it into a dictionary of [sender, recipients] where the recipients are a sequence of strings
    val emailMapping = getValue("email/mapping")
    log.debug(sesMessage.asJson)
    logic.handleRequest(sesMessage, emailMapping.getOrElse(""))
  }
}
