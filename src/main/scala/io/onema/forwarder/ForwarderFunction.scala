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
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import software.amazon.awssdk.services.sns.SnsAsyncClient
import io.onema.forwarder.ForwarderLogic.SesEvent
import io.onema.json.Extensions._
import io.onema.userverless.configuration.lambda.EnvLambdaConfiguration
import io.onema.userverless.function.Extensions._
import io.onema.userverless.function.LambdaHandler

class ForwarderFunction extends LambdaHandler[SesEvent, Unit] with EnvLambdaConfiguration {

  //--- Fields ---
  private val logEmail = getValue("/log/email")
  private val shouldLog = if(logEmail.isDefined && logEmail.getOrElse("").toLowerCase() == "true") true else false
  val logic = new ForwarderLogic(
    snsClient = SnsAsyncClient.create(),
    mailerTopic = getValue("sns/mailer/topic").get,
    s3Client = AmazonS3ClientBuilder.defaultClient(),
    bucketName = getValue("forwarder/s3/bucket").get,
    shouldLog
  )

  //--- Methods ---
  def execute(sesEvent: SesEvent, context: Context): Unit = {
    val accountId = context.accountId
    val region = getValue("aws/region").get

    // Get an email mapping like "foo@bar.com=baz@balh.com,baz1@balh.com&foo2@bar.com=blah@balh.com"
    // and parse it into a dictionary of [sender, recipients] where the recipients are a sequence of strings
    val emailMapping = getValue("email/mapping")
    sesEvent.records.foreach(r => {
      val sesMessage = r.ses
      log.debug(sesMessage.asJson)
      logic.handleRequest(sesMessage, emailMapping.getOrElse(""))
    })
  }

  override def jsonDecode(json: String): SesEvent = {
    json.jsonDecode[SesEvent](ForwarderLogic.fieldRenames)
  }
}
