package hcddemo

import java.net.InetSocketAddress
import java.util.concurrent.Executors

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.stream.ActorMaterializer
import hcddemo.Assembly.Start
import hcddemo.TestHCD.SetupConfig

import scala.concurrent.{ExecutionContext, Future}

class Supervisor extends Actor {

  val hcdRef = context.actorOf(Props[TestHCD], "HCD")

  override def receive: Receive = {
    case m @ _ => hcdRef forward(m)
  }
}


object TestHCD {
  case class SetupConfig(val position:Int){}
}

class TestHCD extends Actor with ActorLogging {
  var timesCalled :Int = 0
  override def receive: Receive = {
    case sc @ SetupConfig(position) => {
      timesCalled = timesCalled + 1
      if (timesCalled == 2) throw new RuntimeException("HCD Failed to execute")
      println(s"$sc from $sender")
    }
    case _ => println("Default message handle");
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    println(s"Restarted because of ${reason.getMessage}")
  }
}

object Assembly {
  case class Start()
}

class Assembly extends Actor {
  val hcdRef = context.actorOf(Props[Supervisor], "Supervisor")
  override def receive: Receive = {
    case s @ Start() => {
      println(s)
      hcdRef ! SetupConfig(12)
      hcdRef ! SetupConfig(13)
      hcdRef ! SetupConfig(14)
    }
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    println(s"Assembly Restarted because of ${reason.getMessage}")
  }
}

object TestHCDApp extends App {
  implicit val system = ActorSystem("hcdApp")
  implicit val materializer = ActorMaterializer()
  // needed for the future map/flatmap in the end
  implicit val executionContext = system.dispatcher


  val assemblyRef = system.actorOf(Props[Assembly], "Assembly")

  //sends messages on zeromq topic
  startHardwareEventPublisherSimulator()

  //receives messages from zeromq and sends to redis topic
  startHardwareEventSubscriber()

  //subscribes to redis and prints messages
  //start redis
  // docker build -t redis-centos .
  // docker run -i -t -p 6379:6379 redis-centos
  startRedisSubscriberClient()

  //Starts accepting http requests to start assembly
  //curl -X POST http://localhost:8080/start-assembly
  startHttpServer()


  private def startHttpServer() = {
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model._
    import akka.http.scaladsl.model.HttpMethods._

    val requestHandler: HttpRequest => HttpResponse = {
      case HttpRequest(POST, Uri.Path("/start-assembly"), _, _, _) =>
        assemblyRef ! Start()
        HttpResponse(entity = "Assembly started!")

      case r: HttpRequest =>
        r.discardEntityBytes() // important to drain incoming HTTP Entity stream
        HttpResponse(404, entity = "Unknown resource!")
    }

    val bindingFuture = Http().bindAndHandleSync(requestHandler, "localhost", 8080)
    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  }


  private def startHardwareEventPublisherSimulator() = {
    import scala.concurrent.duration._

    val publisher = new ZMQPublisher()
    system.scheduler.schedule(1 second, 1 second, new Runnable() {
      override def run(): Unit = {
        println("Publishing message")
        publisher.publish("Hello");
      }
    })
  }


  private def startHardwareEventSubscriber() = {
    val ec = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor())
    Future {
      val redisPublisher = new RedisPublisher()
      val zmqSubscriber = new ZmqSubscriber()
      zmqSubscriber.stream
        .runForeach { message =>
          redisPublisher.publish("hcd-status", message)
          println(s"Publishing $message to Redis")
        }.onComplete { x =>
        zmqSubscriber.shutdown()
      }
    }(ec)
  }


  private def startRedisSubscriberClient() = {
    import redis.api.pubsub.{Message, PMessage}
    import redis.actors.RedisSubscriberActor

    class SubscribeActor(channels: Seq[String] = Nil, patterns: Seq[String] = Nil)
      extends RedisSubscriberActor(
        new InetSocketAddress("localhost", 6379),
        channels,
        patterns,
        onConnectStatus = connected => { println(s"connected: $connected")}
      ) {

      def onMessage(message: Message) {
        println(s"Redis message received: $message")
      }

      def onPMessage(pmessage: PMessage) {
        println(s"pattern message received: $pmessage")
      }
    }


    system.actorOf(Props(classOf[SubscribeActor], Seq("hcd-status"), Seq("")).withDispatcher("rediscala.rediscala-client-worker-dispatcher"))
  }

}