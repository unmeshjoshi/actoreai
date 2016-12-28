package com.actoreai

import akka.actor.{ActorRef, ActorSystem, Props}
import com.actoreai.OrderMessages.Order
import com.typesafe.config.ConfigFactory

object OrderMain {

  def main(args: Array[String]): Unit = {

    val port = if (args.isEmpty) "0" else args(0)

    val config = ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port").
      withFallback(ConfigFactory.load())

    val system: ActorSystem = ActorSystem("OMS", config)
    val orderActor: ActorRef = system.actorOf(Props[OrderActor], "orderProcessor")
    orderActor ! Order("1", "1", "2")
    println("Message Sent")
  }
}
