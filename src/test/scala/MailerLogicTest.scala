/**
  * This file is part of the ONEMA lambda-mailer Package.
  * For the full copyright and license information,
  * please view the LICENSE file that was distributed
  * with this source code.
  *
  * copyright (c) 2019, Juan Manuel Torres (http://onema.io)
  *
  * @author Juan Manuel Torres <software@onema.io>
  */

import java.io.ByteArrayInputStream

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.S3Object
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService
import com.gu.scanamo.{LocalDynamoDB, Scanamo, Table}
import io.onema.bounce.BounceLogic.BounceComplaintItem
import io.onema.json.Extensions._
import io.onema.mailer.MailerLogic
import io.onema.mailer.MailerLogic.Email
import io.onema.vff.FileSystem
import javax.mail.internet.AddressException
import org.scalamock.scalatest.MockFactory
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

import scala.util.Try

class MailerLogicTest extends FlatSpec with Matchers with MockFactory with BeforeAndAfter {
  private val  dynamoDbClient = LocalDynamoDB.client()
  private val tableName = "foo"
  after {
    LocalDynamoDB.deleteTable(dynamoDbClient)(tableName)
  }

  "A blocked email " should "not be sent" in {
    // Arrange
    val blockedAddress = "blocked@email.com"
    val message = s"""{"to":["$blockedAddress"],"from":"some@email.com","subject":"test01","body":"<h1>Test Body</h1>","raw":true}"""
    val sesMock = mock[AmazonSimpleEmailService]
    (sesMock.sendRawEmail _).expects(*).never()
    val s3Mock = mock[AmazonS3]
    val fsMock = mock[FileSystem]
    val logic = new MailerLogic(sesMock, s3Mock, dynamoDbClient, fsMock, tableName, "bucket", false)
    buildDynamoDbTable(tableName, blockedAddress)
    val email = message.jsonDecode[Email]

    // Act - Assert
    logic.handleRequest(email)
  }

  "A list of emails with some blocked emails " should "only send raw email to non-blocked addresses" in {
    // Arrange
    val blockedAddress = "blocked@email.com"
    val nonBlockedAddress = "some@email.com"
    val message = s"""{"to":["$blockedAddress", "$nonBlockedAddress"],"from":"some@email.com","subject":"test01","body":"<h1>Test Body</h1>","raw":true}"""
    val expected = Email(Seq(nonBlockedAddress), "some@email.com", "test01", "<h1>Test Body</h1>", raw = true)
    val sesMock = mock[AmazonSimpleEmailService]
    (sesMock.sendRawEmail _).expects(expected.rawRequest).once()
    val s3Mock = mock[AmazonS3]
    val fsMock = mock[FileSystem]
    val logic = new MailerLogic(sesMock, s3Mock, dynamoDbClient, fsMock, tableName, "bucket", false)
    buildDynamoDbTable(tableName, blockedAddress)
    val email = message.jsonDecode[Email]

    // Act - Assert
    logic.handleRequest(email)
  }

  "A mail with attachments" should "properly compose the raw message to be send " in {
    // Arrange
    val email: Email = "{\"to\":[\"test@email.com\"],\"from\":\"barbaz@mailer.opsdeploy.com\",\"subject\":\"Test with attachment 1\",\"body\":null,\"raw\":false,\"replyTo\":\"Juan Torres <kinojman@gmail.com>\",\"attachments\":[\"/pp03q/IMG_9779.JPG\"]}".jsonDecode[Email]
    val sesMock = mock[AmazonSimpleEmailService]
    (sesMock.sendRawEmail _).expects(*).once()
    val s3Mock = mock[AmazonS3]
    val s3Object = new S3Object()
    s3Object.setObjectContent(new ByteArrayInputStream("test attachment".getBytes()))
    (s3Mock.getObject(_: String, _: String)).expects("bucket", "pp03q/IMG_9779.JPG").once().returning(s3Object)
    val fs = FileSystem()
    buildDynamoDbTable(tableName, "foo@bar.com")
    val logic = new MailerLogic(sesMock, s3Mock, dynamoDbClient, fs, tableName, "bucket", false)

    // Act - Assert
    logic.handleRequest(email)
  }

  "A mail with empty reply to" should "ignore value and handle exception correctly" in {
    // Arrange
    val email: Email = "{\"to\":[\"foo@ymail.com\"],\"from\":\"barbaz@mailer.opsdeploy.com\",\"subject\":\"Test with attachment 1\",\"body\":null,\"raw\":false,\"replyTo\":\"\"}".jsonDecode[Email]
    val sesMock = mock[AmazonSimpleEmailService]
    val s3Mock = mock[AmazonS3]
    val fsMock = mock[FileSystem]
    buildDynamoDbTable(tableName, "foo@bar.com")
    val logic = new MailerLogic(sesMock, s3Mock, dynamoDbClient, fsMock, tableName, "bucket", false)

    // Act - Assert
    intercept[AddressException] {
      logic.handleRequest(email)
    }

  }

//  "A list of emails with some blocked emails " should "only send regular email to non-blocked addresses" in {
//    // Arrange
//    val blockedAddress = "blocked@email.com"
//    val nonBlockedAddress = "some@email.com"
//    val message = s"""{"to":["$blockedAddress", "$nonBlockedAddress"],"from":"foo@email.com","subject":"test01","body":"<h1>Test Body</h1>","raw":false}"""
//    val expected = Email(Seq(nonBlockedAddress), "foo@email.com", "test01", "<h1>Test Body</h1>", false)
//    val sesMock = mock[AmazonSimpleEmailService]
//    (sesMock.sendRawEmail _).expects(expected.request(Seq())).once()
//    val s3Mock = mock[AmazonS3]
//    val fsMock = mock[FileSystem]
//    val logic = new MailerLogic(sesMock, s3Mock, dynamoDbClient, fsMock, tableName, "bucket", false)
//    buildDynamoDbTable(tableName, blockedAddress)
//    val email = message.jsonDecode[Email]
//
//    // Act - Assert
//    logic.handleRequest(email)
//  }

  def buildDynamoDbTable(name: String, destinationAddress: String): Unit = {
    import java.util.UUID.randomUUID

    import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
    Try(LocalDynamoDB.createTableWithIndex(dynamoDbClient, name, "EmailIndex", List('messageId -> S, 'snsPublishTime -> S), List('destinationAddress -> S)))
    val table = Table[BounceComplaintItem](name)
    val id = randomUUID().toString
    val snsPublishTime = randomUUID().toString
    Scanamo.exec(dynamoDbClient)(table.put(BounceComplaintItem(id, destinationAddress, snsPublishTime, None, None, None)))
  }
}
