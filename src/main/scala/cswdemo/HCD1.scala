package cswdemo

import akka.actor.{Actor, ActorSystem, Props}
import com.typesafe.config.ConfigFactory
import cswdemo.ComponentType.HCD

import scala.util.{Failure, Success}

object HCD1App extends App {
  val config = ConfigFactory.parseString(s"akka.remote.netty.tcp.port=2552").
    withFallback(ConfigFactory.load())

  import scala.concurrent.ExecutionContext.Implicits.global

  val actorSystem = ActorSystem("HCDSystem", config)

  val hcdActorRef = actorSystem.actorOf(Props[HCD1], "hcd1")

  println(hcdActorRef)

  LocationService.registerAkkaConnection(ComponentId("hcd1", HCD), hcdActorRef, "nfiraos.ncc.tromboneHCD")(actorSystem).onComplete {
    case Success(value) => println(s"Registered Successfully $value")
    case Failure(e) => println("Could not register")
  }
}

class HCD1 extends Actor {
  override def receive: Receive = {
    case Move(x, y) => println(s"Moved to $x, $y")
  }
}
