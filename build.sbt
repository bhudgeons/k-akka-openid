organization := "net.successk"

name := "k-akka-openid"

description := "Openid implementation for Akka HTTP"

version := "0.1-SNAPSHOT"

scalaVersion := "2.11.8"

scalacOptions := Seq("-encoding", "utf8")

val   akkaV     = "2.5.3"
val   akkaHttpV = "10.0.9"

libraryDependencies ++= {
  Seq(
    "com.typesafe.akka" %% "akka-http"               % akkaHttpV,
    "com.typesafe.akka" %% "akka-http-spray-json"    % akkaHttpV,
    "org.scala-lang.modules" %% "scala-xml" % "1.0.5",
    "com.nimbusds" % "nimbus-jose-jwt" % "4.11",

    "org.scalatest" %% "scalatest" % "3.0.1" % "test",
    "com.typesafe.akka" %% "akka-testkit"            % akkaV % "test",
    "com.typesafe.akka" %% "akka-http-testkit"       % akkaHttpV % "test"
  )
}

publishTo <<= version { v: String => 
  val nexus = "http://scalabuild.schoox.com:8081/nexus/"
  if (v.trim.endsWith("SNAPSHOT"))
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

organization := "com.schoox"

publishMavenStyle := true

publishArtifact in Test := false

pomIncludeRepository := { x => false }
