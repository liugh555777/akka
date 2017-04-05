/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.testkit.scaladsl

import scala.concurrent.duration._
import java.util.concurrent.BlockingDeque
import akka.typed.Behavior
import akka.typed.scaladsl.Actor._
import akka.typed.ActorSystem
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicInteger
import akka.typed.ActorRef
import akka.util.Timeout
import scala.concurrent.Await
import com.typesafe.config.Config

// FIXME update docs here if we find classic testkit useful for typed

object TestKit {

  def testActor[M](queue: BlockingDeque[M]): Behavior[M] = Stateful { (ctx, msg) ⇒
    queue.offerLast(msg)
    Same
  }

}

class TestKitSettings(val config: Config) {

  import akka.util.Helpers._

  val TestTimeFactor = config.getDouble("akka.test.timefactor").
    requiring(tf ⇒ !tf.isInfinite && tf > 0, "akka.test.timefactor must be positive finite double")
  val SingleExpectDefaultTimeout: FiniteDuration = config.getMillisDuration("akka.test.single-expect-default")
  val DefaultTimeout: Timeout = Timeout(config.getMillisDuration("akka.test.default-timeout"))
}

object TestProbe {
  private val testActorId = new AtomicInteger(0)

  private def format(u: TimeUnit, d: Duration) = "%.3f %s".format(d.toUnit(u), u.toString.toLowerCase)

  def apply[M]()(implicit system: ActorSystem[_], settings: TestKitSettings): TestProbe[M] =
    apply(name = "test")

  def apply[M](name: String)(implicit system: ActorSystem[_], settings: TestKitSettings): TestProbe[M] =
    new TestProbe(name)
}

class TestProbe[M](name: String)(implicit val system: ActorSystem[_], val settings: TestKitSettings) {
  import TestProbe._
  private val queue = new LinkedBlockingDeque[M]

  private var end: Duration = Duration.Undefined

  /**
   * if last assertion was expectNoMsg, disable timing failure upon within()
   * block end.
   */
  private var lastWasNoMsg = false

  private var lastMessage: Option[M] = None

  val testActor: ActorRef[M] = {
    implicit val timeout = Timeout(3.seconds)
    val futRef = system.systemActorOf(TestKit.testActor(queue), s"$name-${testActorId.incrementAndGet()}")
    Await.result(futRef, timeout.duration + 1.second)
  }

  /**
   * Shorthand to get the testActor.
   */
  def ref: ActorRef[M] = testActor

  /**
   * Obtain current time (`System.nanoTime`) as Duration.
   */
  protected def now: FiniteDuration = System.nanoTime.nanos

  /**
   * Obtain time remaining for execution of the innermost enclosing `within`
   * block or missing that it returns the properly dilated default for this
   * case from settings (key "akka.test.single-expect-default").
   */
  def remainingOrDefault = remainingOr(settings.SingleExpectDefaultTimeout.dilated)

  /**
   * Obtain time remaining for execution of the innermost enclosing `within`
   * block or throw an [[AssertionError]] if no `within` block surrounds this
   * call.
   */
  def remaining: FiniteDuration = end match {
    case f: FiniteDuration ⇒ f - now
    case _                 ⇒ throw new AssertionError("`remaining` may not be called outside of `within`")
  }

  /**
   * Obtain time remaining for execution of the innermost enclosing `within`
   * block or missing that it returns the given duration.
   */
  def remainingOr(duration: FiniteDuration): FiniteDuration = end match {
    case x if x eq Duration.Undefined ⇒ duration
    case x if !x.isFinite             ⇒ throw new IllegalArgumentException("`end` cannot be infinite")
    case f: FiniteDuration            ⇒ f - now
  }

  private def remainingOrDilated(max: Duration): FiniteDuration = max match {
    case x if x eq Duration.Undefined ⇒ remainingOrDefault
    case x if !x.isFinite             ⇒ throw new IllegalArgumentException("max duration cannot be infinite")
    case f: FiniteDuration            ⇒ f.dilated
  }

  /**
   * Execute code block while bounding its execution time between `min` and
   * `max`. `within` blocks may be nested. All methods in this trait which
   * take maximum wait times are available in a version which implicitly uses
   * the remaining time governed by the innermost enclosing `within` block.
   *
   * Note that the timeout is scaled using Duration.dilated, which uses the
   * configuration entry "akka.test.timefactor", while the min Duration is not.
   *
   * {{{
   * val ret = within(50 millis) {
   *   test ! "ping"
   *   expectMsgClass(classOf[String])
   * }
   * }}}
   */
  def within[T](min: FiniteDuration, max: FiniteDuration)(f: ⇒ T): T = {
    val _max = max.dilated
    val start = now
    val rem = if (end == Duration.Undefined) Duration.Inf else end - start
    assert(rem >= min, s"required min time $min not possible, only ${format(min.unit, rem)} left")

    lastWasNoMsg = false

    val max_diff = _max min rem
    val prev_end = end
    end = start + max_diff

    val ret = try f finally end = prev_end

    val diff = now - start
    assert(min <= diff, s"block took ${format(min.unit, diff)}, should at least have been $min")
    if (!lastWasNoMsg) {
      assert(diff <= max_diff, s"block took ${format(_max.unit, diff)}, exceeding ${format(_max.unit, max_diff)}")
    }

    ret
  }

  /**
   * Same as calling `within(0 seconds, max)(f)`.
   */
  def within[T](max: FiniteDuration)(f: ⇒ T): T = within(Duration.Zero, max)(f)

  /**
   * Same as `expectMsg(remainingOrDefault, obj)`, but correctly treating the timeFactor.
   */
  def expectMsg[T <: M](obj: T): T = expectMsg_internal(remainingOrDefault, obj)

  /**
   * Receive one message from the test actor and assert that it equals the
   * given object. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   *
   * @return the received object
   */
  def expectMsg[T <: M](max: FiniteDuration, obj: T): T = expectMsg_internal(max.dilated, obj)

  /**
   * Receive one message from the test actor and assert that it equals the
   * given object. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   *
   * @return the received object
   */
  def expectMsg[T <: M](max: FiniteDuration, hint: String, obj: T): T = expectMsg_internal(max.dilated, obj, Some(hint))

  private def expectMsg_internal[T <: M](max: Duration, obj: T, hint: Option[String] = None): T = {
    val o = receiveOne(max)
    val hintOrEmptyString = hint.map(": " + _).getOrElse("")
    assert(o != null, s"timeout ($max) during expectMsg while waiting for $obj" + hintOrEmptyString)
    assert(obj == o, s"expected $obj, found $o" + hintOrEmptyString)
    o.asInstanceOf[T]
  }

  /**
   * Receive one message from the internal queue of the TestActor. If the given
   * duration is zero, the queue is polled (non-blocking).
   *
   * This method does NOT automatically scale its Duration parameter!
   */
  private def receiveOne(max: Duration): M = {
    val message =
      if (max == 0.seconds) {
        queue.pollFirst
      } else if (max.isFinite) {
        queue.pollFirst(max.length, max.unit)
      } else {
        queue.takeFirst
      }
    lastWasNoMsg = false
    lastMessage = if (message == null) None else Some(message)
    message
  }

}
