package com.actoreai

import akka.actor.Actor
import com.actoreai.OrderMessages.Order

class OrderActor extends Actor {

  def newOrder(order: Order): Unit = {
      println(s"$order Received")
  }

  override def receive: Receive = {
    case order @ Order(productId, customerId, quantity) => {
      newOrder(order)
    }
  }
}