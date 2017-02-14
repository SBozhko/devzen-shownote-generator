name := "devzen-shownote-generator"

version := "1.0"

scalaVersion := "2.12.1"


libraryDependencies ++= Seq(

  "joda-time" % "joda-time" % "2.9.7",
  "org.json4s" % "json4s-jackson_2.12" % "3.5.0",
  "org.json4s" % "json4s-ext_2.12" % "3.5.0",
  "com.typesafe.scala-logging" % "scala-logging_2.12" % "3.5.0",
  "com.typesafe.akka" % "akka-slf4j_2.12" % "2.4.16",
  "ch.qos.logback" % "logback-classic" % "1.1.9",
  "org.apache.httpcomponents" % "httpclient" % "4.5.3",
  "org.apache.httpcomponents" % "fluent-hc" % "4.5.3"
)
resolvers ++= Seq()

enablePlugins(JavaServerAppPackaging)