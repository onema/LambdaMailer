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

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder
import com.amazonaws.services.dynamodbv2.document.DynamoDB
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.sns.AmazonSNSClientBuilder
import io.onema.forwarder.ForwarderLogic.SesEvent
import io.onema.json.Extensions._
import io.onema.userverless.configuration.lambda.EnvLambdaConfiguration
import io.onema.userverless.function.LambdaHandler

class ForwarderFunction extends LambdaHandler[SesEvent, Unit] with EnvLambdaConfiguration {

  //--- Fields ---
  private val logEmail = getValue("/log/email")
  private val shouldLog = logEmail.isDefined && logEmail.getOrElse("false").toBoolean
  private val tableName = getValue("mapping/table").getOrElse("mapping-table")
  private val dynamodbClient = AmazonDynamoDBClientBuilder.defaultClient()
  private val table = new DynamoDB(dynamodbClient).getTable(tableName)
  val logic = new ForwarderLogic(
    snsClient = AmazonSNSClientBuilder.defaultClient(),
    s3Client = AmazonS3ClientBuilder.defaultClient(),
    table = table,
    mailerTopic = getValue("sns/mailer/topic").get,
    bucketName = getValue("forwarder/s3/bucket").get,
    attachmentBucket = getValue("attachment/bucket").get,
    shouldLog
  )

  //--- Methods ---
  def execute(sesEvent: SesEvent, context: Context): Unit = {
    sesEvent.records.foreach(record => {
      val sesMessage = record.ses
      log.debug(sesMessage.asJson)
      logic.handleRequest(sesMessage)
    })
  }

  override def jsonDecode(json: String): SesEvent = {
    json.jsonDecode[SesEvent](ForwarderLogic.fieldRenames)
  }
}
