scalaVersion := "3.3.4"
scalacOptions ++= Seq("""-Wconf:cat=deprecation:s""")

val PekkoVersion = "1.1.2"
val PekkoHttpVersion = "1.1.0"

libraryDependencies += "org.apache.pekko" %% "pekko-actor-typed" % PekkoVersion
libraryDependencies += "org.apache.pekko" %% "pekko-connectors-kafka" % "1.1.0"
libraryDependencies += "org.apache.pekko" %% "pekko-stream" % PekkoVersion
libraryDependencies += "org.apache.pekko" %% "pekko-http" % PekkoHttpVersion
libraryDependencies += "org.apache.pekko" %% "pekko-http-spray-json" % PekkoHttpVersion
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.5.12" % Runtime
