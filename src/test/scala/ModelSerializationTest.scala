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

import io.onema.bounce.Logic.SesNotification
import io.onema.json.Extensions._
import org.scalatest.{FlatSpec, Matchers}


class ModelSerializationTest extends FlatSpec with Matchers {
  

  "An Exception" should "generate a valid response" in {

    // Arrange
    val message = "{\"notificationType\":\"Bounce\",\"bounce\":{\"bounceType\":\"Permanent\",\"bounceSubType\":\"General\",\"bouncedRecipients\":[{\"emailAddress\":\"bounce@simulator.amazonses.com\",\"action\":\"failed\",\"status\":\"5.1.1\",\"diagnosticCode\":\"smtp; 550 5.1.1 user unknown\"}],\"timestamp\":\"2018-02-03T20:28:10.277Z\",\"feedbackId\":\"010001615d5ad877-379fba80-7996-4fba-bc38-4481b747f3ca-000000\",\"remoteMtaIp\":\"205.251.242.49\",\"reportingMTA\":\"dsn; a8-62.smtp-out.amazonses.com\"},\"mail\":{\"timestamp\":\"2018-02-03T20:28:09.000Z\",\"source\":\"test@foobar.com\",\"sourceArn\":\"arn:aws:ses:us-east-1:065150860170:identity/foobar.com\",\"sourceIp\":\"72.21.217.83\",\"sendingAccountId\":\"065150860170\",\"messageId\":\"010001615d5ad5e1-07b7cacd-6d5d-4aad-a748-f1d919c61785-000000\",\"destination\":[\"bounce@simulator.amazonses.com\"],\"headersTruncated\":false,\"headers\":[{\"name\":\"From\",\"value\":\"test@foobar.com\"},{\"name\":\"To\",\"value\":\"bounce@simulator.amazonses.com\"},{\"name\":\"Subject\",\"value\":\"test\"},{\"name\":\"MIME-Version\",\"value\":\"1.0\"},{\"name\":\"Content-Type\",\"value\":\"text/plain; charset=UTF-8\"},{\"name\":\"Content-Transfer-Encoding\",\"value\":\"7bit\"}],\"commonHeaders\":{\"from\":[\"test@foobar.com\"],\"to\":[\"bounce@simulator.amazonses.com\"],\"subject\":\"test\"}}}"

    // Act
    val sesMessage = message.jsonDecode[SesNotification]

    // Assert
    sesMessage.bounce.bounceType should be ("Permanent")
    sesMessage.mail.source should be ("test@foobar.com")
  }

}
