/*
 * Copyright (c) 2019-2019 by The Monix Project Developers.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.bio.internal

import java.util.concurrent.RejectedExecutionException

import monix.bio.BIO
import monix.bio.BIO.{Async, Context}
import monix.execution.Scheduler

private[bio] object TaskExecuteOn {

  /**
    * Implementation for `Task.executeOn`.
    */
  def apply[E, A](source: BIO[E, A], s: Scheduler, forceAsync: Boolean): BIO[E, A] = {
    val withTrampoline = !forceAsync
    val start =
      if (forceAsync) new AsyncRegister(source, s)
      else new TrampolinedStart(source, s)

    Async(
      start,
      trampolineBefore = withTrampoline,
      trampolineAfter = withTrampoline,
      restoreLocals = false
    )
  }

  // Implementing Async's "start" via `ForkedStart` in order to signal
  // that this is task that forks on evaluation
  private final class AsyncRegister[E, A](source: BIO[E, A], s: Scheduler) extends ForkedRegister[E, A] {

    def apply(ctx: Context[E], cb: BiCallback[E, A]): Unit = {
      val oldS = ctx.scheduler
      val ctx2 = ctx.withScheduler(s)

      try {
        BIO.unsafeStartAsync(
          source,
          ctx2,
          new BiCallback[E, A] with Runnable {
            private[this] var value: A = _
            private[this] var error: E = _
            private[this] var fatalError: Throwable = _

            def onSuccess(value: A): Unit = {
              this.value = value
              oldS.execute(this)
            }

            def onError(ex: E): Unit = {
              this.error = ex
              oldS.execute(this)
            }

            override def onFatalError(e: Throwable): Unit = {
              this.fatalError = e
              oldS.execute(this)
            }

            def run() = {
              if (fatalError ne null) cb.onFatalError(fatalError)
              else if (error != null) cb.onError(error)
              else cb.onSuccess(value)
            }

          }
        )
      } catch {
        case e: RejectedExecutionException =>
          cb.onFatalError(e)
      }
    }
  }

  private final class TrampolinedStart[E, A](source: BIO[E, A], s: Scheduler)
      extends ((Context[E], BiCallback[E, A]) => Unit) {

    def apply(ctx: Context[E], cb: BiCallback[E, A]): Unit = {
      val ctx2 = ctx.withScheduler(s)
      try {
        BIO.unsafeStartNow(source, ctx2, cb)
      } catch {
        case e: RejectedExecutionException =>
          BiCallback.signalFatalErrorTrampolined(cb, e)
      }
    }
  }
}
