name := "ws-out"

version := "1.0"

scalaVersion := "2.11.8"

scalacOptions ++= Seq(
  "-deprecation",
  "-encoding", "UTF-8",
  "-feature",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:postfixOps",
  "-unchecked",
  "-Xfatal-warnings",
  "-Xlint",
  "-Yno-adapted-args",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
  "-Ywarn-value-discard",
  "-Xfuture"
)

libraryDependencies ++= {
  val akkaV            = "2.4.6"
  val akkaGroupId      = "com.typesafe.akka"
  Seq(
    akkaGroupId              %% "akka-stream"                           % akkaV,
    akkaGroupId              %% "akka-http-core"                        % akkaV,
    akkaGroupId              %% "akka-http-experimental"                % akkaV
  )
}