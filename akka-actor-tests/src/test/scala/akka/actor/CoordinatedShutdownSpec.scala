/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor

import java.util
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.Future

import akka.Done
import akka.testkit.{ AkkaSpec, EventFilter, TestKit, TestProbe }
import com.typesafe.config.{ Config, ConfigFactory }
import akka.actor.CoordinatedShutdown.Phase
import akka.actor.CoordinatedShutdown.UnknownReason
import akka.util.ccompat.JavaConverters._
import scala.concurrent.Promise
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

import akka.ConfigurationException

class CoordinatedShutdownSpec
    extends AkkaSpec(ConfigFactory.parseString("""
    akka.loglevel=INFO
    akka.loggers = ["akka.testkit.TestEventListener"]
  """)) {

  def extSys = system.asInstanceOf[ExtendedActorSystem]

  // some convenience to make the test readable
  def phase(dependsOn: String*): Phase = Phase(dependsOn.toSet, timeout = 10.seconds, recover = true, enabled = true)
  val emptyPhase: Phase = Phase(Set.empty, timeout = 10.seconds, recover = true, enabled = true)
  val abortingPhase: Phase = Phase(Set.empty, timeout = 10.seconds, recover = false, enabled = true)

  private def checkTopologicalSort(phases: Map[String, Phase]): List[String] = {
    val result = CoordinatedShutdown.topologicalSort(phases)
    result.zipWithIndex.foreach {
      case (phase, i) =>
        phases.get(phase) match {
          case Some(Phase(dependsOn, _, _, _)) =>
            dependsOn.foreach { depPhase =>
              withClue(
                s"phase [$phase] depends on [$depPhase] but was ordered before it in topological sort result $result") {
                i should be > result.indexOf(depPhase)
              }
            }
          case None => // ok
        }
    }
    result
  }

  case object CustomReason extends CoordinatedShutdown.Reason

  "CoordinatedShutdown" must {

    "sort phases in topological order" in {
      checkTopologicalSort(Map.empty) should ===(Nil)

      checkTopologicalSort(Map("a" -> emptyPhase)) should ===(List("a"))

      checkTopologicalSort(Map("b" -> phase("a"))) should ===(List("a", "b"))

      val result1 = checkTopologicalSort(Map("c" -> phase("a"), "b" -> phase("a")))
      result1.head should ===("a")
      // b, c can be in any order
      result1.toSet should ===(Set("a", "b", "c"))

      checkTopologicalSort(Map("b" -> phase("a"), "c" -> phase("b"))) should ===(List("a", "b", "c"))

      checkTopologicalSort(Map("b" -> phase("a"), "c" -> phase("a", "b"))) should ===(List("a", "b", "c"))

      val result2 = checkTopologicalSort(Map("c" -> phase("a", "b")))
      result2.last should ===("c")
      // a, b can be in any order
      result2.toSet should ===(Set("a", "b", "c"))

      checkTopologicalSort(Map("b" -> phase("a"), "c" -> phase("b"), "d" -> phase("b", "c"), "e" -> phase("d"))) should ===(
        List("a", "b", "c", "d", "e"))

      val result3 =
        checkTopologicalSort(Map("a2" -> phase("a1"), "a3" -> phase("a2"), "b2" -> phase("b1"), "b3" -> phase("b2")))
      val (a, b) = result3.partition(_.charAt(0) == 'a')
      a should ===(List("a1", "a2", "a3"))
      b should ===(List("b1", "b2", "b3"))
    }

    "detect cycles in phases (non-DAG)" in {
      intercept[IllegalArgumentException] {
        CoordinatedShutdown.topologicalSort(Map("a" -> phase("a")))
      }

      intercept[IllegalArgumentException] {
        CoordinatedShutdown.topologicalSort(Map("b" -> phase("a"), "a" -> phase("b")))
      }

      intercept[IllegalArgumentException] {
        CoordinatedShutdown.topologicalSort(Map("c" -> phase("a"), "c" -> phase("b"), "b" -> phase("c")))
      }

      intercept[IllegalArgumentException] {
        CoordinatedShutdown.topologicalSort(
          Map("d" -> phase("a"), "d" -> phase("c"), "c" -> phase("b"), "b" -> phase("d")))
      }

    }

    "have pre-defined phases from config" in {
      import CoordinatedShutdown._
      CoordinatedShutdown(system).orderedPhases should ===(
        List(
          PhaseBeforeServiceUnbind,
          PhaseServiceUnbind,
          PhaseServiceRequestsDone,
          PhaseServiceStop,
          PhaseBeforeClusterShutdown,
          PhaseClusterShardingShutdownRegion,
          PhaseClusterLeave,
          PhaseClusterExiting,
          PhaseClusterExitingDone,
          PhaseClusterShutdown,
          PhaseBeforeActorSystemTerminate,
          PhaseActorSystemTerminate))
    }

    "run ordered phases" in {
      import system.dispatcher
      val phases = Map("a" -> emptyPhase, "b" -> phase("a"), "c" -> phase("b", "a"), "d" -> phase("c", "b", "a"))
      val co = new CoordinatedShutdown(extSys, phases)
      co.addTask("a", "a1") { () =>
        testActor ! "A"
        Future.successful(Done)
      }
      co.addTask("b", "b1") { () =>
        testActor ! "B"
        Future.successful(Done)
      }
      co.addTask("b", "b2") { () =>
        Future {
          // to verify that c is not performed before b
          Thread.sleep(100)
          testActor ! "B"
          Done
        }
      }
      co.addTask("c", "c1") { () =>
        testActor ! "C"
        Future.successful(Done)
      }
      whenReady(co.run(UnknownReason)) { _ =>
        receiveN(4) should ===(List("A", "B", "B", "C"))
      }
    }

    "cancel tasks" in {
      import system.dispatcher
      val phases = Map("a" -> emptyPhase, "b" -> phase("a"))
      val co = new CoordinatedShutdown(extSys, phases)

      val task0Counter = new AtomicInteger()

      val task0: () => Future[Done] = () => Future {
        task0Counter.incrementAndGet()
        Done
      }

      co.addCancellableTask("a", "task0-copy0")(task0)
      co.addCancellableTask("a", "task0-copy1")(task0)
      val cancellable0 = co.addCancellableTask("a", "task0-copy2")(task0)

      object Task1 { // We can also add a method as a task
        val counter = new AtomicInteger()

        private def taskMethod(): Future[Done] = Future {
          counter.incrementAndGet()
          Done
        }

        def register(phase: String, name: String): Cancellable = co.addCancellableTask(phase, name)(taskMethod)
      }

      Task1.register("b", "task1-copy0")
      Task1.register("b", "task1-copy1")
      val cancellable1 = Task1.register("b", "task1-copy2")

      // Adding the same task twice under the same phase/name will still run it twice
      val task2counter = new AtomicInteger()
      co.addCancellableTask("a", "task2")(() => Future {
        task2counter.incrementAndGet()
        Done
      })
      co.addCancellableTask("a", "task2")(() => Future {
        task2counter.incrementAndGet()
        Done
      })
      val cancellable2 = co.addCancellableTask("a", "task2")(() => Future {
        task2counter.incrementAndGet()
        Done
      })

      object TaskAB { // tests cancellation by tasks in a previous/later phase
        val promiseA = Promise[Unit]
        val promiseB = Promise[Unit]
        val taskA: Cancellable = co.addCancellableTask("a", "task-a"){ () => Future {
          taskB.cancel()
          promiseA.trySuccess(log.info("Completing Promise A"))
          Done
        }}
        val taskB: Cancellable = co.addCancellableTask("b", "task-b"){ () => Future {
          taskA.cancel()
          promiseB.trySuccess(log.info("Completing Promise B"))
          Done
        }}
      }

      TaskAB.promiseA.isCompleted shouldBe false
      TaskAB.promiseB.isCompleted shouldBe false
      TaskAB.taskA.isCancelled shouldBe false
      TaskAB.taskB.isCancelled shouldBe false

      cancellable0.cancel()
      cancellable1.cancel()
      cancellable2.cancel()

      whenReady(co.run(UnknownReason)) { _ =>
        task0Counter.get shouldEqual 2
        Task1.counter.get shouldEqual 2
        task2counter.get shouldEqual 2
        TaskAB.promiseA.isCompleted shouldBe true
        TaskAB.promiseB.isCompleted shouldBe false
      }
    }

    "run from a given phase" in {
      val phases = Map("a" -> emptyPhase, "b" -> phase("a"), "c" -> phase("b", "a"))
      val co = new CoordinatedShutdown(extSys, phases)
      co.addTask("a", "a1") { () =>
        testActor ! "A"
        Future.successful(Done)
      }
      co.addTask("b", "b1") { () =>
        testActor ! "B"
        Future.successful(Done)
      }
      co.addTask("c", "c1") { () =>
        testActor ! "C"
        Future.successful(Done)
      }
      Await.result(co.run(CustomReason, Some("b")), remainingOrDefault)
      receiveN(2) should ===(List("B", "C"))
      co.shutdownReason() should ===(Some(CustomReason))
    }

    "only run once" in {
      val phases = Map("a" -> emptyPhase)
      val co = new CoordinatedShutdown(extSys, phases)
      co.addTask("a", "a1") { () =>
        testActor ! "A"
        Future.successful(Done)
      }
      co.shutdownReason() should ===(None)
      Await.result(co.run(CustomReason), remainingOrDefault)
      co.shutdownReason() should ===(Some(CustomReason))
      expectMsg("A")
      Await.result(co.run(UnknownReason), remainingOrDefault)
      testActor ! "done"
      expectMsg("done") // no additional A
      co.shutdownReason() should ===(Some(CustomReason))
    }

    "continue after timeout or failure" in {
      import system.dispatcher
      val phases = Map(
        "a" -> emptyPhase,
        "b" -> Phase(dependsOn = Set("a"), timeout = 100.millis, recover = true, enabled = true),
        "c" -> phase("b", "a"))
      val co = new CoordinatedShutdown(extSys, phases)
      co.addTask("a", "a1") { () =>
        testActor ! "A"
        Future.failed(new RuntimeException("boom"))
      }
      co.addTask("a", "a2") { () =>
        Future {
          // to verify that b is not performed before a also in case of failure
          Thread.sleep(100)
          testActor ! "A"
          Done
        }
      }
      co.addTask("b", "b1") { () =>
        testActor ! "B"
        Promise[Done]().future // never completed
      }
      co.addTask("c", "c1") { () =>
        testActor ! "C"
        Future.successful(Done)
      }
      EventFilter.warning(message = "Task [a1] failed in phase [a]: boom", occurrences = 1).intercept {
        EventFilter
          .warning(message = "Coordinated shutdown phase [b] timed out after 100 milliseconds", occurrences = 1)
          .intercept {
            Await.result(co.run(UnknownReason), remainingOrDefault)
          }
      }
      expectMsg("A")
      expectMsg("A")
      expectMsg("B")
      expectMsg("C")
    }

    "abort if recover=off" in {
      val phases = Map(
        "a" -> emptyPhase,
        "b" -> Phase(dependsOn = Set("a"), timeout = 100.millis, recover = false, enabled = true),
        "c" -> phase("b", "a"))
      val co = new CoordinatedShutdown(extSys, phases)
      co.addTask("b", "b1") { () =>
        testActor ! "B"
        Promise[Done]().future // never completed
      }
      co.addTask("c", "c1") { () =>
        testActor ! "C"
        Future.successful(Done)
      }
      val result = co.run(UnknownReason)
      expectMsg("B")
      intercept[TimeoutException] {
        Await.result(result, remainingOrDefault)
      }
      expectNoMessage() // C not run
    }

    "skip tasks in disabled phase" in {
      val phases = Map(
        "a" -> emptyPhase,
        "b" -> Phase(dependsOn = Set("a"), timeout = 100.millis, recover = false, enabled = false),
        "c" -> phase("b", "a"))
      val co = new CoordinatedShutdown(extSys, phases)
      co.addTask("b", "b1") { () =>
        testActor ! "B"
        Future.failed(new RuntimeException("Was expected to not be executed"))
      }
      co.addTask("c", "c1") { () =>
        testActor ! "C"
        Future.successful(Done)
      }
      EventFilter.info(start = "Phase [b] disabled through configuration", occurrences = 1).intercept {
        val result = co.run(UnknownReason)
        expectMsg("C")
        result.futureValue should ===(Done)
      }
    }

    "be possible to add tasks in later phase from task in earlier phase" in {
      val phases = Map("a" -> emptyPhase, "b" -> phase("a"))
      val co = new CoordinatedShutdown(extSys, phases)
      co.addTask("a", "a1") { () =>
        testActor ! "A"
        co.addTask("b", "b1") { () =>
          testActor ! "B"
          Future.successful(Done)
        }
        Future.successful(Done)
      }
      Await.result(co.run(UnknownReason), remainingOrDefault)
      expectMsg("A")
      expectMsg("B")
    }

    "parse phases from config" in {
      CoordinatedShutdown.phasesFromConfig(ConfigFactory.parseString("""
        default-phase-timeout = 10s
        phases {
          a = {}
          b {
            depends-on = [a]
            timeout = 15s
          }
          c {
            depends-on = [a, b]
            recover = off
          }
        }
        """)) should ===(
        Map(
          "a" -> Phase(dependsOn = Set.empty, timeout = 10.seconds, recover = true, enabled = true),
          "b" -> Phase(dependsOn = Set("a"), timeout = 15.seconds, recover = true, enabled = true),
          "c" -> Phase(dependsOn = Set("a", "b"), timeout = 10.seconds, recover = false, enabled = true)))
    }

    "default exit code to 0" in {
      lazy val conf = ConfigFactory.load().getConfig("akka.coordinated-shutdown")
      val confWithOverrides = CoordinatedShutdown.confWithOverrides(conf, None)
      confWithOverrides.getInt("exit-code") should ===(0)
    }

    "default exit code to -1 when the Reason is ClusterDowning" in {
      lazy val conf = ConfigFactory.load().getConfig("akka.coordinated-shutdown")
      val confWithOverrides =
        CoordinatedShutdown.confWithOverrides(conf, Some(CoordinatedShutdown.ClusterDowningReason))
      confWithOverrides.getInt("exit-code") should ===(-1)
    }

    "terminate ActorSystem" in {
      val sys = ActorSystem(system.name, system.settings.config)
      try {
        Await.result(CoordinatedShutdown(sys).run(CustomReason), 10.seconds) should ===(Done)
        sys.whenTerminated.isCompleted should ===(true)
        CoordinatedShutdown(sys).shutdownReason() should ===(Some(CustomReason))
      } finally {
        shutdown(sys)
      }
    }

    "be run by ActorSystem.terminate" in {
      val sys = ActorSystem(system.name, system.settings.config)
      try {
        Await.result(sys.terminate(), 10.seconds)
        sys.whenTerminated.isCompleted should ===(true)
        CoordinatedShutdown(sys).shutdownReason() should ===(Some(CoordinatedShutdown.ActorSystemTerminateReason))
      } finally {
        shutdown(sys)
      }
    }

    "not be run by ActorSystem.terminate when run-by-actor-system-terminate=off" in {
      val sys = ActorSystem(
        system.name,
        ConfigFactory
          .parseString("akka.coordinated-shutdown.run-by-actor-system-terminate = off")
          .withFallback(system.settings.config))
      try {
        Await.result(sys.terminate(), 10.seconds)
        sys.whenTerminated.isCompleted should ===(true)
        CoordinatedShutdown(sys).shutdownReason() should ===(None)
      } finally {
        shutdown(sys)
      }
    }

    "not allow terminate-actor-system=off && run-by-actor-system-terminate=on" in {
      intercept[ConfigurationException] {
        val sys = ActorSystem(
          system.name,
          ConfigFactory
            .parseString("akka.coordinated-shutdown.terminate-actor-system = off")
            .withFallback(system.settings.config))
        // will only get here if test is failing
        shutdown(sys)
      }
    }

    "add and remove user JVM hooks with run-by-jvm-shutdown-hook = off, terminate-actor-system = off" in new JvmHookTest {
      lazy val systemName = s"CoordinatedShutdownSpec-JvmHooks-1-${System.currentTimeMillis()}"
      lazy val systemConfig = ConfigFactory.parseString("""
          akka.coordinated-shutdown.run-by-jvm-shutdown-hook = off
          akka.coordinated-shutdown.terminate-actor-system = off
          akka.coordinated-shutdown.run-by-actor-system-terminate = off
        """)

      override def withSystemRunning(newSystem: ActorSystem): Unit = {
        val cancellable =
          CoordinatedShutdown(newSystem).addCancellableJvmShutdownHook(println(s"User JVM hook from ${newSystem.name}"))
        myHooksCount should ===(1) // one user, none from system
        cancellable.cancel()
      }
    }

    "add and remove user JVM hooks with run-by-jvm-shutdown-hook = on, terminate-actor-system = off" in new JvmHookTest {
      lazy val systemName = s"CoordinatedShutdownSpec-JvmHooks-2-${System.currentTimeMillis()}"
      lazy val systemConfig = ConfigFactory.parseString("""
          akka.coordinated-shutdown.run-by-jvm-shutdown-hook = on
          akka.coordinated-shutdown.terminate-actor-system = off
          akka.coordinated-shutdown.run-by-actor-system-terminate = off
        """)

      override def withSystemRunning(newSystem: ActorSystem): Unit = {
        val cancellable =
          CoordinatedShutdown(newSystem).addCancellableJvmShutdownHook(println(s"User JVM hook from ${newSystem.name}"))
        myHooksCount should ===(2) // one user, one from system

        cancellable.cancel()
      }
    }

    "add and remove user JVM hooks with run-by-jvm-shutdown-hook = on, terminate-actor-system = on" in new JvmHookTest {
      lazy val systemName = s"CoordinatedShutdownSpec-JvmHooks-3-${System.currentTimeMillis()}"
      lazy val systemConfig = ConfigFactory.parseString("""
          akka.coordinated-shutdown.run-by-jvm-shutdown-hook = on
          akka.coordinated-shutdown.terminate-actor-system = on
        """)

      def withSystemRunning(newSystem: ActorSystem): Unit = {
        val cancellable =
          CoordinatedShutdown(newSystem).addCancellableJvmShutdownHook(println(s"User JVM hook from ${newSystem.name}"))
        myHooksCount should ===(2) // one user, one from actor system
        cancellable.cancel()
      }
    }

    "add and remove user JVM hooks with run-by-jvm-shutdown-hook = on, akka.jvm-shutdown-hooks = off" in new JvmHookTest {
      lazy val systemName = s"CoordinatedShutdownSpec-JvmHooks-4-${System.currentTimeMillis()}"
      lazy val systemConfig = ConfigFactory.parseString("""
          akka.jvm-shutdown-hooks = off
          akka.coordinated-shutdown.run-by-jvm-shutdown-hook = on
        """)

      def withSystemRunning(newSystem: ActorSystem): Unit = {
        val cancellable =
          CoordinatedShutdown(newSystem).addCancellableJvmShutdownHook(println(s"User JVM hook from ${newSystem.name}"))
        myHooksCount should ===(1) // one user, none from actor system
        cancellable.cancel()
      }
    }

    "access extension after system termination" in new JvmHookTest {
      lazy val systemName = s"CoordinatedShutdownSpec-terminated-${System.currentTimeMillis()}"
      lazy val systemConfig = ConfigFactory.parseString("""
          akka.coordinated-shutdown.run-by-jvm-shutdown-hook = on
          akka.coordinated-shutdown.terminate-actor-system = on
        """)

      def withSystemRunning(newSystem: ActorSystem): Unit = {
        TestKit.shutdownActorSystem(newSystem)
        CoordinatedShutdown(newSystem)

      }
    }

    def withCoordinatedShutdown(phases: Map[String, Phase])(block: CoordinatedShutdown => Unit): Unit = {
      val co = new CoordinatedShutdown(extSys, phases)
      try {
        block(co)
      } finally {
        watch(co.terminationWatcher) ! PoisonPill
        expectTerminated(co.terminationWatcher)
      }
    }

    "support actor termination tasks with a stop message" in {
      val phases = Map("a" -> abortingPhase)
      withCoordinatedShutdown(phases) { co =>
        val actorToWatch = TestProbe()
        co.addActorTerminationTask("a", "a1", actorToWatch.ref, Some("stop"))
        val result = co.run(UnknownReason)
        actorToWatch.expectMsg("stop")
        result.isReadyWithin(100.millis) should be(false)
        actorToWatch.ref ! PoisonPill
        result.futureValue should ===(Done)
      }
    }

    "support actor termination tasks without a stop message" in {
      val phases = Map("a" -> abortingPhase)
      withCoordinatedShutdown(phases) { co =>
        val actorToWatch = TestProbe()
        co.addActorTerminationTask("a", "a1", actorToWatch.ref, None)
        val result = co.run(UnknownReason)
        actorToWatch.expectNoMessage(100.millis)
        result.isReadyWithin(100.millis) should be(false)
        actorToWatch.ref ! PoisonPill
        result.futureValue should ===(Done)
      }
    }

    "support actor termination tasks for actors that are already shutdown" in {
      val phases = Map("a" -> abortingPhase)
      withCoordinatedShutdown(phases) { co =>
        val actorToWatch = TestProbe()
        watch(actorToWatch.ref)
        actorToWatch.ref ! PoisonPill
        expectTerminated(actorToWatch.ref)
        co.addActorTerminationTask("a", "a1", actorToWatch.ref, None)
        val result = co.run(UnknownReason)
        result.futureValue should ===(Done)
      }
    }

    "allow watching the same actor twice in the same phase" in {
      val phases = Map("a" -> abortingPhase)
      withCoordinatedShutdown(phases) { co =>
        val actorToWatch = TestProbe()
        co.addActorTerminationTask("a", "a1", actorToWatch.ref, Some("stop1"))
        co.addActorTerminationTask("a", "a2", actorToWatch.ref, Some("stop2"))
        val result = co.run(UnknownReason)
        actorToWatch.expectMsgAllOf("stop1", "stop2")
        actorToWatch.ref ! PoisonPill
        result.futureValue should ===(Done)
      }
    }

    "allow watching the same actor twice in different phases" in {
      val phases = Map("a" -> abortingPhase, "b" -> abortingPhase.copy(dependsOn = Set("a")))
      withCoordinatedShutdown(phases) { co =>
        val actorToWatch = TestProbe()
        co.addActorTerminationTask("a", "a1", actorToWatch.ref, Some("stopa"))
        // no stop message because it's just going to end up being dead lettered
        co.addActorTerminationTask("b", "b1", actorToWatch.ref, None)
        val result = co.run(UnknownReason)
        actorToWatch.expectMsg("stopa")
        actorToWatch.expectNoMessage(100.millis)
        actorToWatch.ref ! PoisonPill
        result.futureValue should ===(Done)
      }
    }

  }

  abstract class JvmHookTest {

    private val initialHookCount = trixyTrixCountJvmHooks(systemName)
    initialHookCount should ===(0)

    def systemName: String
    def systemConfig: Config
    def withSystemRunning(system: ActorSystem): Unit

    val newSystem = ActorSystem(systemName, systemConfig)

    withSystemRunning(newSystem)

    TestKit.shutdownActorSystem(newSystem)

    trixyTrixCountJvmHooks(systemName) should ===(0)

    protected def myHooksCount: Int = trixyTrixCountJvmHooks(systemName)

    private def trixyTrixCountJvmHooks(systemName: String): Int = {
      val clazz = Class.forName("java.lang.ApplicationShutdownHooks")
      val field = clazz.getDeclaredField("hooks")
      field.setAccessible(true)
      clazz.synchronized {
        val hooks = field.get(null).asInstanceOf[util.IdentityHashMap[Thread, Thread]]
        hooks.values().asScala.count(_.getName.startsWith(systemName))
      }
    }
  }

}
