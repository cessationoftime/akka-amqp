/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import language.{ postfixOps, reflectiveCalls }
import scala.concurrent.Future
import org.scalatest.{ WordSpecLike, BeforeAndAfterAll, Tag }
import org.scalatest.matchers.MustMatchers
import _root_.akka.actor.{ Actor, ActorRef, Props, ActorSystem, PoisonPill, DeadLetter }
import _root_.akka.event.{ Logging, LoggingAdapter }
import scala.concurrent.duration._
import scala.concurrent.Await
import com.typesafe.config.{ Config, ConfigFactory }
import java.util.concurrent.TimeoutException
import _root_.akka.dispatch.{ MessageDispatcher, Dispatchers }
import _root_.akka.pattern.ask
import _root_.akka.actor.ActorSystemImpl
import org.scalatest.Matchers

object TimingTestForAmqp extends Tag("timing")
object LongRunningTestForAmqp extends Tag("long-running")

object AkkaSpecForAmqp {
  val testConf: Config = ConfigFactory.parseString("""
      akka {
        event-handlers = ["akka.testkit.TestEventListener"]
        loglevel = "WARNING"
        stdout-loglevel = "WARNING"
        actor {
          default-dispatcher {
            executor = "fork-join-executor"
            fork-join-executor {
              parallelism-min = 8
              parallelism-factor = 2.0
              parallelism-max = 8
            }
          }
        }
      }
      """)

  def mapToConfig(map: Map[String, Any]): Config = {
    import scala.collection.JavaConverters._
    ConfigFactory.parseMap(map.asJava)
  }

  def getCallerName(clazz: Class[_]): String = {
    val s = Thread.currentThread.getStackTrace map (_.getClassName) drop 1 dropWhile (_ matches ".*AkkaSpecForAmqp.?$")
    val reduced = s.lastIndexWhere(_ == clazz.getName) match {
      case -1 ⇒ s
      case z  ⇒ s drop (z + 1)
    }
    reduced.head.replaceFirst(""".*\.""", "").replaceAll("[^a-zA-Z_0-9]", "_")
  }

}

abstract class AkkaSpecForAmqp(_system: ActorSystem)
  extends TestKit(_system) with WordSpecLike with MustMatchers with BeforeAndAfterAll {
  val log: LoggingAdapter = Logging(system, this.getClass)

  def this(config: Config) = this(ActorSystem(AkkaSpecForAmqp.getCallerName(getClass).filterNot(_ == '_'),
    ConfigFactory.load(config.withFallback(AkkaSpecForAmqp.testConf))))

  def this(s: String) = this(ConfigFactory.parseString(s))

  def this(configMap: Map[String, _]) = this(AkkaSpecForAmqp.mapToConfig(configMap))

  def this() = this(ActorSystem(AkkaSpecForAmqp.getCallerName(getClass).filterNot(_ == '_'), AkkaSpecForAmqp.testConf))

  final override def beforeAll {
    atStartup()
  }

  final override def afterAll {
    beforeShutdown()
    system.shutdown()
    try system.awaitTermination(5 seconds) catch {
      case _: TimeoutException ⇒
        system.log.warning("Failed to stop [{}] within 5 seconds", system.name)
        println(system.asInstanceOf[ActorSystemImpl].printTree)
    }
    atTermination()
  }

  protected def atStartup() {}

  protected def beforeShutdown() {}

  protected def atTermination() {}

  def spawn(dispatcherId: String = Dispatchers.DefaultDispatcherId)(body: ⇒ Unit): Unit =
    Future(body)(system.dispatchers.lookup(dispatcherId))
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class AkkaSpecForAmqpSpec extends WordSpecLike with Matchers {

  "An AkkaSpecForAmqp" should {
    /* TODO: Make this spec work */
    //    "warn about unhandled messages" in {
    //      implicit val system = ActorSystem("AkkaSpec0", AkkaSpecForAmqp.testConf)
    //      try {
    //        val blankActor = system.actorOf(Props.empty)
    //        EventFilter[UnsupportedOperationException](occurrences = 1) intercept {
    //          blankActor ! 42
    //        }
    //      } finally {
    //        system.shutdown()
    //      }
    //    }

    "terminate all actors" in {
      // verbose config just for demonstration purposes, please leave in in case of debugging
      import scala.collection.JavaConverters._
      val conf = Map(
        "akka.actor.debug.lifecycle" -> true, "akka.actor.debug.event-stream" -> true,
        "akka.loglevel" -> "DEBUG", "akka.stdout-loglevel" -> "DEBUG")
      val system = ActorSystem("AkkaSpecForAmqp1", ConfigFactory.parseMap(conf.asJava).withFallback(AkkaSpecForAmqp.testConf))
      val spec = new AkkaSpecForAmqp(system) { val ref = Seq(testActor, system.actorOf(Props.empty, "name")) }
      spec.ref foreach (_.isTerminated should not be true)
      system.shutdown()
      spec.awaitCond(spec.ref forall (_.isTerminated), 2 seconds)
    }

    "stop correctly when sending PoisonPill to rootGuardian" in {
      val system = ActorSystem("AkkaSpecForAmqp2", AkkaSpecForAmqp.testConf)
      val spec = new AkkaSpecForAmqp(system) {}
      val latch = new TestLatch(1)(system)
      system.registerOnTermination(latch.countDown())

      system.actorFor("/") ! PoisonPill

      Await.ready(latch, 2 seconds)
    }

    "enqueue unread messages from testActor to deadLetters" in {
      val system, otherSystem = ActorSystem("AkkaSpecForAmqp3", AkkaSpecForAmqp.testConf)

      try {
        var locker = Seq.empty[DeadLetter]
        implicit val timeout = TestKitExtension(system).DefaultTimeout
        implicit val davyJones = otherSystem.actorOf(Props(new Actor {
          def receive = {
            case m: DeadLetter ⇒ locker :+= m
            case "Die!"        ⇒ sender ! "finally gone"; context.stop(self)
          }
        }), "davyJones")

        system.eventStream.subscribe(davyJones, classOf[DeadLetter])

        val probe = new TestProbe(system)
        probe.ref ! 42
        /*
       * This will ensure that the message is actually received, otherwise it
       * may happen that the system.stop() suspends the testActor before it had
       * a chance to put the message into its private queue
       */
        probe.receiveWhile(1 second) {
          case null ⇒
        }

        val latch = new TestLatch(1)(system)
        system.registerOnTermination(latch.countDown())
        system.shutdown()
        Await.ready(latch, 2 seconds)
        Await.result(davyJones ? "Die!", timeout.duration) should be === "finally gone"

        /* This will typically also contain log messages which were sent after the logger shutdown */
        locker should contain(DeadLetter(42, davyJones, probe.ref))
      } finally {
        system.shutdown()
        otherSystem.shutdown()
      }
    }

  }
}
