ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.12"

val AkkaVersion = "2.9.0"
val AkkaHttpVersion = "10.6.0"

run / fork := true

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "rounting-api",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
      "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
      "org.scalatestplus" %% "mockito-3-4" % "3.2.10.0" % Test,
      "org.scalatest" %% "scalatest" % "3.2.15" % Test
    ),
    resolvers += "Akka library repository".at("https://repo.akka.io/maven")
  )
