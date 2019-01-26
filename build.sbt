//resolvers += "Onema Snapshots" at "s3://s3-us-east-1.amazonaws.com/ones-deployment-bucket/snapshots"

lazy val root = (project in file("."))
.settings(
  organization := "io.onema",

  name := "lambda-mailer",

  version := "0.7.0",

  scalaVersion := "2.12.7",

  libraryDependencies ++= {
    val awsSdkVersion = "1.11.451"
    Seq(
      // dependencies
      "io.onema"                  % "userverless-core_2.12"     % "0.2.2",
      "io.onema"                  % "vff_2.12"                  % "0.5.2",
      "org.apache.commons"        % "commons-email"             % "1.5",

        // AWS Clients
      "com.amazonaws"             % "aws-java-sdk-ses"          % awsSdkVersion,
      "com.amazonaws"             % "aws-java-sdk-dynamodb"     % awsSdkVersion,
      "com.amazonaws"             % "aws-java-sdk-s3"           % awsSdkVersion,
      "com.amazonaws"             % "aws-java-sdk-sns"          % awsSdkVersion,

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
