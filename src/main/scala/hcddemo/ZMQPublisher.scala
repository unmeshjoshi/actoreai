package hcddemo

import java.util.concurrent.Executors

import org.zeromq.ZMQ

import scala.concurrent.ExecutionContext

class ZMQPublisher {
  val address = "tcp://localhost:8082"

  val zmqContext =  ZMQ.context(1)
  val socket = zmqContext.socket(ZMQ.PUB)
  println(s"Zmq publisher connecting to $address")
  socket.bind(address)

  def publish(message:String) = {
    socket.send("Hello ZeroMq");
  }
}
