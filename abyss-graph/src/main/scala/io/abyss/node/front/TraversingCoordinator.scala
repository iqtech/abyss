/*
 * This software is licensed under the Apache 2 license, quoted below.
 *
 * Copyright 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.abyss.node.front

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import io.abyss._
import io.abyss.node._


/*
 * Created by cane, 26.07.13 14:07
 * $Id: TraversingCoordinator.scala,v 1.2 2013-12-31 21:09:28 cane Exp $
 */


/**
 * One for each incoming query which must be traversed. On QueryTraversable spawns remote workers on each selected
 * node and then starts traversing by sending StartCoordinatedWork. Receives CoordinatedWorkDone on finish and then
 * kills all workers via PoisonPill message.
 */
class TraversingCoordinator (val shardMap: Array[ Address ])
	extends AbyssActor {

	implicit val defaultTimeout = Timeout (2 seconds)

	var traversers: Set[ ActorRef ] = _

	var client: ActorRef = _


	def receive = {

		case msg: QueryTraversable =>
			client = sender

			// send job to nodes
			val futures = shardMap.distinct map {
				address =>
					val prepareMsg = PrepareQueryTraversable (msg, shardMap)
					val path = RootActorPath (address) / "user" / "node" / "data" / "query"
					ask (context.actorSelection (path), prepareMsg).mapTo[ WorkerReady ]
			}

			// wait for future results of WorkerReady
			val resFuture = Future.fold (futures)(Set.empty[ ActorRef ])(_ + _.ref)

			// TODO ReliableChannel should be created here to support remote calls

			log.info ("Query sent to workers, waiting for ready messages")

			resFuture foreach {
				tr =>
					log.info ("Workers ready, starting coordination")
					traversers = tr
					// start worker
					val path = RootActorPath (shardMap (shardId (msg.startAt))) / "user" / "node" / "data" / "query" / msg.id
					context.actorSelection (path) ! StartCoordinatedWork (self)
			}


		case msg: CoordinatedWorkDone =>

			log.info ("Coordinated query traversable completed: ")

			traversers foreach {
				t => t ! PoisonPill
			}

			// TODO gather results

			client ! TraversingResult (msg.data.asInstanceOf[ Array[ String ] ])


		case msg =>
			log.warning ("Unknown message: {}", msg.toString)
	}
}
