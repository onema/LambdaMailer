//import com.amazonaws.services.dynamodbv2.{AmazonDynamoDBAsync, AmazonDynamoDBAsyncClientBuilder}
//import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
//import com.amazonaws.services.simpleemail.{AmazonSimpleEmailService, AmazonSimpleEmailServiceClientBuilder}
//import io.onema.mailer.MailerLogic.Email
//import io.onema.json.Extensions._
//import io.onema.mailer.MailerLogic
//import org.scalamock.scalatest.MockFactory
//import org.scalatest.{FlatSpec, Matchers}
//
///**
//  * This file is part of the ONEMA lambda-mailer Package.
//  * For the full copyright and license information,
//  * please view the LICENSE file that was distributed
//  * with this source code.
//  *
//  * copyright (c) 2019, Juan Manuel Torres (http://onema.io)
//  *
//  * @author Juan Manuel Torres <software@onema.io>
//  */
//
//class MailerLogicTest extends FlatSpec with Matchers with MockFactory {
//  "A mail with attachments" should "properly compose the raw message to be send " in {
//    // Arrange
//    val email: Email = "{\"to\":[\"onema@ymail.com\"],\"from\":\"barbaz@mailer.opsdeploy.com\",\"subject\":\"Test with attachment 1\",\"body\":null,\"raw\":false,\"replyTo\":\"Juan Torres <kinojman@gmail.com>\",\"attachments\":[\"/pp03qn6t3ro3c4nkfsoq9pvku4849f9uteg34301/IMG_9779.JPG\"]}".jsonDecode[Email]
//    val sesMock = mock[AmazonSimpleEmailService]
//    val dynamoMock = AmazonDynamoDBAsyncClientBuilder.defaultClient()
//    val s3Mock = AmazonS3ClientBuilder.defaultClient()
//    val logic = new MailerLogic(sesMock, dynamoMock, s3Mock, "lambda-mailer-test-LambdaMailerTable-546XVS89ZIJC", "lambda-mailer-test-mailerattachments-13imtk11twvod", true, false)
//
//    // Act
//    logic.handleRequest(email)
//
//    // Assert
//  }
//
//  "A mail with empty reply to" should "ignore value and handle exception correctly" in {
//    // Arrange
//    val email: Email = "{\"to\":[\"onema@ymail.com\"],\"from\":\"barbaz@mailer.opsdeploy.com\",\"subject\":\"Test with attachment 1\",\"body\":null,\"raw\":false,\"replyTo\":\"\"}".jsonDecode[Email]
//    val sesMock = mock[AmazonSimpleEmailService]
//    val dynamoMock = AmazonDynamoDBAsyncClientBuilder.defaultClient()
//    val s3Mock = AmazonS3ClientBuilder.defaultClient()
//    val logic = new MailerLogic(sesMock, dynamoMock, s3Mock, "lambda-mailer-test-LambdaMailerTable-546XVS89ZIJC", "lambda-mailer-test-mailerattachments-13imtk11twvod", true, false)
//
//    // Act
//    logic.handleRequest(email)
//
//    // Assert
//  }
//
//}
