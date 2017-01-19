package hcddemo

import akka.actor.ActorSystem
import redis.RedisClient

class RedisPublisher(implicit val actorSystem:ActorSystem) {
  val redis = RedisClient("127.0.0.1", 6379)
  def publish(channel:String, value:String): Unit = {
    redis.publish(channel, value)
  }
}
