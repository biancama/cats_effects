package udemy.part2ErrorHandling

import cats.data.OptionT
import cats.effect.IO
import cats.syntax.all._

case class Food(name: String)

val food = List(
  Food("lasagna"),
  Food("cannedtuna")
)

def openCan(food: Food): IO[Food] = IO(food.copy(name = food.name.replace("canned", "")))

def getFood(foodName: String): IO[Option[Food]] = IO(food.find(_.name === foodName))

def normalize(foodName: String): Option[String] = Option.when(
  foodName.nonEmpty
)(foodName.toLowerCase)

//here we compose our program
def prepareFood(foodName: String): IO[Option[Food]] = {
  val food = for {
    //creating instance from Option
    normalized <- OptionT.fromOption[IO](normalize(foodName))
    //transformation with OptionT.apply
    food <- OptionT(getFood(normalized))
    _ <- if(food.name.contains("canned")) {
      //lifting functor F into OptionT
      OptionT.liftF(openCan(food))
    } else {
      //Wrapping pure value
      OptionT.pure[IO](food)
    }
  } yield food

  food.value
}