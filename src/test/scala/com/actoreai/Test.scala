package com.actoreai
import akka.actor.Props
import cswdemo.HCD1
import org.scalatest._

import scala.reflect.ClassTag

//https://intellij-support.jetbrains.com/hc/en-us/community/posts/115000021130-IDE-can-t-automatically-create-a-runconfig-for-a-scalatest-testcase-rightclicking-on-the-class-doesn-t-give-me-the-option-and-I-get-an-exception-in-the-logs

class ExampleSpec extends FlatSpec {

  "trying out partial function" should "fail for partial functions" in {
    val v = new PartialFunction[Int, Int] {
      def apply(d: Int) = 42 / d

      def isDefinedAt(d: Int) = d != 0
    }
    v(42)
    v(0)
  }

  "trying out props" should "create props with generics" in {
    val props = Props[HCD1]
    println(props)
    import scala.reflect._
    val ct = classTag[String]
    println(ct)
  }
}