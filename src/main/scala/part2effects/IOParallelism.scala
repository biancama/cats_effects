package part2effects

import cats.Parallel
import cats.effect.{IO, IOApp}

object IOParallelism extends IOApp.Simple {

  // IOs are usually sequential
  val aniIO = IO(s"[${Thread.currentThread().getName}] Ani")
  val kamranIO = IO(s"[${Thread.currentThread().getName}] Kamran")

  val composedIO = for {
    ani <- aniIO
    kamran <- kamranIO
  } yield s"$ani and $kamran love Rock the JVM"

  import utils._
  // mapN extension method
  import cats.syntax.apply._
  val meaningOfLife: IO[Int] = IO.delay(42)
  val favLang: IO[String] = IO.delay("Scala")
  val goalInLife: IO[String] = (meaningOfLife.myDebug, favLang.myDebug).mapN((num, string) => s"my goal in life is $num and $string")  // sequential

  // parallelism on IOs
  // convert a sequential IO to parallel IO
  val parIO1: IO.Par[Int] = Parallel[IO].parallel(meaningOfLife.myDebug)
  val parIO2: IO.Par[String] = Parallel[IO].parallel(favLang.myDebug)
  import cats.effect.implicits._
  val goalInLifeParallel: IO.Par[String] = (parIO1, parIO2).mapN((num, string) => s"my goal in life is $num and $string")
  // turn back to sequential
  val goalInLife_v2: IO[String] = Parallel[IO].sequential(goalInLifeParallel).myDebug

  // shorthand:
  import cats.syntax.parallel._
  val goalInLife_v3: IO[String] = (meaningOfLife.myDebug, favLang.myDebug).parMapN((num, string) => s"my goal in life is $num and $string")

  val aFailure: IO[String] = IO.raiseError(new RuntimeException("I can't do this!"))
  // compose success + failure
  val parallelWithFailure = (meaningOfLife.myDebug, aFailure.myDebug).parMapN((num, string) => s"$num $string") // both are computed but since one  fails dso the third in not evaluated
  // compose failure + failure
  val anotherFailure: IO[String] = IO.raiseError(new RuntimeException("Second failure"))
  val twoFailures: IO[String] = (aFailure.myDebug, anotherFailure.myDebug).parMapN(_ + _) // first fail first is stop
  val twoFailuresDelayed: IO[String] = (IO(Thread.sleep(1000)) >> aFailure.myDebug, anotherFailure.myDebug).parMapN(_ + _)


  override def run: IO[Unit] =  {

    //composedIO.map(println)
//    goalInLife.map(println)
//
//    goalInLife_v2.map(println)
//    goalInLife_v3.myDebug.void
    //parallelWithFailure.myDebug.void
    //twoFailures.myDebug.void
    twoFailuresDelayed.myDebug.void
  }
}
