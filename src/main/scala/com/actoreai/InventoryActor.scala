package com.actoreai

import akka.actor.Actor
import akka.actor.Actor.Receive

class InventoryActor extends Actor {
  override def receive: Receive = {
    case _ => println ("hello")
  }
}
