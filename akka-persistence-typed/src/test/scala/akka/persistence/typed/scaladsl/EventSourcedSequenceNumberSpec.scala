/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.{ ScalaTestWithActorTestKit, TestProbe }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.RecoveryCompleted
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

object EventSourcedSequenceNumberSpec {

  private val conf = ConfigFactory.parseString(s"""
      akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
      akka.persistence.journal.inmem.test-serialization = on
    """)

}

class EventSourcedSequenceNumberSpec
    extends ScalaTestWithActorTestKit(EventSourcedSequenceNumberSpec.conf)
    with WordSpecLike
    with LogCapturing {

  private def behavior(pid: PersistenceId, probe: ActorRef[String]): Behavior[String] =
    Behaviors.setup(ctx =>
      EventSourcedBehavior[String, String, String](pid, "", { (_, command) =>
        probe ! s"${EventSourcedBehavior.lastSequenceNumber(ctx)} onCommand"
        Effect.persist(command).thenRun(_ => probe ! s"${EventSourcedBehavior.lastSequenceNumber(ctx)} thenRun")
      }, { (state, evt) =>
        probe ! s"${EventSourcedBehavior.lastSequenceNumber(ctx)} eventHandler"
        state + evt
      }).receiveSignal {
        case (_, RecoveryCompleted) =>
          probe ! s"${EventSourcedBehavior.lastSequenceNumber(ctx)} onRecoveryComplete"
      })

  "The sequence number" must {

    "be accessible in the handlers" in {
      val probe = TestProbe[String]()
      val ref = spawn(behavior(PersistenceId.ofUniqueId("ess-1"), probe.ref))
      probe.expectMessage("0 onRecoveryComplete")

      ref ! "cmd1"
      probe.expectMessage("0 onCommand")
      probe.expectMessage("0 eventHandler")
      probe.expectMessage("1 thenRun")
    }
  }
}
