package hcddemo

import java.util.concurrent.Executors

import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.zeromq.ZMQ

import scala.concurrent.{ExecutionContext, Future}

class ZmqSubscriber() {
  val address = s"tcp://localhost:8082"

  val zmqContext =  ZMQ.context(1)
  private val socket = zmqContext.socket(ZMQ.SUB)
  println(s"ZmqSubscriber connecting to $address")
  socket.connect(address)
  socket.subscribe(Array.empty)

  private val ec = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor())

  val stream = {
    Source
      .repeat(())
      .mapAsync(1)(_ => receive())
      .map(bytes => ByteString(bytes).utf8String)
      .map(x => {println(s"********* ZmqSubscriber received $x"); x})
  }

  private def receive() = Future {
    socket.recv(0)
  }(ec)

  def shutdown(): Unit = {
    socket.close()
  }
}