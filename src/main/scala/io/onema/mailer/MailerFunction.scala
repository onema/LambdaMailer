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

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClientBuilder
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceAsyncClientBuilder
import io.onema.json.JavaExtensions._
import io.onema.mailer.MailerLogic.Email
import io.onema.userverless.configuration.lambda.EnvLambdaConfiguration
import io.onema.userverless.function.SnsHandler

class MailerFunction extends SnsHandler[Email] with EnvLambdaConfiguration {

  //--- Fields ---
  private val logEmail = getValue("/log/email")
  private val shouldLog = if(logEmail.isDefined && logEmail.getOrElse("").toLowerCase() == "true") true else false
  val logic = new MailerLogic(
    AmazonSimpleEmailServiceAsyncClientBuilder.defaultClient(),
    AmazonDynamoDBAsyncClientBuilder.defaultClient(),
    getValue("/table/name").getOrElse("LambdaMailerSESNotifications"),
    shouldLog
  )

  //--- Methods ---
  def execute(email: Email, context: Context): Unit = {
    log.info(email.asJson)
    logic.handleRequest(email)
  }
}
