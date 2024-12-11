import sbt._
import Keys._

organization := "net.successk"

name := "k-akka-openid"

description := "Openid implementation for Akka HTTP"

version := "0.3-SNAPSHOT"

scalaVersion := "2.12.10"
crossScalaVersions := Seq("2.11.11", "2.12.10")

retrieveManaged := true

val   akkaV     = "2.6.1"
val   akkaHttpV = "10.1.11"

libraryDependencies ++= {
  Seq(
    "com.typesafe.akka" %% "akka-actor"              % akkaV,
    "com.typesafe.akka" %% "akka-stream"             % akkaV,
    "com.typesafe.akka" %% "akka-http"               % akkaHttpV,
    "com.typesafe.akka" %% "akka-http-spray-json"    % akkaHttpV,
    "org.scala-lang.modules" %% "scala-xml" % "1.2.0",
    "com.nimbusds" % "nimbus-jose-jwt" % "8.3",

    "org.scalatest" %% "scalatest" % "3.0.5" % "test",
    "com.typesafe.akka" %% "akka-testkit"            % akkaV % "test",
    "com.typesafe.akka" %% "akka-http-testkit"       % akkaHttpV % "test"
  )
}

publishTo := {
  val nexus = "http://scalabuild.schoox.com:8081/"
  if (isSnapshot.value)
    Some(("snapshots" at nexus + "repository/snapshots").withAllowInsecureProtocol(true))
  else
    Some(("releases"  at nexus + "service/local/staging/deploy/maven2").withAllowInsecureProtocol(true))
}

organization := "com.schoox"

publishMavenStyle := true

publishArtifact in Test := false

pomIncludeRepository := { x => false }

