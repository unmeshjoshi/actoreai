package com.actoreai

import akka.actor.Actor
import akka.actor.Actor.Receive

class CustomerActor extends Actor {
  override def receive: Receive = {
    case _ => println("hello")
  }
}
