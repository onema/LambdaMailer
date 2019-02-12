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

import com.gu.scanamo.{LocalDynamoDB, Scanamo, Table}
import com.gu.scanamo.syntax._
import io.onema.bounce.BounceLogic
import io.onema.bounce.BounceLogic._
import io.onema.json.Extensions._
import org.scalamock.scalatest.MockFactory
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

import scala.util.Try

class BounceLogicTest extends FlatSpec with Matchers with MockFactory with BeforeAndAfter {
  private val  dynamoDbClient = LocalDynamoDB.client()
  private val tableName = "foo"
  after {
    LocalDynamoDB.deleteTable(dynamoDbClient)(tableName)
  }

  "A bounced event " should "be recorded " in {
    // Arrange
    val bouncedEmailAddress = "bounced@email.com"
    val logic = new BounceLogic(dynamoDbClient, tableName)
    val bounce = SesNotification("Bounce", Bounce(bouncedRecipients = List(BouncedRecipients(bouncedEmailAddress))))
    buildDynamoDbTable(bouncedEmailAddress)

    // Act
    logic.handleRequest("publishTime", "messageId", bounce)

    // Assert
    containsEmail(bouncedEmailAddress) should be (true)
  }

  "A complaint event " should "be recorded " in {
    // Arrange
    val complainedAddress = "complaint@email.com"
    val logic = new BounceLogic(dynamoDbClient, tableName)
    val complaint = SesNotification("Complaint", Bounce(), Complaint(complainedRecipients = List(ComplainedRecipients(complainedAddress))))
    buildDynamoDbTable(complainedAddress)

    // Act
    logic.handleRequest("publishTime", "messageId", complaint)

    // Assert
    containsEmail(complainedAddress) should be (true)
  }

  def buildDynamoDbTable(destinationAddress: String, bounceData: Option[BounceData] = None, complaintData: Option[ComplaintData] = None): Unit = {
    import java.util.UUID.randomUUID
    import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
    Try(LocalDynamoDB.createTableWithIndex(dynamoDbClient, tableName, "EmailIndex", List('messageId -> S, 'snsPublishTime -> S), List('destinationAddress -> S)))
    val table = Table[BounceComplaintItem](tableName)
    val id = randomUUID().toString
    val snsPublishTime = randomUUID().toString
    val bounce = bounceData.map(_.asJson)
    val complaint = complaintData.map(_.asJson)
    if(bounce.isDefined || complaint.isDefined) {
      Scanamo.exec(dynamoDbClient)(table.put(BounceComplaintItem(id, destinationAddress, snsPublishTime, None, bounce, complaint)))
    }
  }

  def containsEmail(email: String): Boolean = {
    val table = Table[BounceComplaintItem](tableName)
    val emailIndex = table.index("EmailIndex")
    val operations = emailIndex.query('destinationAddress -> email)
    val response = Scanamo.exec(dynamoDbClient)(operations)
    response.exists(_.isRight)
  }
}
