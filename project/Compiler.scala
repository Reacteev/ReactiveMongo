import sbt._
import sbt.Keys._

object Compiler {
  private val silencerVersion = "1.2.1"

  val settings = Seq(
    scalacOptions ++= Seq(
      "-encoding", "UTF-8",
      "-unchecked",
      "-deprecation",
      "-feature",
      //"-Xfatal-warnings",
      "-Xlint",
      "-Ywarn-numeric-widen",
      "-Ywarn-dead-code",
      "-Ywarn-value-discard",
      "-g:vars"
    ),
    scalacOptions in Compile ++= {
      if (!scalaVersion.value.startsWith("2.11.")) Nil
      else Seq(
        "-Yconst-opt",
        "-Yclosure-elim",
        "-Ydead-code",
        "-Yopt:_"
      )
    },
    libraryDependencies in ThisBuild ++= {
      if (scalaVersion.value startsWith "2.10.") Nil
      else Seq(
        compilerPlugin(
          "com.github.ghik" %% "silencer-plugin" % silencerVersion),
        "com.github.ghik" %% "silencer-lib" % silencerVersion % Provided)
    },
    scalacOptions in Compile ++= {
      if (scalaVersion.value startsWith "2.10.") Nil
      else {
        val internal = ".*Internal:\\ will\\ be\\ made\\ private.*"
        val m26 = "MongoDB\\ 2\\.6\\ EOL\\ reached\\ by\\ Oct\\ 2016"
        val m3 = "MongoDB\\ 3\\.0\\ EOL\\ reached\\ by\\ Feb\\ 2018"
        val cmd = "Will\\ be\\ removed;\\ See\\ `Command`"
        val repl = "Will\\ be\\ replaced\\ by\\ `reactivemongo.*"

        Seq(
          "-Ywarn-infer-any",
          "-Ywarn-unused",
          "-Ywarn-unused-import",
          "-Xlint:missing-interpolator",
          s"-P:silencer:globalFilters=$internal;$m26;$m3;$internal;$repl"
        )
      }
    },
    scalacOptions in Compile ++= {
      if (!scalaVersion.value.startsWith("2.12.")) Seq("-target:jvm-1.6")
      else Seq("-target:jvm-1.8")
    },
    scalacOptions in (Compile, console) ~= {
      _.filterNot(excludeOpt)
    },
    scalacOptions in (Test, console) ~= {
      _.filterNot(excludeOpt)
    },
    scalacOptions in (Test, console) += "-Yrepl-class-based",
    scalacOptions in Compile := {
      val opts = (scalacOptions in Compile).value

      if (scalaVersion.value != "2.10.7") opts
      else {
        opts.filter(_ != "-Ywarn-unused-import")
      }
    }
  )

  private lazy val excludeOpt: String => Boolean = { opt =>
    opt.startsWith("-X") || opt.startsWith("-Y") ||
    opt.startsWith("-P:silencer")
  }
}
