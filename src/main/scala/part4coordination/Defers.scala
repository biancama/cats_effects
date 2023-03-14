package part4coordination

import cats.effect.{Deferred, Fiber, IO, IOApp, Outcome, Ref}
import utils.*

import scala.concurrent.duration.*
import cats.syntax.traverse.*
object Defers extends IOApp.Simple {
  val aDeferred: IO[Deferred[IO, Int]] = Deferred[IO, Int]
  val aDeferred_v2: IO[Deferred[IO, Int]] = IO.deferred[Int] // same


  // get blocks the calling fiber (semantically) until some other fiber completes the Deferred with a value
  val reader: IO[Int] = aDeferred.flatMap { signal =>
    signal.get // blocks the fiber until some other defer will write in the deferred
  }
  val writer = aDeferred.flatMap { signal =>
    signal.complete(42) // this will unblock the reader
  }

  def demoDeferred(): IO[Unit] = {
    def consumer(signal: Deferred[IO, Int]) = for {
      _ <- IO("[consumer] waiting for result...").myDebug
      meaningOfLife <- signal.get // blocker
      _ <- IO(s"[consumer] got the result: $meaningOfLife").myDebug
    } yield ()

    def producer(signal: Deferred[IO, Int]) = for {
      _ <- IO("[producer] crunching numbers...").myDebug
      _ <- IO.sleep(1.second)
      _ <- IO("[producer] complete: 42").myDebug
      meaningOfLife <- IO(42)
      _ <- signal.complete(meaningOfLife)
    } yield ()

    for {
      signal <- Deferred[IO, Int]
      fibConsumer <- consumer(signal).start
      fibProducer <- producer(signal).start
      _ <- fibProducer.join
      _ <- fibConsumer.join
    } yield ()
  }
  // simulate downloading some content
  val fileParts = List("I ", "love S", "cala", " with Cat", "s Effect!<EOF>")

  def fileNotifierWithRef(): IO[Unit] = {
    def downloadFile(contentRef: Ref[IO, String]): IO[Unit] =
      fileParts
        .map { part =>
          IO(s"[downloader] got '$part'").myDebug >> IO.sleep(1.second) >> contentRef.update(currentContent => currentContent + part)
        }
        .sequence
        .void

    def notifyFileComplete(contentRef: Ref[IO, String]): IO[Unit] = for {
      file <- contentRef.get
      _ <- if (file.endsWith("<EOF>")) IO("[notifier] File download complete").myDebug
      else IO("[notifier] downloading...").myDebug >> IO.sleep(500.millis) >> notifyFileComplete(contentRef) // busy wait! not remove IO.sleep
    } yield ()

    for {
      contentRef <- Ref[IO].of("")
      fibDownloader <- downloadFile(contentRef).start
      notifier <- notifyFileComplete(contentRef).start
      _ <- fibDownloader.join
      _ <- notifier.join
    } yield ()
  }

  // deferred works miracles for waiting
  def fileNotifierWithDeferred(): IO[Unit] = {
    def notifyFileComplete(signal: Deferred[IO, String]): IO[Unit] = for {
      _ <- IO("[notifier] downloading...").myDebug
      _ <- signal.get // blocks until the signal is completed
      _ <- IO("[notifier] File download complete").myDebug
    } yield ()

    def downloadFilePart(part: String, contentRef: Ref[IO, String], signal: Deferred[IO, String]): IO[Unit] = for {
      _ <- IO(s"[downloader] got '$part'").myDebug
      _ <- IO.sleep(1.second)
      latestContent <- contentRef.updateAndGet(currentContent => currentContent + part)
      _ <- if (latestContent.contains("<EOF>")) signal.complete(latestContent) else IO.unit
    } yield ()

    for {
      contentRef <- Ref[IO].of("")
      signal <- Deferred[IO, String]
      notifierFib <- notifyFileComplete(signal).start
      fileTasksFib <- fileParts.map(part => downloadFilePart(part, contentRef, signal)).sequence.start
      _ <- notifierFib.join
      _ <- fileTasksFib.join
    } yield ()
  }


  /**
   * Exercises:
   *  - (medium) write a small alarm notification with two simultaneous IOs
   *    - one that increments a counter every second (a clock)
   *    - one that waits for the counter to become 10, then prints a message "time's up!"
   *
   *  - (mega hard) implement racePair with Deferred.
   *    - use a Deferred which can hold an Either[outcome for ioa, outcome for iob]
   *    - start two fibers, one for each IO
   *    - on completion (with any status), each IO needs to complete that Deferred
   *      (hint: use a finalizer from the Resources lesson)
   *      (hint2: use a guarantee call to make sure the fibers complete the Deferred)
   *    - what do you do in case of cancellation (the hardest part)?
   */

  def smallAlarm(): IO[Unit] = {
    def waitingTime(signal: Deferred[IO, Unit]) = for {
      _ <- IO("Waiting for the counter to become 10").myDebug
      _ <- signal.get
      _ <- IO("time's up!").myDebug
    } yield ()

    def counterEverySecond(signal: Deferred[IO, Unit], counter: Ref[IO, Int]): IO[Unit] = for {
      lastCounter <- counter.getAndUpdate(_ + 1)
      _ <- if (lastCounter == 10) signal.complete(()) else IO("waiting...").myDebug >> IO.sleep(1.second) >> counterEverySecond(signal, counter)
    } yield ()
    for {
      ref <- Ref[IO].of(0)
      signal <- Deferred[IO, Unit]
      fibWaiting <- waitingTime(signal).start
      fibCounter <- counterEverySecond(signal, ref).start
      _ <- fibWaiting.join
      _ <- fibCounter.join
    } yield ()
  }

  type RaceResult[A, B] = Either[
    (Outcome[IO, Throwable, A], Fiber[IO, Throwable, B]), // (winner result, loser fiber)
    (Fiber[IO, Throwable, A], Outcome[IO, Throwable, B]) // (loser fiber, winner result)
  ]
  type DeferredResult[A, B] = Either[Outcome[IO, Throwable, A], Outcome[IO, Throwable, B]]

  def ourRacePair[A, B](ioa: IO[A], iob: IO[B]): IO[RaceResult[A, B]] = IO.uncancelable {poll =>
    for {
      signal <- Deferred[IO, DeferredResult[A, B]]
      fibA <- ioa.guaranteeCase(outcomeA => signal.complete(Left(outcomeA)).void).start
      fibB <- iob.guaranteeCase(outcomeB => signal.complete(Right(outcomeB)).void).start
      result: DeferredResult[A, B] <- poll(signal.get) onCancel { // blocking call - should be cancelable
        for {
          cancelFibA <- fibA.cancel.start // start another fiber to cancel at the same time
          cancelFibB <- fibB.cancel.start
          _ <- cancelFibA.join
          _ <- cancelFibB.join
        } yield ()
      }
    } yield result match {
      case Left(outcomeA) => Left(outcomeA, fibB)
      case Right(outcomeB) => Right(fibA, outcomeB)
    }
  }
  //override def run: IO[Unit] = demoDeferred()
  //override def run: IO[Unit] = fileNotifierWithRef()
  //override def run: IO[Unit] = fileNotifierWithDeferred()
  override def run: IO[Unit] = smallAlarm()
}
