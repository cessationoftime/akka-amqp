import sbt._

object dependencies {
  val akkaVersion = "2.3.9"
  def Scalatest    = "org.scalatest" %% "scalatest" % "2.2.1" % "test"   	  // ApacheV2
 // def scalaActorsForScalaTest = "org.scala-lang" % "scala-actors" % "2.10.1" % "test"
  def AmqpClient = "com.rabbitmq" % "amqp-client" % "3.1.4"   													  // ApacheV2

  def AkkaAgent = "com.typesafe.akka" %% "akka-agent" % akkaVersion

  def Specs2      = "org.specs2" %% "specs2" % "2.4.9" % "test"  // MIT
  def JUnit = "junit" % "junit" % "4.7" % "test"   																 // Common Public License 1.0
  def AkkaTestKit = "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test"
  //def ActorTests = "com.typesafe.akka" %% "akka-actor-tests" % "2.1-M2" % "test"
  def Mockito = "org.mockito" % "mockito-all" % "1.10.0" % "test"     											// MIT
}
