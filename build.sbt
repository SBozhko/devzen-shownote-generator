name := "devzen-shownote-generator"

version := "1.0"

scalaVersion := "2.12.17"


libraryDependencies ++= Seq(
  "joda-time" % "joda-time" % "2.9.7",
  "org.json4s" % "json4s-jackson_2.12" % "4.0.6",
  "org.apache.httpcomponents" % "httpclient" % "4.5.3",
  "org.apache.httpcomponents" % "fluent-hc" % "4.5.3",
  "com.typesafe.akka" %% "akka-http-core" % "10.0.1",
  "com.typesafe.akka" %% "akka-http" % "10.0.1",
  "org.apache.commons" % "commons-lang3" % "3.5"
)

enablePlugins(JavaServerAppPackaging)
