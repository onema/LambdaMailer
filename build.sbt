resolvers += "Onema Snapshots" at "s3://s3-us-east-1.amazonaws.com/ones-deployment-bucket/snapshots"

lazy val root = (project in file("."))
.settings(
  organization := "io.onema",

  name := "lambda-mailer",

  version := "0.3.0",

  scalaVersion := "2.12.6",

  libraryDependencies ++= {
    Seq(
      // Serverless Base!
      "io.onema"                  % "userverless_2.12"      % "0.0.1",

      // AWS Clients
      "com.amazonaws"             % "aws-java-sdk-ses"          % "1.11.271",
      "com.amazonaws"             % "aws-java-sdk-dynamodb"     % "1.11.263",

      // Logging
      "com.typesafe.scala-logging"% "scala-logging_2.12"        % "3.7.2",
      "ch.qos.logback"            % "logback-classic"           % "1.1.7",

      // Testing
      "org.scalatest"             %% "scalatest"                          % "3.0.4"   % "test",
      "org.scalamock"             % "scalamock-scalatest-support_2.12"    % "3.6.0"   % "test"
    )
  }
)
//.dependsOn(uServerless)

// Sub-projects
//lazy val uServerless = RootProject(file("../uServerless"))

// Assembly
assemblyJarName in assembly := "app.jar"
