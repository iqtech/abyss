/*
 * Copyright 2013-2014 IQ TECH <http://www.iqtech.pl>
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

package io.abyss.test.graph

import akka.util.Timeout
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import akka.actor.ActorSystem

/**
 * Created by cane, 12/1/13 12:15 PM
 * $Id: PersistenceTestBase.scala,v 1.2 2013-12-31 21:09:28 cane Exp $
 */
trait PersistenceTestBase {
	System.setProperty ("config.resource", "/persistence-test.conf")

	implicit val ec = ExecutionContext.global
	implicit val system = ActorSystem("test")

	def awaitTermination(s: Int) = try {
		println("Awaiting termination...")
		val timeout = Timeout(s seconds)
		val duration = timeout.duration
		system.awaitTermination(duration)
	} catch {
		case e: Throwable =>
			println("EOT -> %s" format e.getMessage)
			e.printStackTrace()
	}

}
