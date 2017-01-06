import akka.stream.scaladsl.Source

import collection.mutable.Stack
import org.scalatest._

class ExampleSpec extends FlatSpec with Matchers {

  "trying out partial function" should "fail for partial functions" in {
    val v = new PartialFunction[Int, Int] {
      def apply(d: Int) = 42 / d

      def isDefinedAt(d: Int) = d != 0
    }
    v(42)
    v(0)
  }
}