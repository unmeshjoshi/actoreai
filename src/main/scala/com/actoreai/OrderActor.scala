package com.actoreai

import akka.actor.Actor
import com.actoreai.OrderMessages.{NewOrder, Order}

class OrderActor extends Actor {

  def newOrder(order: NewOrder): Unit = {
      println(s"$order Received")
  }

  override def receive: Receive = {
    case order @ NewOrder(productId, customerId, quantity) => {
      newOrder(order)
    }
  }
}