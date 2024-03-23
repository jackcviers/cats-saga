/* Copyright 2019 Kopaniev Vladyslav
 *
 * Copyright 2024 Jack C. Viers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.vladkopanev.cats.saga.example.dao

import java.util.UUID
import cats.effect.kernel.MonadCancelThrow
import com.vladkopanev.cats.saga.example.model.{SagaInfo, SagaStep}
import doobie.util.transactor.Transactor
import io.circe.Json
import org.postgresql.util.PGobject

trait SagaLogDao[F[_]] {
  def finishSaga(sagaId: Long): F[Unit]

  def startSaga(initiator: UUID, data: Json): F[Long]

  def createSagaStep(
    name: String,
    sagaId: Long,
    result: Option[Json],
    failure: Option[String] = None
  ): F[Unit]

  def listExecutedSteps(sagaId: Long): F[List[SagaStep]]

  def listUnfinishedSagas: F[List[SagaInfo]]
}

class SagaLogDaoImpl[F[_]](xa: Transactor[F])(implicit B: MonadCancelThrow[F]) extends SagaLogDao[F] {
  import cats.syntax.all.*
  import doobie.*
  import doobie.implicits.*
  import doobie.postgres.implicits.*

  implicit val han:LogHandler = LogHandler.jdkLogHandler

  override def finishSaga(sagaId: Long): F[Unit] =
    sql"""UPDATE saga SET "finishedAt" = now() WHERE id = $sagaId""".update.run.transact(xa).void

  override def startSaga(initiator: UUID, data: Json): F[Long] =
    sql"""INSERT INTO saga("initiator", "createdAt", "finishedAt", "data", "type") 
          VALUES ($initiator, now(), null, $data, 'order')""".update
      .withUniqueGeneratedKeys[Long]("id")
      .transact(xa)

  override def createSagaStep(
    name: String,
    sagaId: Long,
    result: Option[Json],
    failure: Option[String]
  ): F[Unit] =
    sql"""INSERT INTO saga_step("sagaId", "name", "result", "finishedAt", "failure")
          VALUES ($sagaId, $name, $result, now(), $failure)""".update.run
      .transact(xa)
      .void

  override def listExecutedSteps(sagaId: Long): F[List[SagaStep]] =
    sql"""SELECT "sagaId", "name", "finishedAt", "result", "failure"
          from saga_step WHERE "sagaId" = $sagaId""".query[SagaStep].to[List].transact(xa)

  override def listUnfinishedSagas: F[List[SagaInfo]] =
    sql"""SELECT "id", "initiator", "createdAt", "finishedAt", "data", "type"
          from saga s WHERE "finishedAt" IS NULL""".query[SagaInfo].to[List].transact(xa)

  implicit lazy val JsonMeta: Meta[Json] = {
    import io.circe.parser.*
    Meta.Advanced
      .other[PGobject]("jsonb")
      .timap[Json](
        pgObj => parse(pgObj.getValue).fold(e => sys.error(e.message), identity)
      )(
        json => {
          val pgObj = new PGobject
          pgObj.setType("jsonb")
          pgObj.setValue(json.noSpaces)
          pgObj
        }
      )
  }

}
