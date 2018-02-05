resolvers += "Onema Snapshots" at "s3://s3-us-east-1.amazonaws.com/ones-deployment-bucket/snapshots"

lazy val root = (project in file("."))
.settings(
  organization := "onema",

  name := "lambda-sample-request",

  version := "0.1.0",

  scalaVersion := "2.12.4",

  libraryDependencies ++= {
    Seq(
      // CORE!
      "onema"                     % "serverless-base_2.12"      % "0.2.0",

      // AWS Clients
      "com.amazonaws"             % "aws-java-sdk-sqs"          % "1.11.228",
      "com.amazonaws"             % "aws-java-sdk-ses"          % "1.11.271",
      "com.amazonaws"             % "aws-java-sdk-dynamodb"     % "1.11.263",

      // Logging
      "com.typesafe.scala-logging"% "scala-logging_2.12"        % "3.7.2",
      "ch.qos.logback"            % "logback-classic"           % "1.1.7",

      // Testing
      "org.scalatest"             %% "scalatest"                % "3.0.4"       % "test",
      "org.mockito"               % "mockito-core"              % "2.12.0"      % "test"
    )
  }
)
//.dependsOn(serverlessBase)

// Sub-projects
//lazy val serverlessBase = RootProject(file("../ServerlessBase"))

// Assembly
assemblyJarName in assembly := "app.jar"
