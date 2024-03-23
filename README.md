
[comment]: <> (MIT License)

[comment]: <> ()

[comment]: <> (Copyright (c) 2019 Kopaniev Vladyslav)

[comment]: <> (Copyright (c) 2024 Jack C. Viers)

[comment]: <> (Permission is hereby granted, free of charge, to any person obtaining a copy)

[comment]: <> (of this software and associated documentation files (the "Software"), to deal)

[comment]: <> (in the Software without restriction, including without limitation the rights)

[comment]: <> (to use, copy, modify, merge, publish, distribute, sublicense, and/or sell)

[comment]: <> (copies of the Software, and to permit persons to whom the Software is)

[comment]: <> (furnished to do so, subject to the following conditions:)

[comment]: <> ()

[comment]: <> (The above copyright notice and this permission notice shall be included in all)

[comment]: <> (copies or substantial portions of the Software.)

[comment]: <> ()

[comment]: <> (THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR)

[comment]: <> (IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,)

[comment]: <> (FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE)

[comment]: <> (AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER)

[comment]: <> (LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,)

[comment]: <> (OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE)

[comment]: <> (SOFTWARE.)

# This is a hard fork of the archived [https://github.com/vladkopanev/cats-saga](vladkopanev/cats-saga) project. All attributions have been maintained to the original author.

# CATS-SAGA

Purely Functional Transaction Management In Scala With Cats

> [!WARNING] This project is for minor, lightweight, localized saga
> transactional use cases and is not a fully, distributed,
> fault-tolerant workflow orchestration/choreographed saga
> project. Please look elsewhere for that. It merely allows for local
> compensating calls within workflow steps on in a single
> program. Though it can be journaled to a database, there is no
> external coordinating service or queue, which is required for
> durability and fault-tolerant behavior.

# What is the saga pattern?

