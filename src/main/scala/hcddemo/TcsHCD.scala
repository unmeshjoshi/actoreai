package hcddemo

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import hcddemo.Assembly.Start
import hcddemo.TestHCD.SetupConfig

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
      hcdRef ! SetupConfig(14)
    }
  }
}

object TestHCD {
  case class SetupConfig(val position:Int){}
}

object TestHCDApp extends App {
  val system = ActorSystem("hcdApp")
  val assemblyRef = system.actorOf(Props[Assembly], "Assembly")
  assemblyRef ! Start()
}