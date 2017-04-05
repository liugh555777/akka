/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.adapter

import scala.concurrent.Await
import akka.{ actor ⇒ untyped }
import akka.typed.ActorRef
import akka.typed.ActorSystem
import akka.typed.Behavior
import akka.typed.TypedSpec
import akka.typed.TypedSpec.Create
import akka.typed.scaladsl.Actor._
import akka.typed.scaladsl.AskPattern._
import akka.typed.testkit.scaladsl.TestKitSettings
import akka.typed.testkit.scaladsl.TestProbe

object ActorSystemAdapterSpec {
  val untyped1: untyped.Props = untyped.Props(new Untyped1)

  class Untyped1 extends untyped.Actor {
    def receive = {
      case "ping" ⇒ sender() ! "pong"
    }
  }

  def typed1(ref: untyped.ActorRef, probe: ActorRef[String]): Behavior[String] =
    Stateful[String] { (ctx, msg) ⇒
      msg match {
        case "send" ⇒
          // FIXME cast, missing API?
          val replyTo = ctx.self.asInstanceOf[ActorRefAdapter[String]].untyped
          ref.tell("ping", replyTo)
          Same
        case "pong" ⇒
          probe ! "ok"
          Same
      }

    }
}

class ActorSystemAdapterSpec extends TypedSpec {
  import ActorSystemAdapterSpec._

  object `An ActorSystemAdapter` {
    implicit def system: ActorSystem[TypedSpec.Command] = adaptedSystem
    implicit lazy val testkitSettings = new TestKitSettings(system.settings.config)

    // FIXME missing API? ActorSystemAdapter is not public
    def untypedSystem: untyped.ActorSystem = system.asInstanceOf[ActorSystemAdapter[_]].untyped

    def `01 send message from typed to untyped`(): Unit = {
      val probe = TestProbe[String]()
      // FIXME no way to create an untyped.Actor when using ActorSystem.adapter ?
      // UnsupportedOperationException: cannot create top-level actor from the outside on ActorSystem with custom user guardian
      pending
      val untypedRef = untypedSystem.actorOf(untyped1)
      // FIXME synchronous Create similar to this would be useful in TestKit
      val typedRef = Await.result(system ? Create(typed1(untypedRef, probe.ref), name = "typed1"), probe.remainingOrDefault)
      typedRef ! "send"
      probe.expectMsg("ok")
    }
  }

}