The [saga pattern](https://microservices.io/patterns/data/saga.html)
is a microservice pattern in which operations which may fail execute
under the control of other services or through remote execution (via
RPC, api-call, event emission, etc) can be rolled back transactionally
via the use of compensating actions.

# Getting started

## sbt

Add cats-saga dependency to our `build.sbt`:

`"com.jackcviers" %% "cats-saga" % "<version>"`

## mill

`ivy"com.jackcviers::cats-saga:<version>"`

# Example of usage:

Consider the following case, we have built our food delivery system in microservices fashion, so
we have `Order` service, `Payment` service, `LoyaltyProgram` service, etc. 
And now we need to implement a closing order method, that collects *payment*, assigns *loyalty* points 
and closes the *order*. This method should run transactionally so if e.g. *closing order* fails we will 
rollback the state for user and *refund payments*, *cancel loyalty points*.

Applying Saga pattern we need a compensating action for each call to particular microservice, those 
actions needs to be run for each completed request in case some of the requests fails.

![Order Saga Flow](./images/diagrams/Order%20Saga%20Flow.jpeg)

Let's think for a moment about how we could implement this pattern without any specific libraries.

The naive implementation could look like this:

```scala
def orderSaga(): IO[Unit] = {
  for {
    _ <- collectPayments(2d, 2) handleErrorWith (_ => refundPayments(2d, 2))
    _ <- assignLoyaltyPoints(1d, 1) handleErrorWith (_ => cancelLoyaltyPoints(1d, 1))
    _ <- closeOrder(1) handleErrorWith (_ => reopenOrder(1))
  } yield ()  
}
```

Looks pretty simple and straightforward, `handleErrorWith` function tries to recover the original request if it fails.
We have covered every request with a compensating action. But what if last request fails? We know for sure that corresponding 
compensation `reopenOrder` will be executed, but when other compensations would be run? Right, they would not be triggered, 
because the error would not be propagated higher, thus not triggering compensating actions. That is not what we want, we want 
full rollback logic to be triggered in Saga, whatever error occurred.
 
Second try, this time let's somehow trigger all compensating actions.
  
```scala
def orderSaga: IO[Unit] = {
  collectPayments(2d, 2).flatMap { _ =>
    assignLoyaltyPoints(1d, 1).flatMap { _ =>
      closeOrder(1) handleErrorWith(e => reopenOrder(1) *> IO.raiseError(e))
    } handleErrorWith (e => cancelLoyaltyPoints(1d, 1)  *> IO.raiseError(e))
  } handleErrorWith(e => refundPayments(2d, 2) *> IO.raiseError(e))  
}
```

This works, we trigger all rollback actions by failing after each. 
But the implementation itself looks awful, we lost expressiveness in the call-back hell, imagine 15 saga steps implemented in such manner.

We can solve this problems in different ways, but we will encounter a number of difficulties, and our code still would 
look pretty much the same as we did in our last try. 

Achieve a generic solution is not that simple, so we will end up
repeating the same boilerplate code from service to service.

`cats-saga` tries to address these concerns and provide us with simple syntax to compose our Sagas.

With `cats-saga` we could do it like so:

```scala
def orderSaga(): IO[Unit] = {
  import com.vladkopanev.cats.saga.Saga._
    
  (for {
    _ <- collectPayments(2d, 2) compensate refundPayments(2d, 2)
    _ <- assignLoyaltyPoints(1d, 1) compensate cancelLoyaltyPoints(1d, 1)
    _ <- closeOrder(1) compensate reopenOrder(1)
  } yield ()).transact
}
```

`compensate` pairs request `F[_]` actions with compensating `IO` actions
 and returns a new `Saga` object which we then compose with other
 `Sagas`.  To materialize `Saga` object to `F[_]` when it's complete it
 is required to use `transact` method.

Because `Saga` is effect polymorphic we could use whatever effect
type we want in tagless final style:

```scala
def orderSaga[F[_]: Concurrent](): F[Unit] = {
  import com.vladkopanev.cats.saga.Saga._
    
  (for {
    _ <- collectPayments(2d, 2) compensate refundPayments(2d, 2)
    _ <- assignLoyaltyPoints(1d, 1) compensate cancelLoyaltyPoints(1d, 1)
    _ <- closeOrder(1) compensate reopenOrder(1)
  } yield ()).transact
}
```

As we can see with `cats-saga` the process of building our Sagas is
greatly simplified comparably to ad-hoc solutions.  `cats-saga`s are
composable, boilerplate-free and intuitively understandable for people
that aware of Saga pattern.  This library lets us compose transaction
steps both in sequence and in parallel, this feature gives us more
powerful control over transaction execution.

# Advanced Usage

An advanced example of working application that stores saga state in DB (journaling) can be found 
here [examples](/examples).

## Retrying

`cats-saga` provides us with functions for retrying our compensating actions, so we can write:

 ```scala
collectPayments(2d, 2) retryableCompensate (refundPayments(2d, 2), RetryPolicies.exponentialBackoff(1.second))
```

In this example our Saga will retry compensating action `refundPayments` after exponentially 
increasing timeouts (based on [cats-retry](https://github.com/cb372/cats-retry)).


### Parallel execution

Saga pattern does not limit transactional requests to run only in sequence.
Because of that `cats-sagas` contains methods for parallel execution of requests. 

```scala
    val flight          = bookFlight compensate cancelFlight
    val hotel           = bookHotel compensate cancelHotel
    val bookingSaga     = flight zipPar hotel
```

Note that in this case two compensations would run in sequence, one after another by default.
If we need to execute compensations in parallel consider using `Saga#zipWithParAll` function, it allows arbitrary 
combinations of compensating actions.

### Result dependent compensations

Depending on the result of compensable effect we may want to execute specific compensation, for such cases `cats-saga`
contains specific functions:
- `compensate(compensation: Either[E, A] => F[Unit])` this function makes compensation dependent on the result 
of corresponding effect that either fails or succeeds.
- `compensateIfFail(compensation: E => F[Unit])` this function makes compensation dependent only on error type 
hence compensation will only be triggered if corresponding effect fails.
- `compensateIfSuccess(compensation: A => F[Unit])` this function makes compensation dependent only on
successful result type hence compensation can only occur if corresponding effect succeeds.

### Notes on compensation action failures

By default, if some compensation action fails no other compensation would run and therefore user has the ability to 
choose what to do: stop compensation (by default), retry failed compensation step until it succeeds or proceed to next 
compensation steps ignoring the failure.
