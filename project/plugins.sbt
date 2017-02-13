addSbtPlugin("io.spray" % "sbt-revolver" % "0.8.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.1.0-RC1")

addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "4.0.0")

addSbtPlugin("se.marcuslonnberg" % "sbt-docker" % "1.4.0")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.8.2")

addSbtPlugin("org.clapper" % "sbt-editsource" % "0.7.0")

libraryDependencies += "io.swagger" % "swagger-codegen" % "2.2.1" from "file:///" + baseDirectory.value + "/lib"

//libraryDependencies ++= Seq("net.sf.proguard" % "proguard-base" % "5.0" % Proguard.name
//  from "file:///tmp/proguard5.0beta2/lib/proguard.jar")