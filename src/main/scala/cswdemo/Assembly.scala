package cswdemo

import akka.actor.{Actor, ActorIdentity, ActorRef, ActorSystem, Identify, Props, Stash}
import com.typesafe.config.ConfigFactory
import cswdemo.ComponentType.HCD
import cswdemo.Connection.AkkaConnection
import cswdemo.LocationService.{Location, ResolvedAkkaLocation}

import scala.concurrent.duration._

object AssemblyApp extends App {

  val config = ConfigFactory.parseString(s"akka.remote.netty.tcp.port=2553").
    withFallback(ConfigFactory.load())

  val actorSystem = ActorSystem("AssemblyActorSystem", config)
  val assemplyActorRef = actorSystem.actorOf(Props[Assembly])

  import actorSystem.dispatcher

  actorSystem.scheduler.schedule(1 second, 1 second, assemplyActorRef, StartObservation())
}

class Assembly extends Actor with Stash {
  var hcdActorRef:Option[ActorRef] = null
  private val trackerSubscriber = context.actorOf(LocationSubscriberActor.props)
  trackerSubscriber ! LocationSubscriberActor.Subscribe
  LocationSubscriberActor.trackConnections(Set(AkkaConnection(ComponentId("hcd1", HCD))), trackerSubscriber)


  override def receive = {
    case location: Location =>
      location match {
        case l: ResolvedAkkaLocation =>
          hcdActorRef = l.actorRef
          // When the HCD is located, Started is sent to Supervisor
          //              supervisor ! Started
          unstashAll()
          context.become(ready)
      }
    case x => {
      println(x)
      stash()
    }

  }
  def ready:Receive = {
    case StartObservation() => {
      this.hcdActorRef.foreach(_ ! Move(1, 2))
    }
  }
}
