/**
  * MIT License
  * 
  * Copyright (c) 2019 Kopaniev Vladyslav
  * 
  * Copyright (c) 2024 Jack C. Viers
  * 
  * Permission is hereby granted, free of charge, to any person obtaining a copy
  * of this software and associated documentation files (the "Software"), to deal
  * in the Software without restriction, including without limitation the rights
  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
  * copies of the Software, and to permit persons to whom the Software is
  * furnished to do so, subject to the following conditions:
  * 
  * The above copyright notice and this permission notice shall be included in all
  * copies or substantial portions of the Software.
  * 
  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
  * SOFTWARE.
  */
import $ivy.`io.github.davidgregory084::mill-tpolecat::0.3.5`
import io.github.davidgregory084.TpolecatModule
import $ivy.`com.lewisjkl::header-mill-plugin::0.0.3`
import header._
import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version::0.4.0`
import de.tobiasroeser.mill.vcs.version._
import $ivy.`com.github.lolgab::mill-mima::0.1.0`
import com.github.lolgab.mill.mima._
import mill._
import mill.scalalib._
import mill.scalalib.api._
import mill.scalalib.publish._
import java.time.Year

val versions = new {
  val cats = "2.9.0"
  val `cats-effect` = "3.4.0"
  val `cats-retry` = "3.1.0"
  val scalatest = "3.2.12"
  val discipline = "1.5.1"
  val `discipline-scalatest` = "2.2.0"
  val http4s = "0.23.0-RC1"
  val log4Cats = "2.1.1"
  val doobie = "1.0.0-M5"
  val circe = "0.14.1"
  val `logback-classic` = "1.2.3"
  val `kind-projector` = "0.13.3"
  val `better-monadic-for` = "0.3.1"
}

trait BaseModule extends HeaderModule with TpolecatModule {
  override def license: HeaderLicense = MITMultiple(Seq(2019, 2024), Seq("Kopaniev Vladyslav", "Jack C. Viers")).license
}

object `cats-saga` extends Cross[CatsSagaModule]("2.13.13", "3.3.3", "3.4.0"){
  override def defaultCrossSegments = Seq("3.3.3")
}

trait CatsSagaModule extends CrossScalaModule with BaseModule with PublishModule with Mima {

  override def ivyDeps = T {
    super.ivyDeps() ++ Agg(
      ivy"org.typelevel::cats-effect:${versions.`cats-effect`}",
      ivy"com.github.cb372::cats-retry:${versions.`cats-retry`}"
    )
  }

  override def scalacPluginIvyDeps = T {
    super.scalacPluginIvyDeps() ++ {
      if(!ZincWorkerUtil.isScala3(scalaVersion())){
        Agg(ivy"org.typelevel:kind-projector_${scalaVersion()}:${versions.`kind-projector`}")
      } else{
        Agg.empty
      }
    }
  }

  object test extends ScalaTests with TestModule.ScalaTest {
    override def scalacOptions = T.task{
      super.scalacOptions() ++ {if(!ZincWorkerUtil.isScala3(scalaVersion())) Agg("-Xmigration") else Agg.empty[String]}
    }

    def ivyDeps = Agg(
      ivy"org.typelevel::cats-laws:${versions.cats}",
      ivy"org.typelevel::cats-effect-laws:${versions.`cats-effect`}",
      ivy"org.scalatest::scalatest:${versions.scalatest}",
      ivy"org.typelevel::cats-effect-testkit:${versions.`cats-effect`}",
      ivy"org.typelevel::discipline-core:${versions.discipline}",
      ivy"org.typelevel::discipline-scalatest:${versions.`discipline-scalatest`}",
    )
  }

  override def publishVersion = VcsVersion.vcsState().format(untaggedSuffix = "-SNAPSHOT")
  override def versionScheme: T[Option[VersionScheme]] = T(
    Option(VersionScheme.EarlySemVer)
  )
  override def mimaPreviousVersions = T{Seq.empty[String]}
  override def mimaPreviousArtifacts: Target[Agg[Dep]] = T {
    val md = artifactMetadata()
    Agg.from(
      mimaPreviousVersions().map(v => ivy"${md.group}:${md.id}:${v}")
    )
  }
  def pomSettings = T {
    PomSettings(
      description = "The saga pattern for scala cats-effect.",
      organization = "com.jackcviers",
      url = "https://github.com/jackcviers/cats-saga",
      licenses = Seq(License.`MIT`),
      versionControl = VersionControl.github("jackcviers", "cats-saga"),
      developers = Seq(
        Developer("jackcviers", "Jack Viers", "https.//github.com/jackcviers"),
        Developer("vladkopanev", "Kopaniev Vladyslav", "https://github.com/VladKopanev")
      )
    )
  }
}

object examples extends Cross[ExamplesModule]("2.13.13", "3.3.3", "3.4.0"){
  override def defaultCrossSegments = Seq("3.3.3")
}

trait ExamplesModule extends CrossScalaModule with BaseModule {

  override def scalacOptions = T.task{
    super.scalacOptions() ++ {if(ZincWorkerUtil.isScala3(scalaVersion())) Agg("-source:future") else Agg.empty}
  }

  override def moduleDeps = Seq(`cats-saga`())

  override def ivyDeps = T{
    super.ivyDeps() ++ Agg(
      ivy"ch.qos.logback:logback-classic:${versions.`logback-classic`}",
      ivy"com.github.cb372::cats-retry:${versions.`cats-retry`}",
      ivy"org.typelevel::log4cats-slf4j:${versions.log4Cats}",
      ivy"io.circe::circe-generic:${versions.circe}",
      ivy"io.circe::circe-parser:${versions.circe}",
      ivy"org.http4s::http4s-circe:${versions.http4s}",
      ivy"org.http4s::http4s-dsl:${versions.http4s}",
      ivy"org.http4s::http4s-blaze-server:${versions.http4s}",
      ivy"org.tpolecat::doobie-core:${versions.doobie}",
      ivy"org.tpolecat::doobie-hikari:${versions.doobie}",
      ivy"org.tpolecat::doobie-postgres:${versions.doobie}"
    )
  }

  override def scalacPluginIvyDeps = T {
    super.scalacPluginIvyDeps() ++ {
      if(!ZincWorkerUtil.isScala3(scalaVersion())) Agg(ivy"org.typelevel:kind-projector_${scalaVersion()}:${versions.`kind-projector`}", ivy"com.olegpy::better-monadic-for:${versions.`better-monadic-for`}") else Agg.empty
    }
  }
}

final case class MITMultiple(years: Seq[Int], copyrightHolders: Seq[String]) {
  assert(years.size == copyrightHolders.size)
  private lazy val copyrightStatements = years.zipAll(copyrightHolders, Year.now(), "Jack C. Viers").map{ case (year, copyrightHolder) => s"Copyright $year $copyrightHolder" }.mkString("\n\n")
 private lazy val text: String = s"""|$copyrightStatements
                        |
                        |Permission is hereby granted, free of charge, to any person obtaining a copy of
                        |this software and associated documentation files (the "Software"), to deal in
                        |the Software without restriction, including without limitation the rights to
                        |use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
                        |the Software, and to permit persons to whom the Software is furnished to do so,
                        |subject to the following conditions:
                        |
                        |The above copyright notice and this permission notice shall be included in all
                        |copies or substantial portions of the Software.
                        |
                        |THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
                        |IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
                        |FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
                        |COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
                        |IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
                        |CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
                        |""".stripMargin
  def license: HeaderLicense = HeaderLicense.Custom(text)
}

