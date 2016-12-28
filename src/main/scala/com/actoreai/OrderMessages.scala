package com.actoreai



object OrderMessages {
  case class Order(productId:String, customerId:String, quantity:String)
}