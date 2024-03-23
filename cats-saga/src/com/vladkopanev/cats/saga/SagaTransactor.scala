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

package com.vladkopanev.cats.saga

import cats.MonadError
import cats.effect.{ Fiber, Spawn }
import cats.effect.kernel.{ MonadCancel, Outcome }
import com.vladkopanev.cats.saga.Saga.{
  CompensateFailed,
  CompensateSucceeded,
  Failed,
  FlatMap,
  Noop,
  Par,
  SagaErr,
  Step,
  Suceeded
}
import cats.syntax.all._
import scala.unchecked


trait SagaTransactor[F[_]] {
  def transact[A](saga: Saga[F, A])(implicit F: MonadError[F, Throwable]): F[A]
}

class SagaDefaultTransactor[F[_]] extends SagaTransactor[F] {

  def transact[A](saga: Saga[F, A])(implicit F: MonadError[F, Throwable]): F[A] = {
    def run[X](s: Saga[F, X]): F[(X, F[Unit])] = (s: @unchecked) match {
      case Suceeded(value) => F.pure((value, F.unit))
      case Failed(err)     => F.raiseError(SagaErr(err, F.unit))
      case Noop(computation) =>
        computation.attempt.flatMap {
          case Right(x) => F.pure((x, F.unit))
          case Left(ex) => F.raiseError(SagaErr(ex, F.unit))
        }
      case s: Step[F, X, Throwable] @unchecked =>
        s.action.attempt.flatMap {
          case r @ Right(x) => F.pure((x, s.compensate(r)))
          case e @ Left(ex) => F.raiseError(SagaErr(ex, s.compensate(e)))
        }
      case s: CompensateFailed[F, X, Throwable] @unchecked =>
        s.action.attempt.flatMap {
          case Right(x) => F.pure((x, F.unit))
          case Left(ex) => F.raiseError(SagaErr(ex, s.compensate(ex)))
        }
      case s: CompensateSucceeded[F, X] =>
        s.action.attempt.flatMap {
          case Right(x) => F.pure((x, s.compensate(x)))
          case Left(ex) => F.raiseError(SagaErr(ex, F.unit))
        }
      case FlatMap(chained: Saga[F, Any], continuation: (Any => Saga[F, X])) =>
        run(chained).flatMap {
          case (v, prevStepCompensator) =>
            run(continuation(v)).attempt.flatMap {
              case Right((x, currCompensator)) => F.pure((x, currCompensator *> prevStepCompensator))
              case Left(ex: SagaErr[F] @unchecked)        => F.raiseError(ex.copy(compensator = ex.compensator *> prevStepCompensator))
              case Left(err)                   =>
                //should not be here
                F.raiseError(err)
            }
        }
      case Par(
          left: Saga[F, Any],
          right: Saga[F, Any],
          combine: ((Any, Any) => X),
          combineCompensations,
          spawnInstance
          ) =>
        implicit val spawn: Spawn[F] = spawnInstance
        def coordinate[D, B, C](f: (D, B) => C)(
          fasterSaga: Outcome[F, Throwable, (D, F[Unit])],
          slowerSaga: Fiber[F, Throwable, (B, F[Unit])]
        ): F[(C, F[Unit])] = fasterSaga match {
          case Outcome.Succeeded(fa) =>
            fa.flatMap {
              case (a, compA) =>
                slowerSaga.join.flatMap[(C, F[Unit])] {
                  case Outcome.Succeeded(fA) =>
                    fA.map { case (b, compB) => f(a, b) -> combineCompensations(compB, compA) }
                  case Outcome.Errored(e: SagaErr[F] @unchecked) =>
                    F.raiseError(e.copy(compensator = combineCompensations(e.compensator, compA)))
                  case Outcome.Canceled() =>
                    //should not be here as we wrap our fibers in uncancelable
                    MonadCancel[F].canceled >> Spawn[F].never[(C, F[Unit])]
                  case Outcome.Errored(err) =>
                    //should not be here
                    F.raiseError(err)
                }
            }
          case Outcome.Errored(e: SagaErr[F] @unchecked) =>
            slowerSaga.join.flatMap[(C, F[Unit])] {
              case Outcome.Succeeded(fA) =>
                fA.flatMap {
                  case (_, compB) => F.raiseError(e.copy(compensator = combineCompensations(compB, e.compensator)))
                }
              case Outcome.Errored(ea: SagaErr[F] @unchecked) =>
                ea.cause.addSuppressed(e.cause)
                F.raiseError(ea.copy(compensator = combineCompensations(ea.compensator, e.compensator)))
              case Outcome.Canceled() =>
                //should not be here as we wrap our fibers in uncancelable
                MonadCancel[F].canceled >> Spawn[F].never[(C, F[Unit])]
              case Outcome.Errored(err) =>
                //should not be here
                F.raiseError(err)
            }
          case Outcome.Errored(err) =>
            //should not be here
            F.raiseError(err)
          case Outcome.Canceled() =>
            //should not be here as we wrap our fibers in uncancelable
            MonadCancel[F].canceled >> Spawn[F].never[(C, F[Unit])]
        }

        Spawn[F].racePair(run(left), run(right)).flatMap {
          case Left((fastLeft, slowRight))  => coordinate(combine)(fastLeft, slowRight)
          case Right((slowLeft, fastRight)) => coordinate((b: Any, a: Any) => combine(a, b))(fastRight, slowLeft)
        }
    }

    run(saga).map(_._1).handleErrorWith {
      case e: SagaErr[F] @unchecked => e.compensator.orElse(F.unit) *> F.raiseError(e.cause)
    }
  }
}
