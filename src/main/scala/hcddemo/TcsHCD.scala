package hcddemo

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import hcddemo.Assembly.Start
import hcddemo.TestHCD.SetupConfig

import scala.io.StdIn

class Supervisor extends Actor {

  val hcdRef = context.actorOf(Props[TestHCD], "HCD")

  override def receive: Receive = {
    case m @ _ => hcdRef forward(m)
  }
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
//      throw new RuntimeException("Assembly broken")
      hcdRef ! SetupConfig(14)
    }
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    println(s"Assembly Restarted because of ${reason.getMessage}")
  }
}

object TestHCD {
  case class SetupConfig(val position:Int){}
}

object TestHCDApp extends App {
  implicit val system = ActorSystem("hcdApp")
  implicit val materializer = ActorMaterializer()
  // needed for the future map/flatmap in the end
  implicit val executionContext = system.dispatcher
  val assemblyRef = system.actorOf(Props[Assembly], "Assembly")

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