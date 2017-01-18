package cswdemo.events

import redis.RedisClient

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object RedisClientTest extends App {

  implicit val akkaSystem = akka.actor.ActorSystem()


  val redis = RedisClient("127.0.0.1", 6379)

  val futurePong = redis.ping()
  println("Ping sent!")
  futurePong.map(pong => {
    println(s"Redis replied with a $pong")
  })
  Await.result(futurePong, 5 seconds)
}
