val scala3Version = "3.2.2"

lazy val root = project
  .in(file("."))
  .settings(
    name := "cats_effects",
    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.5-6581dc4",
      "org.scalameta" %% "munit" % "0.7.29" % Test
    )

  )
