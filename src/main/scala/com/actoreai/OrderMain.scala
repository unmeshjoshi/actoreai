package com.actoreai

import akka.actor.{ActorRef, ActorSystem, Props}
import com.actoreai.OrderMessages.NewOrder
import com.typesafe.config.ConfigFactory

object OrderMain extends App {
    val port = if (args.isEmpty) "5222" else args(0)

    val config = ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port").
      withFallback(ConfigFactory.load())

    val system: ActorSystem = ActorSystem("OMS", config)
    val orderActor: ActorRef = system.actorOf(Props[OrderActor], "orderProcessor")
    orderActor ! NewOrder("1", "1", "2")
    println("Message Sent")
}
