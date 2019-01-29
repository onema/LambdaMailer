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
import com.amazonaws.services.dynamodbv2.document.DynamoDB
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceAsyncClientBuilder
import io.onema.json.JavaExtensions._
import io.onema.mailer.MailerLogic.Email
import io.onema.userverless.configuration.lambda.EnvLambdaConfiguration
import io.onema.userverless.function.SnsHandler
import io.onema.userverless.exception.ThrowableExtensions._
import io.onema.vff.FileSystem

import scala.util.{Failure, Success, Try}

class MailerFunction extends SnsHandler[Email] with EnvLambdaConfiguration {

  //--- Fields ---
  private val logEmail = getValue("/log/email")
  private val shouldLog = if(logEmail.isDefined && logEmail.getOrElse("").toLowerCase() == "true") true else false
  private val reportException = if(getValue("/report/exception").getOrElse("").toLowerCase() == "true") true else false
  private val tableName = getValue("/table/name").getOrElse(throw new Exception("Table name is a required value"))
  private val dynamodbClient = AmazonDynamoDBAsyncClientBuilder.defaultClient()
  private val dynamoDb = new DynamoDB(dynamodbClient)
  private val table = dynamoDb.getTable(tableName)
  private val local = FileSystem()
  val logic = new MailerLogic(
    AmazonSimpleEmailServiceAsyncClientBuilder.defaultClient(),
    table,
    AmazonS3ClientBuilder.defaultClient(),
    local,
    getValue("/attachment/bucket").getOrElse(throw new Exception("Attachment bucket is a required value")),
    shouldLog,
  )

  //--- Methods ---
  def execute(email: Email, context: Context): Unit = {
    log.info(email.asJson)
    Try(logic.handleRequest(email)) match {
      case Failure(ex) => log.error(ex.structuredMessage(reportException = reportException))
      case Success(_) =>
    }
  }
}
