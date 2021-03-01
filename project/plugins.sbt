logLevel := Level.Warn
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.1.4")
addSbtPlugin("com.artima.supersafe" % "sbtplugin" % "1.1.10")

resolvers += "Artima Maven Repository" at "http://repo.artima.com/releases"